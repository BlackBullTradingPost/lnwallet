package com.lightning.wallet.lncloud

import com.lightning.wallet.ln._
import scala.concurrent.duration._
import com.lightning.wallet.Utils._
import com.lightning.wallet.ln.wire._
import com.lightning.wallet.lncloud.ImplicitConversions._
import com.lightning.wallet.helper.{SocketListener, SocketWrap}
import org.bitcoinj.core.{StoredBlock, Transaction}
import rx.lang.scala.{Observable => Obs}

import com.lightning.wallet.lncloud.ChannelSaver.ChannelSnapshot
import org.bitcoinj.core.listeners.NewBestBlockListener
import concurrent.ExecutionContext.Implicits.global
import com.lightning.wallet.ln.crypto.Noise.KeyPair
import com.lightning.wallet.ln.Tools.none
import com.lightning.wallet.TxTracker
import fr.acinq.bitcoin.BinaryData
import scala.concurrent.Future


object ChannelManager {
  var allChannels = ChannelSaver.tryGetObject getOrElse Set.empty map fresh
  var activeKits = allChannels.filterNot(_.data == Channel.CLOSING) map ChannelKit

  val blockchainListener = new TxTracker with NewBestBlockListener {
    override def coinsSent(tx: Transaction) = for (chan <- allChannels) chan process CMDSomethingSpent(tx)
    override def txConfirmed(tx: Transaction) = for (kit <- activeKits) kit.chan process CMDSomethingConfirmed(tx)
    override def notifyNewBestBlock(block: StoredBlock) = for (kit <- activeKits) kit.chan process CMDDepth(block.getHeight)
  }

  app.kit.wallet addCoinsSentEventListener blockchainListener
  app.kit.blockChain addNewBestBlockListener blockchainListener
  app.kit.wallet addTransactionConfidenceEventListener blockchainListener

  def fresh(snapshot: ChannelSnapshot) =
    new Channel match { case freshChannel =>
      val recoveredData ~ recoveredState = snapshot
      freshChannel.listeners += LNParams.broadcaster
      freshChannel.state = recoveredState
      freshChannel.data = recoveredData
      freshChannel
    }
}

case class ChannelKit(chan: Channel) { me =>
  val address = chan.data.announce.addresses.head
  lazy val socket = new SocketWrap(address.getAddress, address.getPort) {
    def onReceive(dataChunk: BinaryData): Unit = handler process dataChunk
  }

  val keyPair = KeyPair(LNParams.nodePrivateKey.publicKey, LNParams.nodePrivateKey.toBin)
  val handler: TransportHandler = new TransportHandler(keyPair, chan.data.announce.nodeId, socket) {
    def feedForward(msg: BinaryData): Unit = interceptIncomingMsg(LightningMessageCodecs deserialize msg)
  }

  val reconnectSockListener = new SocketListener {
    override def onDisconnect = Obs.just(Tools log "Reconnecting a socket")
      .delay(5.seconds).subscribe(_ => socket.start, _.printStackTrace)
  }

  socket.listeners += new SocketListener {
    override def onConnect: Unit = handler.init
    override def onDisconnect = Tools log "Sock off"
  }

  handler.listeners += new StateMachineListener {
    override def onBecome: PartialFunction[Transition, Unit] = {
      case (_, _, TransportHandler.HANDSHAKE, TransportHandler.WAITING_CYPHERTEXT) =>
        Tools log s"Handler handshake phase completed, now sending Init message"
        me send Init(LNParams.globalFeatures, LNParams.localFeatures)
    }

    override def onError = {
      case transportRelated: Throwable =>
        Tools log s"Transport $transportRelated"
        chan process CMDShutdown
    }
  }

  chan.listeners += new StateMachineListener { self =>
    override def onBecome: PartialFunction[Transition, Unit] = {
      case (previousData, followingData, previousState, followingState) =>
        val messages = Helpers.extractOutgoingMessages(previousData, followingData)
        Tools log s"Sending $previousState -> $followingState messages: $messages"
        messages foreach send
    }

    override def onPostProcess = {
      case Error(_, reason: BinaryData) =>
        val decoded = new String(reason.toArray)
        Tools log s"Got remote Error: $decoded"
    }
  }

  def tellChannel(msg: Any) = Future(chan process msg)
  private def interceptIncomingMsg(msg: LightningMessage) = msg match {
    case Ping(responseLength, _) => if (responseLength > 0) me send Pong("00" * responseLength)
    case Init(_, local) if !Features.areSupported(local) => chan process CMDShutdown
    case _ => chan process msg
  }

  def send(msg: LightningMessage) = {
    val encoded = LightningMessageCodecs serialize msg
    handler process Tuple2(TransportHandler.Send, encoded)
  }
}