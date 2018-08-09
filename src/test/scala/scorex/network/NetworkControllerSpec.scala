package scorex.network

import java.net
import java.net.{InetAddress, InetSocketAddress}

import akka.actor.{ActorRef, ActorSystem}
import akka.io.Tcp
import akka.io.Tcp.{Bind, Bound, Connected, Write}
import akka.io.Tcp.SO.KeepAlive
import akka.testkit.TestActor.RealMessage
import akka.testkit.TestProbe
import akka.util.ByteString
import com.google.common.primitives.Ints
import org.scalatest.{FlatSpec, Matchers}
import scorex.core.network._
import scorex.core.network.message._
import scorex.core.network.peer.{LocalAddressPeerFeature, LocalAddressPeerFeatureSerializer, PeerManagerRef}
import scorex.core.settings.{NetworkSettings, ScorexSettings}
import scorex.core.utils.NetworkTimeProvider
import org.scalatest.TryValues._
import org.scalatest.OptionValues._
import org.scalatest.EitherValues._
import scorex.core.app.Version

import scala.concurrent.ExecutionContext
import scala.util.Try

class NetworkControllerSpec extends FlatSpec with Matchers {

  import scala.concurrent.ExecutionContext.Implicits.global
  private val settings = ScorexSettings.read(None)

  "A NetworkController" should "send local address on handshake when peer and node address is loopback" in {
    implicit val system = ActorSystem()

    val tcpManagerProbe = TestProbe()
    val networkControllerRef: ActorRef = createNetworkController(settings, tcpManagerProbe)
    val testPeer = new TestPeer(settings, networkControllerRef, tcpManagerProbe)

    testPeer.connect(new InetSocketAddress("127.0.0.1", 5678))

    val handshakeFromNode  = testPeer.receiveHandshake
    handshakeFromNode.success.value.declaredAddress shouldBe empty
    val localAddressFeature = extractLocalAddrFeat(handshakeFromNode)
    localAddressFeature.value.address should be (new InetSocketAddress("127.0.0.1", settings.network.bindAddress.getPort))

    testPeer.sendHandshake(None, None)
    system.terminate()
  }

  it should "send local address on handshake when the peer in local network" in {
    implicit val system = ActorSystem()

    val tcpManagerProbe = TestProbe()
    val networkControllerRef: ActorRef = createNetworkController(settings, tcpManagerProbe)
    val testPeer = new TestPeer(settings, networkControllerRef, tcpManagerProbe)

    testPeer.connect(new InetSocketAddress("192.168.0.1", 5678))

    val handshakeFromNode  = testPeer.receiveHandshake
    handshakeFromNode.success.value.declaredAddress shouldBe empty
    val localAddressFeature = extractLocalAddrFeat(handshakeFromNode)
    localAddressFeature.value.address should be (new InetSocketAddress("127.0.0.1", settings.network.bindAddress.getPort))

    testPeer.sendHandshake(None, None)
    system.terminate()
  }

  it should "not send local address on handshake when peer is external" in {
    implicit val system = ActorSystem()

    val tcpManagerProbe = TestProbe()
    val networkControllerRef: ActorRef = createNetworkController(settings, tcpManagerProbe)
    val testPeer = new TestPeer(settings, networkControllerRef, tcpManagerProbe)

    testPeer.connect(new InetSocketAddress("88.77.66.55", 5678))

    val handshakeFromNode  = testPeer.receiveHandshake
    handshakeFromNode.success.value.declaredAddress shouldBe empty
    val localAddressFeature = extractLocalAddrFeat(handshakeFromNode)
    localAddressFeature shouldBe empty

    testPeer.sendHandshake(None, None)
    system.terminate()
  }

  it should "send declared address when node and peer is public" in {
    implicit val system = ActorSystem()

    val tcpManagerProbe = TestProbe()

    val bindAddress = new InetSocketAddress("88.77.66.55", 12345)
    val settings2 = settings.copy(network = settings.network.copy(bindAddress = bindAddress))
    val networkControllerRef: ActorRef = createNetworkController(settings2, tcpManagerProbe)
    val testPeer = new TestPeer(settings2, networkControllerRef, tcpManagerProbe)

    testPeer.connect(new InetSocketAddress("88.77.66.55", 5678))

    val handshakeFromNode  = testPeer.receiveHandshake
    handshakeFromNode.success.value.declaredAddress.value should be (bindAddress)
    val localAddressFeature = extractLocalAddrFeat(handshakeFromNode)
    localAddressFeature shouldBe empty

    testPeer.sendHandshake(None, None)
    system.terminate()
  }

