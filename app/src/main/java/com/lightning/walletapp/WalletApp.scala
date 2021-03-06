package com.lightning.walletapp

import R.string._
import org.bitcoinj.core._
import scala.concurrent.duration._
import com.lightning.walletapp.ln._
import com.lightning.walletapp.Utils._
import com.lightning.walletapp.lnutils._
import com.lightning.walletapp.ln.wire._
import scala.collection.JavaConverters._
import com.lightning.walletapp.ln.Tools._
import com.lightning.walletapp.ln.Channel._
import com.lightning.walletapp.ln.LNParams._
import com.lightning.walletapp.ln.PaymentInfo._
import com.google.common.util.concurrent.Service.State._
import com.lightning.walletapp.lnutils.ImplicitConversions._
import com.lightning.walletapp.ln.wire.LightningMessageCodecs._
import org.bitcoinj.core.TransactionConfidence.ConfidenceType._

import scala.util.{Success, Try}
import rx.lang.scala.{Observable => Obs}
import scodec.bits.{BitVector, ByteVector}
import org.bitcoinj.wallet.{SendRequest, Wallet}
import androidx.work.{ExistingWorkPolicy, WorkManager}
import android.content.{ClipData, ClipboardManager, Context}
import fr.acinq.bitcoin.Crypto.{Point, PrivateKey, PublicKey}
import com.lightning.walletapp.helper.{AwaitService, RichCursor}
import com.lightning.walletapp.lnutils.JsonHttpUtils.{pickInc, repeat}
import com.lightning.walletapp.lnutils.olympus.{OlympusWrap, TxUploadAct}
import android.app.{Application, NotificationChannel, NotificationManager}
import org.bitcoinj.core.listeners.PeerDisconnectedEventListener
import concurrent.ExecutionContext.Implicits.global
import java.util.concurrent.TimeUnit.MILLISECONDS
import org.bitcoinj.wallet.KeyChain.KeyPurpose
import org.bitcoinj.net.discovery.DnsDiscovery
import org.bitcoinj.wallet.Wallet.BalanceType
import rx.lang.scala.schedulers.IOScheduler
import java.util.Collections.singletonList
import fr.acinq.bitcoin.Protocol.Zeroes
import org.bitcoinj.uri.BitcoinURI
import scala.concurrent.Future
import fr.acinq.bitcoin.Crypto
import android.widget.Toast
import scodec.DecodeResult
import android.os.Build
import java.io.File


class WalletApp extends Application { me =>
  lazy val params = org.bitcoinj.params.MainNetParams.get
  lazy val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
  lazy val walletFile = new File(getFilesDir, walletFileName)
  lazy val chainFile = new File(getFilesDir, chainFileName)
  var olympus: OlympusWrap = _
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

