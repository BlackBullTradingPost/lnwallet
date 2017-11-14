package com.lightning.wallet

import R.string._
import spray.json._
import org.bitcoinj.core._
import com.lightning.wallet.ln._
import scala.concurrent.duration._
import com.lightning.wallet.Utils._
import com.lightning.wallet.ln.Tools._
import spray.json.DefaultJsonProtocol._
import com.lightning.wallet.ln.LNParams._
import com.lightning.wallet.ln.PaymentInfo._
import com.lightning.wallet.lnutils.ImplicitJsonFormats._
import com.lightning.wallet.lnutils.ImplicitConversions._
import java.util.concurrent.TimeUnit.MILLISECONDS
import com.lightning.wallet.ln.Channel.CLOSING

import rx.lang.scala.{Observable => Obs}
import org.bitcoinj.wallet.{Protos, Wallet}
import com.lightning.wallet.ln.wire.{Init, LightningMessage}
import android.content.{ClipData, ClipboardManager, Context}
import org.bitcoinj.uri.{BitcoinURI, BitcoinURIParseException}
import com.google.common.util.concurrent.Service.State.{RUNNING, STARTING}
import com.lightning.wallet.lnutils.{ChannelWrap, CloudAct, Notificator, RatesSaver}
import com.lightning.wallet.lnutils.PaymentInfoTable.updFailWaitingSql
import collection.JavaConverters.seqAsJavaListConverter
import com.lightning.wallet.lnutils.Connector.CMDStart
import org.bitcoinj.wallet.KeyChain.KeyPurpose
import org.bitcoinj.net.discovery.DnsDiscovery
import org.bitcoinj.wallet.Wallet.BalanceType
import org.bitcoinj.crypto.KeyCrypterScrypt
import com.google.common.net.InetAddresses
import fr.acinq.bitcoin.Crypto.PublicKey
import com.google.protobuf.ByteString
import scala.collection.mutable
import android.app.Application
import android.widget.Toast
import java.io.File


class WalletApp extends Application { me =>
  lazy val params = org.bitcoinj.params.RegTestParams.get
  lazy val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
  lazy val chainFile = new File(getFilesDir, s"$appName.spvchain")
  lazy val walletFile = new File(getFilesDir, s"$appName.wallet")
  var kit: WalletKit = _

  lazy val plur = getString(lang) match {
    case "eng" | "esp" => (opts: Array[String], num: Long) => if (num == 1) opts(1) else opts(2)
    case "chn" | "jpn" => (phraseOptions: Array[String], num: Long) => phraseOptions(1)
    case "rus" | "ukr" => (phraseOptions: Array[String], num: Long) =>

      val reminder100 = num % 100
      val reminder10 = reminder100 % 10
      if (reminder100 > 10 & reminder100 < 20) phraseOptions(3)
      else if (reminder10 > 1 & reminder10 < 5) phraseOptions(2)
      else if (reminder10 == 1) phraseOptions(1)
      else phraseOptions(3)
  }

  // Various utilities