  it should "send known public peers" in {
    implicit val system = ActorSystem()

    val tcpManagerProbe = TestProbe()

    val bindAddress = new InetSocketAddress("88.77.66.55", 12345)
    val settings2 = settings.copy(network = settings.network.copy(bindAddress = bindAddress))
    val networkControllerRef: ActorRef = createNetworkController(settings2, tcpManagerProbe)
    val testPeer1 = new TestPeer(settings2, networkControllerRef, tcpManagerProbe)
    val peer1Addr = new InetSocketAddress("88.77.66.55", 5678)
    testPeer1.connect(peer1Addr)
    testPeer1.receiveHandshake
    testPeer1.sendHandshake(Some(peer1Addr), None)
    testPeer1.receiveGetPeers
    testPeer1.sendPeers(Seq.empty)

    val peer2Addr = new InetSocketAddress("88.77.66.56", 5678)
    val testPeer2 = new TestPeer(settings2, networkControllerRef, tcpManagerProbe)
    testPeer2.connect(peer2Addr)
    testPeer2.receiveHandshake
    testPeer2.sendHandshake(Some(peer2Addr), None)

    testPeer1.sendGetPeers
    testPeer1.receivePeers should contain theSameElementsAs Seq(peer1Addr, peer2Addr)

    system.terminate()
  }

  it should "send known local peers" in {
    implicit val system = ActorSystem()

    val tcpManagerProbe = TestProbe()

    val bindAddress = new InetSocketAddress("88.77.66.55", 12345)
    val settings2 = settings.copy(network = settings.network.copy(bindAddress = bindAddress))
    val networkControllerRef: ActorRef = createNetworkController(settings2, tcpManagerProbe)

    val testPeer1 = new TestPeer(settings2, networkControllerRef, tcpManagerProbe)
    val peer1DecalredAddr = new InetSocketAddress("88.77.66.55", 5678)
    val peer1LocalAddr = new InetSocketAddress("192.168.1.55", 5678)
    testPeer1.connect(peer1LocalAddr)
    testPeer1.receiveHandshake
    testPeer1.sendHandshake(Some(peer1DecalredAddr), Some(peer1LocalAddr))
    testPeer1.receiveGetPeers
    testPeer1.sendPeers(Seq.empty)

    val peer2DeclaredAddr = new InetSocketAddress("88.77.66.56", 5678)
    val peer2LocalAddr = new InetSocketAddress("192.168.1.56", 5678)
    val testPeer2 = new TestPeer(settings2, networkControllerRef, tcpManagerProbe)
    testPeer2.connect(peer2LocalAddr)
    testPeer2.receiveHandshake
    testPeer2.sendHandshake(Some(peer2DeclaredAddr), Some(peer2LocalAddr))

    testPeer1.sendGetPeers
    testPeer1.receivePeers should contain theSameElementsAs Seq(peer1LocalAddr, peer2LocalAddr)

    system.terminate()
  }

  it should "not send known local peers when public node is not in local network" in {
    implicit val system = ActorSystem()

    val tcpManagerProbe = TestProbe()

    val bindAddress = new InetSocketAddress("88.77.66.55", 12345)
    val settings2 = settings.copy(network = settings.network.copy(bindAddress = bindAddress))
    val networkControllerRef: ActorRef = createNetworkController(settings2, tcpManagerProbe)

    val testPeer1 = new TestPeer(settings2, networkControllerRef, tcpManagerProbe)
    val peer1DecalredAddr = new InetSocketAddress("88.77.66.55", 5678)
    val peer1LocalAddr = new InetSocketAddress("192.168.1.55", 5678)
    testPeer1.connect(peer1LocalAddr)
    testPeer1.receiveHandshake
    testPeer1.sendHandshake(Some(peer1DecalredAddr), Some(peer1LocalAddr))
    testPeer1.receiveGetPeers
    testPeer1.sendPeers(Seq.empty)

    val peer2DeclaredAddr = new InetSocketAddress("88.77.66.56", 5678)
    val testPeer2 = new TestPeer(settings2, networkControllerRef, tcpManagerProbe)
    testPeer2.connect(peer2DeclaredAddr)
    testPeer2.receiveHandshake
    testPeer2.sendHandshake(Some(peer2DeclaredAddr), None)

    testPeer2.sendGetPeers
    testPeer2.receivePeers should contain theSameElementsAs Seq(peer1DecalredAddr, peer2DeclaredAddr)

    system.terminate()
  }

  private def extractLocalAddrFeat(handshakeFromNode: Try[Handshake]) = {
    handshakeFromNode.success.value.features.collectFirst { case a: LocalAddressPeerFeature => a }
  }