  def toast(code: Int): Unit = toast(me getString code)
  def toast(msg: CharSequence): Unit = Toast.makeText(me, msg, Toast.LENGTH_SHORT).show
  def clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE).asInstanceOf[ClipboardManager]
  def plur1OrZero(opts: Array[String], num: Long) = if (num > 0) plur(opts, num).format(num) else opts(0)
  def getBufferUnsafe = clipboardManager.getPrimaryClip.getItemAt(0).getText.toString
  def notMixedCase(s: String) = s.toLowerCase == s || s.toUpperCase == s

  def isAlive =
    if (null == kit || null == olympus || null == db || null == extendedNodeKey) false
    else kit.state match { case STARTING | RUNNING => true case _ => false }

  Utils.appReference = me
  override def onCreate = wrap(super.onCreate) {
    // These cannot be lazy vals because values may change
    Utils.fiatCode = prefs.getString(AbstractKit.FIAT_TYPE, "usd")
    Utils.denom = Utils denoms prefs.getInt(AbstractKit.DENOM_TYPE, 0)

    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
      val importanceLevel = NotificationManager.IMPORTANCE_DEFAULT
      val srvChan = new NotificationChannel(AwaitService.CHANNEL_ID, "NC", importanceLevel)
      me getSystemService classOf[NotificationManager] createNotificationChannel srvChan
    }
  }

  def setBuffer(bufferText: String, notify: Boolean = true) = {
    val bufferContent = ClipData.newPlainText("wallet", bufferText)
    if (notify) me toast getString(copied_to_clipboard).format(bufferText)
    clipboardManager setPrimaryClip bufferContent
  }

  def sign(data: Bytes, pk: PrivateKey) = Try {
    Crypto encodeSignature Crypto.sign(data, pk)
  } getOrElse ByteVector.empty

  def mkNodeAnnouncement(id: PublicKey, na: NodeAddress, alias: String) =
    NodeAnnouncement(signature = sign(random getBytes 32, randomPrivKey), features = ByteVector.empty,
      timestamp = 0L, nodeId = id, rgbColor = (-128, -128, -128), alias take 16, addresses = na :: Nil)

  object TransData {
    var value: Any = new String
    private[this] val prefixes = PaymentRequest.prefixes.values mkString "|"
    private[this] val lnUrl = s"(?im).*?(lnurl)([0-9]{1,}[a-z0-9]+){1}".r.unanchored
    private[this] val lnPayReq = s"(?im).*?($prefixes)([0-9]{1,}[a-z0-9]+){1}".r.unanchored
    private[this] val shortNodeLink = "([a-fA-F0-9]{66})@([a-zA-Z0-9:\\.\\-_]+)".r.unanchored
    val nodeLink = "([a-fA-F0-9]{66})@([a-zA-Z0-9:\\.\\-_]+):([0-9]+)".r.unanchored

    case object DoNotEraseValue
    type Checker = PartialFunction[Any, Any]
    def checkAndMaybeErase(check: Checker) = check(value) match {
      // Sometimes we need to forward a value between activity switches
      case DoNotEraseValue => Tools log "app.TransData.value retained"
      case _ => value = null
    }

    def bitcoinUri(bitcoinUriLink: String) = {
      val uri = new BitcoinURI(params, bitcoinUriLink)
      require(null != uri.getAddress, "No address detected")
      uri
    }

    def toBitcoinUri(addr: String) = bitcoinUri(s"bitcoin:$addr")
    def recordValue(rawInputText: String) = value = parse(rawInputText)
    def parse(rawInputTextToParse: String) = rawInputTextToParse take 2880 match {
      case bitcoinUriLink if bitcoinUriLink startsWith "bitcoin" => bitcoinUri(bitcoinUriLink)
      case bitcoinUriLink if bitcoinUriLink startsWith "BITCOIN" => bitcoinUri(bitcoinUriLink.toLowerCase)
      case nodeLink(key, host, port) => mkNodeAnnouncement(PublicKey(ByteVector fromValidHex key), NodeAddress.fromParts(host, port.toInt), host)
      case shortNodeLink(key, host) => mkNodeAnnouncement(PublicKey(ByteVector fromValidHex key), NodeAddress.fromParts(host, 9735), host)
      case lnPayReq(prefix, data) => PaymentRequest.read(s"$prefix$data")
      case lnUrl(prefix, data) => LNUrl.fromBech32(s"$prefix$data")
      case _ => toBitcoinUri(rawInputTextToParse)
    }
  }

  abstract class WalletKit extends AbstractKit {
    def currentAddress = wallet currentAddress KeyPurpose.RECEIVE_FUNDS
    def conf0Balance = wallet getBalance BalanceType.ESTIMATED_SPENDABLE // Returns all utxos
    def blockSend(txj: Transaction) = peerGroup.broadcastTransaction(txj, 1).broadcast.get
    def shutDown = none

    def trustedNodeTry = Try(nodeaddress.decode(BitVector fromValidHex wallet.getDescription).require.value)
    def fundingPubScript(some: HasCommitments) = singletonList(some.commitments.commitInput.txOut.publicKeyScript: org.bitcoinj.script.Script)
    def closingPubKeyScripts(cd: ClosingData) = cd.commitTxs.flatMap(_.txOut).map(_.publicKeyScript: org.bitcoinj.script.Script).asJava
    def useCheckPoints(time: Long) = CheckpointManager.checkpoint(params, getAssets open "checkpoints.txt", store, time)

    def sign(unsigned: SendRequest) = {
      // Create a tx ready for broadcast
      wallet finalizeReadyTx unsigned
      unsigned.tx.verify
      unsigned
    }

    def setupAndStartDownload = {
      wallet.allowSpendingUnconfirmedTransactions
      wallet.autosaveToFile(walletFile, 1000, MILLISECONDS, null)
      wallet.addCoinsSentEventListener(ChannelManager.chainEventsListener)
      wallet.addCoinsReceivedEventListener(ChannelManager.chainEventsListener)
      wallet.addTransactionConfidenceEventListener(ChannelManager.chainEventsListener)
      peerGroup.addDisconnectedEventListener(ChannelManager.chainEventsListener)

      Future {
        trustedNodeTry match {
          case Success(nodeAddress) =>
            val isa = NodeAddress.toInetSocketAddress(nodeAddress)
            val trusted = new PeerAddress(params, isa.getAddress, isa.getPort)
            peerGroup.addAddress(trusted)

          case _ =>
            peerGroup addPeerDiscovery new DnsDiscovery(params)
            peerGroup.addAddress(TopNodes.randomPeerAddress)
            peerGroup.setMaxConnections(5)
        }
      }

      peerGroup.setMinRequiredProtocolVersion(70015)
      peerGroup.setDownloadTxDependencies(0)
      peerGroup.setPingIntervalMsec(10000)
      peerGroup.addWallet(wallet)

      for {
        _ <- Obs just null subscribeOn IOScheduler.apply delay 20.seconds
        offlineChan <- ChannelManager.notClosing if offlineChan.state == SLEEPING
        if offlineChan.data.announce.addresses.headOption.forall(_.canBeUpdatedIfOffline)
        // Call findNodes without `retry` wrapper because it gives harmless `Obs.empty` on error
        Vector(ann1 \ _, _*) <- app.olympus findNodes offlineChan.data.announce.nodeId.toString
      } offlineChan process ann1

      ConnectionManager.listeners += ChannelManager.socketEventsListener
      startBlocksDownload(ChannelManager.chainEventsListener)
      // Try to clear act leftovers if no channels are left
      app.olympus tellClouds OlympusWrap.CMDStart
      PaymentInfoWrap.markFailedAndFrozen
      ChannelManager.initConnect
      RatesSaver.subscription
    }
  }
}

