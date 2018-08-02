package scorex.core.network

import akka.actor.{ActorRef, ActorSystem, Cancellable}
import scorex.core.consensus.ContainsModifiers
import scorex.core.network.ModifiersStatus._
import scorex.core.network.NodeViewSynchronizer.ReceivableMessages.CheckDelivery
import scorex.core.utils.{ScorexEncoding, ScorexLogging}
import scorex.core.{ModifierId, ModifierTypeId, NodeViewModifier}

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Try}


/**
  * This class tracks modifier statuses.
  * Modifier can be in one of the following states: Unknown, Requested, Received, Applied, Invalid. See
  * ModifiersStatus for states description.
  * Modifiers in `Requested` state are kept in `requested` map containing info about peer and number of retries.
  * Modifiers in `Received` state are kept in `received` set.
  * Modifiers in `Invalid` state are kept in `invalid` set to prevent this modifier download and processing.
  * Modifiers in `Applied` state are not kept in this class - we can get this status from object, that contains
  * these modifiers (History for PersistentNodeViewModifier, Mempool for EphemerealNodeViewModifier).
  * If we can't identify modifiers status based on the rules above, it's status is Unknown.
  *
  * In success path modifier changes his statuses `Unknown`->`Requested`->`Received`->`Applied`.
  * If something went wrong (e.g. modifier was not delivered) it goes back to `Unknown` state
  * (if we are going to receive it in future) or to `Invalid` state (if we are not going to receive
  * this modifier anymore)
  * Locally generated modifiers may go to `Applied` or `Invalid` states at any time.
  * These rules are also described in `isCorrectTransition` function.
  */