  private def createNetworkController(settings: ScorexSettings, tcpManagerProbe: TestProbe)(implicit system:ActorSystem) = {
    val timeProvider = new NetworkTimeProvider(settings.ntp)
    val peerManagerRef = PeerManagerRef(settings, timeProvider)

    val messageSpecs = Seq(GetPeersSpec, PeersSpec)
    val messagesHandler = MessageHandler(messageSpecs)
    val upnp = new UPnP(settings.network)


    val networkControllerRef: ActorRef = NetworkControllerRef("networkController", settings.network,
      messagesHandler, Seq.empty, upnp,
      peerManagerRef, timeProvider, tcpManagerProbe.testActor)


    tcpManagerProbe.expectMsg(Bind(networkControllerRef, settings.network.bindAddress, options = KeepAlive(true) :: Nil))

    tcpManagerProbe.send(networkControllerRef, Bound(settings.network.bindAddress))
    networkControllerRef
  }
}

class TestPeer(settings: ScorexSettings, networkControllerRef: ActorRef, tcpManagerProbe: TestProbe)
              (implicit ec:ExecutionContext) extends Matchers {

  private val timeProvider = new NetworkTimeProvider(settings.ntp)
  private val featureSerializers = Map(LocalAddressPeerFeature.featureId -> LocalAddressPeerFeatureSerializer)
  private val handshakeSerializer = new HandshakeSerializer(featureSerializers, settings.network.maxHandshakeSize)
  private val messageSpecs = Seq(GetPeersSpec, PeersSpec)
  private val messagesHandler = MessageHandler(messageSpecs)
  private val buffering = new Buffering {
    override def settings: NetworkSettings = TestPeer.this.settings.network
  }

  private var connectionHandler:ActorRef = _

  def connect(peerAddr: InetSocketAddress): Tcp.ResumeReading.type = {
    tcpManagerProbe.send(networkControllerRef, Connected(peerAddr, new InetSocketAddress("127.0.0.1", settings.network.bindAddress.getPort)))

    connectionHandler = tcpManagerProbe.expectMsgPF() {
      case Tcp.Register(handler, _, _) => handler
    }

    tcpManagerProbe.expectMsg(Tcp.ResumeReading)
  }

  def sendHandshake(declaredAddress: Option[InetSocketAddress], localAddress: Option[InetSocketAddress]): Tcp.ResumeReading.type = {
    val features = localAddress.map(LocalAddressPeerFeature(_)).toSeq
    val handshakeToNode = Handshake(settings.network.agentName,
      Version(settings.network.appVersion), "test",
      declaredAddress, features, timeProvider.time())

    tcpManagerProbe.send(connectionHandler, Tcp.Received(ByteString(handshakeSerializer.toBytes(handshakeToNode))))
    tcpManagerProbe.expectMsg(Tcp.ResumeReading)
    tcpManagerProbe.expectMsg(Tcp.ResumeReading)
  }

  def receiveHandshake: Try[Handshake] = {
    val handshakeFromNode = tcpManagerProbe.expectMsgPF() {
      case Tcp.Write(data, e) =>
        handshakeSerializer.parseBytes(data.toByteBuffer.array)
    }
    handshakeFromNode
  }

  def sendGetPeers: Unit = {
    val msg = Message[Unit](GetPeersSpec, Right(Unit), None)
    sendMessage(msg)
  }

  def receiveGetPeers: Message[_] = {
    val message = receiveMessage
    message.spec.messageCode should be (GetPeersSpec.messageCode)
    message
  }

  def receivePeers: Seq[InetSocketAddress] = {
    val message = receiveMessage
    message.spec.messageCode should be (PeersSpec.messageCode)
    PeersSpec.parseBytes(message.input.left.value).success.value
  }

  def sendPeers(peers: Seq[InetSocketAddress]): Unit = {
    val msg = Message(PeersSpec, Right(peers), None)
    sendMessage(msg)
  }

  def sendMessage(msg: Message[_]): Unit = {
    val bytes = msg.bytes
    tcpManagerProbe.send(connectionHandler, Tcp.Received(ByteString(Ints.toByteArray(bytes.length) ++ bytes)))
    tcpManagerProbe.expectMsg(Tcp.ResumeReading)
  }

  def receiveMessage: Message[_] = {
    tcpManagerProbe.expectMsgPF() {
      case Tcp.Write(b, _) =>
        val bytes = buffering.getPacket(b)._1.headOption.value.toByteBuffer
        messagesHandler.parseBytes(bytes, None).success.value
    }
  }
}