object ChannelManager extends Broadcaster {
  val operationalListeners = Set(ChannelManager, bag)
  val CMDLocalShutdown = CMDShutdown(scriptPubKey = None)
  private[this] var initialChainHeight = app.kit.wallet.getLastBlockSeenHeight
  // Blocks download has not started yet and we don't know how many is left
  var currentBlocksLeft = Int.MaxValue

  val socketEventsListener = new ConnectionListener {
    override def onOperational(nodeId: PublicKey, isCompat: Boolean) =
      fromNode(notClosing, nodeId).foreach(_ process CMDOnline)

    override def onMessage(nodeId: PublicKey, msg: LightningMessage) = msg match {
      case update: ChannelUpdate => fromNode(notClosing, nodeId).foreach(_ process update)
      case errAll: Error if errAll.channelId == Zeroes => fromNode(notClosing, nodeId).foreach(_ process errAll)
      case m: ChannelMessage => notClosing.find(_.hasCsOr(_.commitments.channelId, null) == m.channelId).foreach(_ process m)
      case _ =>
    }

    override def onDisconnect(nodeId: PublicKey) = for {
      affectedChans <- Obs just fromNode(notClosing, nodeId) if affectedChans.nonEmpty
      _ = affectedChans.foreach(affectedChan => affectedChan process CMDOffline)
      announce <- Obs just affectedChans.head.data.announce delay 5.seconds
    } ConnectionManager.connectTo(announce, notify = false)
  }