class DeliveryTracker(system: ActorSystem,
                      deliveryTimeout: FiniteDuration,
                      maxDeliveryChecks: Int,
                      nvsRef: ActorRef) extends ScorexLogging with ScorexEncoding {

  protected case class RequestedInfo(peer: Option[ConnectedPeer], cancellable: Cancellable, checks: Int)

  // when a remote peer is asked a modifier, we add the expected data to `requested`
  protected val requested: mutable.Map[ModifierId, RequestedInfo] = mutable.Map[ModifierId, RequestedInfo]()

  // when our node received invalid modidier, we put it to `invalid`
  protected val invalid: mutable.HashSet[ModifierId] = mutable.HashSet[ModifierId]()

  // when our node received a modifier, from remote peer, we put it to `received`
  protected val received: mutable.HashSet[ModifierId] = mutable.HashSet[ModifierId]()

  /**
    * @return status of modifier `id`.
    *         Since we do not keep statuses for already applied modifiers,
    *         `modifierKeepers` are required here to check that modifier was already applied
    */
  def status(id: ModifierId, modifierKeepers: Seq[ContainsModifiers[_]]): ModifiersStatus = {
    if (received.contains(id)) {
      Received
    } else if (requested.contains(id)) {
      Requested
    } else if (invalid.contains(id)) {
      Invalid
    } else if (modifierKeepers.exists(_.contains(id))) {
      Applied
    } else {
      Unknown
    }
  }

  def status(id: ModifierId, mk: ContainsModifiers[_ <: NodeViewModifier]): ModifiersStatus = status(id, Seq(mk))

  def status(id: ModifierId): ModifiersStatus = status(id, Seq())

  /**
    * Someone should have these modifiers, but we do not know who
    */
  def onRequest(cp: Option[ConnectedPeer], mtid: ModifierTypeId, mids: Seq[ModifierId])(implicit ec: ExecutionContext): Try[Unit] =
    tryWithLogging(mids.foreach(mid => onRequest(cp, mtid, mid, 0)))

  /**
    * Put modifier id and corresponding peer to expecting map
    */
  protected def onRequest(cp: Option[ConnectedPeer],
                          mtid: ModifierTypeId,
                          mid: ModifierId,
                          checksDone: Int)(implicit ec: ExecutionContext): Unit = {
    val cancellable = system.scheduler.scheduleOnce(deliveryTimeout, nvsRef, CheckDelivery(cp, mtid, mid))
    updateStatus(mid, Requested, Some(RequestedInfo(cp, cancellable, checksDone)))
  }

  /**
    *
    * Our node have requested a modifier, but did not received it yet
    * Stops expecting, and expects again if the number of checks does not exceed the maximum
    *
    * @return `true` when expect again, `false` otherwise
    */
  def onStillWaiting(cp: ConnectedPeer,
                     mtid: ModifierTypeId,
                     mid: ModifierId)(implicit ec: ExecutionContext): Try[Unit] = tryWithLogging {
    val checks = requested(mid).checks + 1
    if (checks < maxDeliveryChecks) {
      stopExpecting(mid)
      onRequest(Some(cp), mtid, mid, checks)
    } else {
      stopProcessing(mid)
      throw new StopExpectingError(mid, checks)
    }
  }

  /**
    * Modifier was received from remote peer.
    */
  def onReceive(mid: ModifierId): Unit = tryWithLogging {
    updateStatus(mid, Received)
  }

  /**
    * Modifier was successfully applied to history - set it status to applied
    */
  def onApply(mid: ModifierId): Unit = {
    updateStatus(mid, Applied)
  }

  /**
    * Modified is permanently invalid - set it status to invalid
    */
  def onInvalid(mid: ModifierId): Unit = {
    updateStatus(mid, Invalid)
  }

  /**
    * We're not trying to process modifier anymore
    * This may happen when received modifier bytes does not correspond to declared modifier id,
    * this modifier was removed from cache because cache is overfull or
    * we stop trying to download this modifiers due to exceeded number of retries
    */
  def stopProcessing(id: ModifierId): Unit = {
    updateStatus(id, Unknown)
  }

  protected def stopExpecting(mid: ModifierId): Unit = {
    requested(mid).cancellable.cancel()
    requested.remove(mid)
  }

  /**
    * Set status of modifier with id `id` to `newStatus`
    */
  protected def updateStatus(id: ModifierId,
                             newStatus: ModifiersStatus,
                             requestedInfoOpt: Option[RequestedInfo] = None): ModifiersStatus = {
    val oldStatus: ModifiersStatus = status(id)
    log.debug(s"Set modifier ${encoder.encode(id)} from status $oldStatus to status $newStatus.")
    if (oldStatus == Requested) {
      stopExpecting(id)
    } else if (oldStatus == Received) {
      received.remove(id)
    }
    assert(status(id) == Unknown, "Intermediate check, that modifier status was cleared")
    if (newStatus == Received) {
      received.add(id)
    } else if (newStatus == Requested) {
      requestedInfoOpt.foreach(s => requested.put(id, s))
    } else if (newStatus == Invalid) {
      invalid.add(id)
    }
    oldStatus
  }.ensuring(oldStatus => isCorrectTransition(oldStatus, newStatus))

  /**
    * Self-check, that transition between states is correct.
    *
    * Modifier may stay in current state,
    * go to Requested state form Unknown
    * go to Received state from Requested
    * go to Applied state from any state (this may happen on locally generated modifier)
    * go to Invalid state from any state (this may happen on invalid locally generated modifier)
    * go to Unknown state from Requested and Received states
    */
  protected def isCorrectTransition(oldStatus: ModifiersStatus, newStatus: ModifiersStatus): Boolean = {
    oldStatus match {
      case old if old == newStatus => true
      case old if newStatus == Invalid || newStatus == Applied => true
      case Unknown => newStatus == Requested
      case Requested => newStatus == Unknown || newStatus == Received
      case Received => newStatus == Unknown
      case _ => false
    }
  }

  protected def tryWithLogging[T](fn: => T): Try[T] = {
    Try(fn).recoverWith {
      case e: StopExpectingError =>
        log.warn(e.getMessage)
        Failure(e)
      case e =>
        log.warn("Unexpected error", e)
        Failure(e)
    }
  }

  class StopExpectingError(mid: ModifierId, checks: Int)
    extends Error(s"Stop expecting ${encoder.encode(mid)} due to exceeded number of retries $checks")

}