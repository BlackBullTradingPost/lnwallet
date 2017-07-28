package com.lightning.wallet.ln

import fr.acinq.bitcoin._
import com.lightning.wallet.lncloud._
import fr.acinq.bitcoin.DeterministicWallet._
import com.lightning.wallet.lncloud.JsonHttpUtils._
import fr.acinq.bitcoin.Crypto.{PrivateKey, sha256}
import rx.lang.scala.schedulers.IOScheduler
import com.lightning.wallet.Utils.app


object LNParams {
  val maxReserveToFundingRatio = 0.05 // %
  val updateFeeMinDiffRatio = 0.25 // %
  val reserveToFundingRatio = 0.01 // %
  val localFeatures = "00"
  val globalFeatures = ""
  val minDepth = 2

  val maxHtlcValue = MilliSatoshi(4000000000L)
  val maxChannelCapacity = MilliSatoshi(16777216000L)
  lazy val publicCloud = new LNCloud("10.0.2.2")
  lazy val broadcaster = LocalBroadcaster
  lazy val bag = PaymentInfoWrap

  var nodePrivateKey: PrivateKey = _
  var cloudPrivateKey: PrivateKey = _
  var extendedNodeKey: ExtendedPrivateKey = _
  var db: CipherOpenHelper = _

  def isSetUp: Boolean = db != null
  def setup(seed: BinaryData): Unit = generate(seed) match { case master =>
    cloudPrivateKey = derivePrivateKey(master, hardened(92) :: hardened(0) :: Nil).privateKey
    extendedNodeKey = derivePrivateKey(master, hardened(46) :: hardened(0) :: Nil)
    db = new CipherOpenHelper(app, 1, Crypto.hash256(seed).toString)
    nodePrivateKey = extendedNodeKey.privateKey
  }

  // LNCLOUD AND PATHFINDER

  def getCloud: LNCloud = PrivateDataSaver.tryGetObject map {
    privateData => new FailoverLNCloud(publicCloud, privateData.url)
  } getOrElse publicCloud

  def getPathfinder(channel: Channel): Pathfinder = PrivateDataSaver.tryGetObject map {
    privateData => new PrivatePathfinder(new FailoverLNCloud(publicCloud, privateData.url), channel) { data = privateData }
  } getOrElse new PublicPathfinder(bag, publicCloud, channel) { data = PublicDataSaver.tryGetObject getOrElse PublicDataSaver.empty }

  // FEE RELATED

  def exceedsReserve(channelReserveSatoshis: Long, fundingSatoshis: Long): Boolean =
    channelReserveSatoshis.toDouble / fundingSatoshis > maxReserveToFundingRatio

  def shouldUpdateFee(commitmentFeeratePerKw: Long, networkFeeratePerKw: Long): Boolean = {
    val feeRatio = (networkFeeratePerKw - commitmentFeeratePerKw) / commitmentFeeratePerKw.toDouble
    networkFeeratePerKw > 0 && Math.abs(feeRatio) > updateFeeMinDiffRatio
  }

  // MISC

  def expiry: Int = broadcaster.currentHeight + 10
  def deriveParamsPrivateKey(index: Long, n: Long): PrivateKey =
    derivePrivateKey(extendedNodeKey, index :: n :: Nil).privateKey

  def makeLocalParams(channelReserveSat: Long, finalScriptPubKey: BinaryData, keyIndex: Long) = {
    val Seq(funding, revocation, payment, delayed, sha) = for (order <- 0 to 4) yield deriveParamsPrivateKey(keyIndex, order)
    LocalParams(chainHash = Block.RegtestGenesisBlock.blockId, dustLimitSatoshis = 542, maxHtlcValueInFlightMsat = Long.MaxValue,
      channelReserveSat, htlcMinimumMsat = 500, toSelfDelay = 144, maxAcceptedHtlcs = 20, fundingPrivKey = funding,
      revocationSecret = revocation, paymentKey = payment, delayedPaymentKey = delayed, finalScriptPubKey,
      shaSeed = sha256(sha.toBin), isFunder = true)
  }
}

trait Broadcaster extends ChannelListener { me =>
  def safeSend(tx: Transaction) = obsOn(me send tx, IOScheduler.apply).onErrorReturn(_.getMessage)
  def convertToBroadcastStatus(txs: Seq[Transaction], parents: ParentTxidToDepth): Seq[BroadcastStatus] = {
    val augmented = for (tx <- txs) yield (tx, parents get tx.txIn.head.outPoint.txid.toString, Scripts csvTimeout tx)

    augmented map {
      // If CSV is zero then whether parent tx is present or not is irrelevant, we look as CLTV
      case (tx, _, 0) if tx.lockTime - currentHeight < 1 => BroadcastStatus(None, publishable = true, tx)
      case (tx, _, 0) => BroadcastStatus(Some(tx.lockTime - currentHeight), publishable = false, tx)
      // If CSV is not zero but parent tx is not published then we wait for parent
      case (tx, None, _) => BroadcastStatus(None, publishable = false, tx)

      case (tx, Some(parentConfs), csv) =>
        // Tx may have both CLTV and CSV so we need to get the max of them both
        val blocksLeft = math.max(csv - parentConfs, tx.lockTime - currentHeight)
        if (blocksLeft < 1) BroadcastStatus(None, publishable = true, tx)
        else BroadcastStatus(Some(blocksLeft), publishable = false, tx)
    }
  }

  def convertToBroadcastStatus(close: ClosingData): Seq[BroadcastStatus] =
    convertToBroadcastStatus(me extractTxs close, getParentsDepth)

  def extractTxs(cd: ClosingData): Seq[Transaction] =
    cd.localCommit.flatMap(extractTxs) ++ cd.remoteCommit.flatMap(extractTxs) ++
      cd.nextRemoteCommit.flatMap(extractTxs) ++ cd.revokedCommits.flatMap(extractTxs)

  private def extractTxs(bag: RemoteCommitPublished): Seq[Transaction] =
    bag.claimMainOutputTx ++ bag.claimHtlcSuccessTxs ++ bag.claimHtlcTimeoutTxs

  private def extractTxs(bag: RevokedCommitPublished): Seq[Transaction] =
    bag.claimMainOutputTx ++ bag.mainPenaltyTx ++ bag.claimHtlcTimeoutTxs ++
      bag.htlcTimeoutTxs ++ bag.htlcPenaltyTxs

  private def extractTxs(bag: LocalCommitPublished): Seq[Transaction] =
    bag.claimMainDelayedOutputTx ++ bag.htlcSuccessTxs ++ bag.htlcTimeoutTxs ++
      bag.claimHtlcSuccessTxs ++ bag.claimHtlcTimeoutTxs

  type ParentTxidToDepth = Map[String, Int]
  def getParentsDepth: ParentTxidToDepth
  def send(tx: Transaction): String
  def feeRatePerKw: Long
  def currentHeight: Int
}