  val chainEventsListener = new TxTracker with BlocksListener with PeerDisconnectedEventListener {
    def onPeerDisconnected(connectedPeer: Peer, numPeers: Int) = if (numPeers < 1) onBlock = oneTimeRun
    def onBlocksDownloaded(p: Peer, b: Block, fb: FilteredBlock, left: Int) = onBlock(left)
    override def onChainDownloadStarted(peer: Peer, left: Int) = onBlock(left)

    override def txConfirmed(txj: Transaction) = for (c <- notClosing) c process CMDConfirmed(txj)
    def onCoinsReceived(w: Wallet, txj: Transaction, a: Coin, b: Coin) = onChainTx(txj)
    def onCoinsSent(w: Wallet, txj: Transaction, a: Coin, b: Coin) = onChainTx(txj)

    var onBlock: Int => Unit = oneTimeRun
    lazy val oneTimeRun: Int => Unit = left => {
      // Set standardRun to be executed on new blocks
      // then set `currentBlocksLeft` less than MaxValue
      onBlock = standardRun
      standardRun(left)

      // Can already send payments at this point
      // because `currentBlocksLeft` is updated
      PaymentInfoWrap.resolvePending
    }

    lazy val standardRun: Int => Unit = left => {
      // LN payment before BTC peers on app start: tried in `oneTimeRun`
      // LN payment before BTC peers on app restart: tried in `oneTimeRun`
      // LN payment after BTC peers on app start: tried immediately since `currentBlocksLeft` < `Int.MacValue`
      // LN payment after BTC peers on app restart: tried immediately since `currentBlocksLeft` < `Int.MacValue`
      // LN payment after BTC peers disconnected: tried in `oneTimeRun` because `onPeerDisconnected` resets `onBlock`

      currentBlocksLeft = left
      if (currentBlocksLeft < 1) {
        // Send this once rescan is done to spare resources
        val cmd = CMDBestHeight(currentHeight, initialChainHeight)
        for (channel <- all) channel process cmd
        initialChainHeight = currentHeight
      }
    }

    def onChainTx(txj: Transaction) = {
      val cmdOnChainSpent = CMDSpent(txj)
      for (c <- all) c process cmdOnChainSpent
      bag.extractPreimage(cmdOnChainSpent.tx)
    }
  }

  // BROADCASTER IMPLEMENTATION

  def perKwSixSat = RatesSaver.rates.feeSix.value / 4
  def perKwThreeSat = RatesSaver.rates.feeThree.value / 4

  def currentHeight: Int =
    // We may still be syncing but anyway a final chain height is known here
    if (currentBlocksLeft == Int.MaxValue) app.kit.wallet.getLastBlockSeenHeight
    else app.kit.wallet.getLastBlockSeenHeight + currentBlocksLeft

  def getTx(txid: ByteVector) = {
    val wrapped = Sha256Hash wrap txid.toArray
    Option(app.kit.wallet getTransaction wrapped)
  }

  def getStatus(txid: ByteVector) = getTx(txid) map { tx =>
    val isTxDead = tx.getConfidence.getConfidenceType == DEAD
    tx.getConfidence.getDepthInBlocks -> isTxDead
  } getOrElse 0 -> false

  // CHANNEL LISTENER IMPLEMENTATION

  override def onProcessSuccess = {
    case (_, close: ClosingData, _: Command) =>
      // Repeatedly spend everything we can in this state in case it was unsuccessful before
      val tier12Publishable = for (state <- close.tier12States if state.isPublishable) yield state.txn
      val toSend = close.mutualClose ++ close.localCommit.map(_.commitTx) ++ tier12Publishable
      for (tx <- toSend) try app.kit blockSend tx catch none

    case (chan, norm: NormalData, _: CMDBestHeight) if norm.commitments.updateOpt.isEmpty =>
      // Depth barrier is relevant for Turbo channels: restrict receiving until confirmed
      val fundingDepth \ isFundingDead = broadcaster.getStatus(chan.fundTxId)

      if (fundingDepth > minDepth && !isFundingDead) for {
        blockHeight \ txIndex <- app.olympus getShortId chan.fundTxId
        shortChannelId <- Tools.toShortIdOpt(blockHeight, txIndex, norm.commitments.commitInput.outPoint.index)
        dummy = Announcements.makeChannelUpdate(chainHash, nodePrivateKey, chan.data.announce.nodeId, shortChannelId)
      } chan process CMDChannelUpdate(dummy)

    case (chan, norm: NormalData, real: ChannelUpdate)
      if norm.commitments.updateOpt.exists(old => old.shortChannelId == real.shortChannelId && old.timestamp < real.timestamp) =>
      // We have an old or dummy ChannelUpdate, replace it with a new one IF it updates parameters and is a more recent one
      chan process CMDChannelUpdate(real)
  }