  def toast(code: Int): Unit = toast(app getString code)
  def toast(msg: CharSequence): Unit = Toast.makeText(me, msg, Toast.LENGTH_LONG).show
  def isAlive = if (null == kit) false else kit.state match { case STARTING | RUNNING => true case _ => false }
  def plurOrZero(opts: Array[String], number: Long) = if (number > 0) plur(opts, number) format number else opts(0)
  def clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE).asInstanceOf[ClipboardManager]
  def getBuffer = clipboardManager.getPrimaryClip.getItemAt(0).getText.toString
  def getTo(base58: String) = Address.fromBase58(params, base58)

  appReference = me
  override def onCreate = wrap(super.onCreate) {
    // These cannot just be lazy vals because their values may change
    denom = denoms apply prefs.getInt(AbstractKit.DENOMINATION, 0)
    fiatName = prefs.getString(AbstractKit.FIAT, strDollar)
  }

  def setBuffer(text: String) = wrap(me toast copied_to_clipboard) {
    clipboardManager setPrimaryClip ClipData.newPlainText(appName, text)
  }

  def encryptWallet(wallet: Wallet, pass: CharSequence) = {
    val salt = ByteString copyFrom KeyCrypterScrypt.randomSalt
    val builder = Protos.ScryptParameters.newBuilder.setSalt(salt)
    val crypter = new KeyCrypterScrypt(builder.setN(65536).build)
    wallet.encrypt(crypter, crypter deriveKey pass)
  }

  object TransData {
    var value: Any = _
    def onFail(err: Int => Unit): PartialFunction[Throwable, Unit] = {
      case _: org.bitcoinj.core.WrongNetworkException => err(err_different_net)
      case _: org.bitcoinj.core.AddressFormatException => err(err_address)
      case _: BitcoinURIParseException => err(err_uri)
      case _: ArithmeticException => err(err_neg)
      case _: Throwable => err(err_general)
    }

    def recordValue(rawText: String) = value = rawText match {
      case raw if raw startsWith "bitcoin" => new BitcoinURI(params, raw)
      case raw if raw startsWith "lnbc" => PaymentRequest read raw
      case raw if raw startsWith "lntb" => PaymentRequest read raw
      case raw => getTo(raw)
    }
  }

  object ChannelManager {
    import ConnectionManager._
    type ChannelVec = Vector[Channel]

    var all: ChannelVec = ChannelWrap.get map createChannel // Receive CMDSpent and CMDBestHeight, nothing else
    def connected: ChannelVec = all.filter(_.state != Channel.CLOSING) // Those who need a connection to LN peer
    def alive: ChannelVec = all.filter(channel => channel.state != Channel.CLOSING && channel.state != Channel.REFUNDING)
    def fromNode(of: ChannelVec, id: PublicKey) = of.filter(_.data.announce.nodeId == id) // Those from specific ln peer
    def reconnect(of: ChannelVec) = of.map(_.data.announce).distinct foreach requestConnection

    val chainEventsListener = new TxTracker with BlocksListener {
      override def coinsSent(tx: Transaction) = CMDSpent(tx) match { case spent =>
        // Any incoming tx may spend HTLCs so we always attempt to extract a preimage
        for (channel <- all) channel process spent
        bag.extractPreimage(spent.tx)
      }

      override def txConfirmed(tx: Transaction) = for (chan <- alive) chan process CMDConfirmed(tx)
      // No matter how many blocks are left on start we only send a CMD once the last block has been processed
      def tellHeight(left: Int) = if (left < 1) for (chan <- all) chan process CMDBestHeight(broadcaster.currentHeight)
      override def onBlocksDownloaded(peer: Peer, block: Block, fb: FilteredBlock, left: Int) = tellHeight(left)
      override def onChainDownloadStarted(peer: Peer, left: Int) = tellHeight(left)
    }

    val socketEventsListener = new ConnectionListener {
      override def onOperational(id: PublicKey, their: Init) = fromNode(connected, id).foreach(_ process CMDOnline)
      override def onTerminalError(id: PublicKey) = fromNode(connected, id).foreach(_ process CMDShutdown)
      override def onMessage(msg: LightningMessage) = connected.foreach(_ process msg)

      override def onDisconnect(id: PublicKey) =
        fromNode(connected, id) match { case needsReconnect =>
          val delayed = Obs.just(Tools log s"Retrying $id").delay(5.seconds)
          delayed.subscribe(_ => reconnect(needsReconnect), Tools.errlog)
          needsReconnect.foreach(_ process CMDOffline)
        }
    }

    def createChannel(bootstrap: ChannelData) = new Channel {
      val listeners = mutable.Set(broadcaster, bag, ChannelWrap, Notificator)
      def SEND(msg: LightningMessage) = connections.get(data.announce.nodeId).foreach(_.handler process msg)
      def STORE(hasCommitments: HasCommitments) = runAnd(hasCommitments)(ChannelWrap put hasCommitments)
      // First add listeners, then specifically call doProcess so it runs on UI thread
      doProcess(bootstrap)

      def CLOSEANDWATCH(cd: ClosingData) = {
        BECOME(data1 = STORE(cd), state1 = CLOSING)
        // Ask server once for txs which may spend our commit txs outputs to extract preimages
        cloud.connector.getChildTxs(cd.commitTxs).foreach(_ foreach bag.extractPreimage, Tools.errlog)
        // Collect all the commit txs output publicKeyScripts and watch them locally for payment preimages
        kit.watchScripts(cd.commitTxs.flatMap(_.txOut).map(_.publicKeyScript) map bitcoinLibScript2bitcoinjScript)

        if (cd.localCommit.nonEmpty) {
          // Schedule local tier1-2 txs on server just in case
          val txsJson = cd.localCommit.head.getState.map(_.txn).toJson
          cloud doProcess CloudAct(txsJson.toString.hex, Nil, "txs/schedule")
        }
      }
    }

    // Get routes from maintenance server and make an OutgoingPayment if routes exist
    // If payment request contains extra routing info then we also ask for assisted routes
    // Once direct route and assisted routes are fetched we combine them into single sequence
    def outPaymentObs(badNodes: Set[PublicKey], badChannels: Set[Long], pr: PaymentRequest) =

      Obs from alive.headOption flatMap { chan =>
        def findRoutes(target: PublicKey) = chan.data.announce.nodeId match {
          case directPeerNode if directPeerNode == target => Obs just Vector(Vector.empty)
          case _ => cloud.connector.findRoutes(badNodes, badChannels, chan.data.announce.nodeId, target)
        }

        def augmentAssisted(tag: RoutingInfoTag) = for {
          publicRoutes <- findRoutes(tag.route.head.nodeId)
        } yield publicRoutes.flatMap(_ ++ tag.route)

        val allAssisted = Obs.zip(Obs from pr.routingInfo map augmentAssisted)
        findRoutes(pr.nodeId).zipWith(allAssisted orElse Vector.empty) { case direct \ assisted =>
          val routes = for (rt <- direct ++ assisted) yield buildRelativeRoute(rt, pr.finalSum.amount)
          buildPayment(RoutingData(routes, badNodes, badChannels), pr, chan)
        }
      }
  }

  abstract class WalletKit extends AbstractKit {
    type ScriptSeq = Seq[org.bitcoinj.script.Script]
    def blockingSend(tx: Transaction) = peerGroup.broadcastTransaction(tx, 1).broadcast.get
    def watchFunding(cs: Commitments) = watchScripts(cs.commitInput.txOut.publicKeyScript :: Nil)
    def watchScripts(scripts: ScriptSeq) = app.kit.wallet addWatchedScripts scripts.asJava
    def currentBalance = wallet getBalance BalanceType.ESTIMATED_SPENDABLE
    def currentAddress = wallet currentAddress KeyPurpose.RECEIVE_FUNDS
    def currentHeight = blockChain.getBestChainHeight
    def shutDown = none

    def useCheckPoints(time: Long) = {
//      val pts = getAssets open "checkpoints-testnet.txt"
//      CheckpointManager.checkpoint(params, pts, store, time)
    }

    def decryptSeed(pass: String) = wallet.getKeyCrypter match { case crypter =>
      wallet.getKeyChainSeed.decrypt(crypter, pass, crypter deriveKey pass)
    }

    def setupAndStartDownload = {
      wallet addTransactionConfidenceEventListener ChannelManager.chainEventsListener
      wallet addCoinsSentEventListener ChannelManager.chainEventsListener
      wallet addTransactionConfidenceEventListener Vibr.generalTracker
      wallet addCoinsReceivedEventListener Vibr.generalTracker
      wallet addCoinsSentEventListener Vibr.generalTracker
      wallet.watchMode = true

      peerGroup addAddress new PeerAddress(app.params,
        InetAddresses forString cloud.connector.url, 8333)

//      peerGroup addPeerDiscovery new DnsDiscovery(params)
      peerGroup.setMinRequiredProtocolVersion(70015)
      peerGroup.setUserAgent(appName, "0.01")
      peerGroup.setDownloadTxDependencies(0)
      peerGroup.setPingIntervalMsec(10000)
      peerGroup.setMaxConnections(5)
      peerGroup.addWallet(wallet)

      wallet.autosaveToFile(walletFile, 400, MILLISECONDS, null)
      ConnectionManager.listeners += ChannelManager.socketEventsListener
      startBlocksDownload(ChannelManager.chainEventsListener)
      ChannelManager reconnect ChannelManager.connected
      db change updFailWaitingSql
      cloud doProcess CMDStart
      RatesSaver.update
    }
  }
}

object Vibr {
  type Pattern = Array[Long]
  def vibrate(pattern: Pattern) = if (null != vib && vib.hasVibrator) vib.vibrate(pattern, -1)
  lazy val vib = app.getSystemService(Context.VIBRATOR_SERVICE).asInstanceOf[android.os.Vibrator]
  val confirmed = Array(0L, 75, 250, 75, 250)
  val processed = Array(0L, 85, 200)

  val generalTracker = new TxTracker {
    override def txConfirmed(tx: Transaction) = vibrate(confirmed)
    override def coinsReceived(tx: Transaction) = vibrate(processed)
    override def coinsSent(tx: Transaction) = vibrate(processed)
  }
}