  override def onBecome = {
    // Repeatedly resend a funding tx, update feerate on becoming open
    case (_, wait: WaitFundingDoneData, _, _) => app.kit blockSend wait.fundingTx
    case (chan, _: NormalData, SLEEPING, OPEN) => chan process CMDFeerate(perKwThreeSat)
  }

  // CHANNEL CREATION AND MANAGEMENT

  val chanBackupWork = BackupWorker.workRequest(backupFileName, cloudSecret)
  // All stored channels which would receive CMDSpent, CMDBestHeight and nothing else
  var all: Vector[Channel] = for (chanState <- ChannelWrap doGet db) yield createChannel(operationalListeners, chanState)
  def backUp = WorkManager.getInstance.beginUniqueWork("Backup", ExistingWorkPolicy.REPLACE, chanBackupWork).enqueue
  def fromNode(of: Vector[Channel], nodeId: PublicKey) = for (c <- of if c.data.announce.nodeId == nodeId) yield c
  def notClosing = for (c <- all if c.state != CLOSING) yield c

  def delayedPublishes = {
    val statuses = all.map(_.data).collect { case cd: ClosingData => cd.bestClosing.getState }
    // Select all ShowDelayed which can't be published yet because cltv/csv delays are not cleared on them
    statuses.flatten.collect { case sd: ShowDelayed if !sd.isPublishable && sd.delay > Long.MinValue => sd }
  }

  // AIR

  def airCanSendInto(targetChan: Channel) = for {
    canSend <- all.filter(isOperational) diff Vector(targetChan) map estimateCanSend
    // While rebalancing, payments from other channels will lose some off-chain fee
    canSendFeeIncluded = canSend - maxAcceptableFee(canSend, hops = 3)
    // Estimation should be smaller than original but not negative
    if canSendFeeIncluded < canSend && canSendFeeIncluded > 0L
  } yield canSendFeeIncluded

  def estimateAIRCanSend = {
    // We are ultimately bound by the useful capacity of the largest channel
    val airCanSend = mostFundedChanOpt.map(chan => estimateCanSend(chan) + airCanSendInto(chan).sum)
    val largestCapOpt = all.filter(isOperational).map(estimateNextUsefulCapacity).reduceOption(_ max _)
    math.min(airCanSend getOrElse 0L, largestCapOpt getOrElse 0L)
  }

  def accumulatorChanOpt(rd: RoutingData) =
    all.filter(chan => isOperational(chan) && channelAndHop(chan).nonEmpty)
      .filter(chan => estimateNextUsefulCapacity(chan) >= rd.withMaxOffChainFeeAdded)
      .sortBy(estimateCanReceive).headOption // The one most likely to be chosen by direct peer

  // CHANNEL

  def mostFundedChanOpt = all.filter(isOperational).sortBy(estimateCanSend).lastOption
  def activeInFlightHashes = all.filter(isOperational).flatMap(inFlightHtlcs).map(_.add.paymentHash)
  def frozenInFlightHashes = all.map(_.data).collect { case cd: ClosingData => cd.frozenPublishedHashes }.flatten
  def initConnect = for (chan <- notClosing) ConnectionManager.connectTo(chan.data.announce, notify = false)
  def attachListener(lst: ChannelListener) = for (chan <- all) chan.listeners += lst
  def detachListener(lst: ChannelListener) = for (chan <- all) chan.listeners -= lst

  def createChannel(initialListeners: Set[ChannelListener], bootstrap: ChannelData): Channel = new Channel { self =>
    def SEND(m: LightningMessage) = for (work <- ConnectionManager.connections get data.announce.nodeId) work.handler process m

    def STORE(data: HasCommitments) = runAnd(data) {
      // Put updated data into db, schedule gdrive upload,
      // replace if upload already is pending, return data
      ChannelWrap put data
      backUp
    }

    def REV(cs: Commitments, rev: RevokeAndAck) = for {
      tx <- cs.remoteCommit.txOpt // We use old commitments to save a punishment for remote commit before it gets dropped
      myBalance = cs.remoteCommit.spec.toRemoteMsat // Local commit is cleared by now, remote still has relevant balance
      watchtowerFee = broadcaster.perKwThreeSat * 3 // Scorched earth policy becuase watchtower can't regenerate tx
      revocationInfo = Helpers.Closing.makeRevocationInfo(cs, tx, rev.perCommitmentSecret, watchtowerFee)
      serialized = LightningMessageCodecs.serialize(revocationInfoCodec encode revocationInfo)
    } db.change(RevokedInfoTable.newSql, tx.txid, cs.channelId, myBalance, serialized)

    def GETREV(cs: Commitments, tx: fr.acinq.bitcoin.Transaction) = {
      val dbCursor = db.select(RevokedInfoTable.selectTxIdSql, tx.txid)
      val rc = RichCursor(dbCursor).headTry(_ string RevokedInfoTable.info)

      for {
        serialized <- rc.toOption
        bitVec = BitVector.fromValidHex(serialized)
        DecodeResult(ri, _) <- revocationInfoCodec.decode(bitVec).toOption
        perCommitmentSecret <- Helpers.Closing.extractCommitmentSecret(cs, tx)
        riWithCurrentFeeRate = ri.copy(feeRate = ri.feeRate max broadcaster.perKwThreeSat)
        ri1 = Helpers.Closing.reMakeRevocationInfo(riWithCurrentFeeRate, cs, tx, perCommitmentSecret)
      } yield Helpers.Closing.claimRevokedRemoteCommitTxOutputs(ri1, tx)
    }

    def CLOSEANDWATCHREVHTLC(cd: ClosingData) = {
      // After publishing a revoked remote commit our peer may further publish Timeout and Success HTLC outputs
      // our job here is to watch every output of every revoked commit tx and re-spend it before their CSV delay runs out
      repeat(app.olympus getChildTxs cd.commitTxs.map(_.txid), pickInc, 7 to 8).foreach(_ map CMDSpent foreach process, none)
      app.kit.wallet.addWatchedScripts(app.kit closingPubKeyScripts cd)
      BECOME(STORE(cd), CLOSING)
    }

    def CLOSEANDWATCH(cd: ClosingData) = {
      val tier12txs = cd.tier12States.map(_.txn.bin).toVector
      if (tier12txs.nonEmpty) app.olympus tellClouds TxUploadAct(txvec.encode(tier12txs).require.toByteVector, Nil, "txs/schedule")
      repeat(app.olympus getChildTxs cd.commitTxs.map(_.txid), pickInc, 7 to 8).foreach(_ foreach bag.extractPreimage, none)
      // Collect all the commit txs publicKeyScripts and watch these scripts locally for future possible payment preimages
      app.kit.wallet.addWatchedScripts(app.kit closingPubKeyScripts cd)
      BECOME(STORE(cd), CLOSING)
    }

    def ASKREFUNDTX(ref: RefundingData) = {
      // Failsafe check in case if we are still in REFUNDING state after app is restarted
      val txsObs = app.olympus getChildTxs Seq(ref.commitments.commitInput.outPoint.txid)
      txsObs.foreach(_ map CMDSpent foreach process, none)
    }

    def ASKREFUNDPEER(some: HasCommitments, point: Point) = {
      val msg = ByteVector.fromValidHex("please publish your local commitment".hex)
      val ref = RefundingData(some.announce, Some(point), some.commitments)
      val error = Error(some.commitments.channelId, msg)

      // Send both invalid reestablish and an error
      app.kit.wallet.addWatchedScripts(app.kit fundingPubScript some)
      BECOME(STORE(ref), REFUNDING) SEND makeReestablish(some, 0L)
      SEND(error)
    }

    // First add listeners, then call
    // doProcess on current thread
    listeners = initialListeners
    doProcess(bootstrap)
  }

  // SENDING PAYMENTS

  def checkIfSendable(rd: RoutingData) = {
    val isFulfilledAlready = bag.getPaymentInfo(rd.pr.paymentHash).filter(_.status == SUCCESS)
    if (isFulfilledAlready.isSuccess) Left(err_ln_fulfilled, NOT_SENDABLE) else mostFundedChanOpt map estimateCanSend match {
      // May happen such that we had enough while were deciding whether to pay, but do not have enough funds now, also check extended options
      case Some(max) if max < rd.firstMsat && rd.airLeft > 1 && estimateAIRCanSend >= rd.firstMsat => Left(dialog_sum_big, SENDABLE_AIR)
      case Some(max) if max < rd.firstMsat => Left(dialog_sum_big, NOT_SENDABLE)
      case None => Left(err_no_data, NOT_SENDABLE)
      case _ => Right(rd)
    }
  }

  def fetchRoutes(rd: RoutingData) = {
    // First we collect chans which in principle can handle a given payment sum right now, then prioritize less busy chans
    val from = all.filter(chan => isOperational(chan) && estimateCanSend(chan) >= rd.firstMsat).map(_.data.announce.nodeId).distinct

    def withHints = for {
      tag <- Obs from rd.pr.routingInfo
      partialRoutes <- getRoutes(tag.route.head.nodeId)
    } yield Obs just partialRoutes.map(_ ++ tag.route)

    def getRoutes(target: PublicKey) =
      if (rd.isReflexive) from diff Vector(target) match {
        case restFrom if restFrom.isEmpty => Obs just Vector(Vector.empty)
        case restFrom if rd.useCache => RouteWrap.findRoutes(restFrom, target, rd)
        case restFrom => BadEntityWrap.findRoutes(restFrom, target, rd)
      } else from contains target match {
        case false if rd.useCache => RouteWrap.findRoutes(from, target, rd)
        case false => BadEntityWrap.findRoutes(from, target, rd)
        case true => Obs just Vector(Vector.empty)
      }

    val paymentRoutesObs =
      if (from.isEmpty) Obs error new LightningException("No sources")
      else if (rd.isReflexive) Obs.zip(withHints).map(_.flatten.toVector)
      else Obs.zip(getRoutes(rd.pr.nodeId) +: withHints).map(_.flatten.toVector)

    for {
      cheapestRoutes <- paymentRoutesObs
      busyMap = Tools.toMap[Channel, PublicKey, Int](all, _.data.announce.nodeId, chan => inFlightHtlcs(chan).size)
      openMap = Tools.toMap[Channel, PublicKey, Int](all, _.data.announce.nodeId, chan => if (chan.state == OPEN) 0 else 1)
      lessBusyRoutes = cheapestRoutes.sortBy(route => if (route.isEmpty) busyMap apply rd.pr.nodeId else busyMap apply route.head.nodeId)
      openRoutes = lessBusyRoutes.sortBy(route => if (route.isEmpty) openMap apply rd.pr.nodeId else openMap apply route.head.nodeId)
    } yield useFirstRoute(openRoutes, rd)
  }

  def sendEither(foeRD: FullOrEmptyRD, noRoutes: RoutingData => Unit): Unit = foeRD match {
    // Find a local channel which is online, can send an amount and belongs to a correct peer
    case Right(rd) if frozenInFlightHashes contains rd.pr.paymentHash =>
    case Right(rd) if activeInFlightHashes contains rd.pr.paymentHash =>
    case Left(emptyRD) => noRoutes(emptyRD)

    case Right(rd) =>
      all filter isOperational find { chan =>
        // Reflexive payment may happen through two chans belonging to the same peer
        // here we must make sure we don't accidently use terminal channel as source one
        val excludeChan = if (rd.usedRoute.isEmpty) 0L else rd.usedRoute.last.shortChannelId
        // Empty used route means we're sending to peer and its nodeId should be our targetId
        val targetNodeId = if (rd.usedRoute.isEmpty) rd.pr.nodeId else rd.usedRoute.head.nodeId
        val isLoop = chan.hasCsOr(_.commitments.updateOpt.exists(_.shortChannelId == excludeChan), false)
        !isLoop && chan.data.announce.nodeId == targetNodeId && estimateCanSend(chan) >= rd.firstMsat
      } match {
        case None => sendEither(useFirstRoute(rd.routes, rd), noRoutes)
        case Some(targetGoodChannel) => targetGoodChannel process rd
      }
  }
}

object Vibrator {
  private[this] var lastVibrated = 0L
  private[this] val vib = app.getSystemService(Context.VIBRATOR_SERVICE).asInstanceOf[android.os.Vibrator]
  def canVibrate = null != vib && vib.hasVibrator && lastVibrated < System.currentTimeMillis - 3000L

  def vibrate = if (canVibrate) {
    vib.vibrate(Array(0L, 85, 200), -1)
    lastVibrated = System.currentTimeMillis
  }
}