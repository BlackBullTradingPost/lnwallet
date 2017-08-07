package com.lightning.wallet.lncloud

import com.lightning.wallet.ln._
import collection.JavaConverters._
import com.lightning.wallet.ln.Channel._
import com.lightning.wallet.lncloud.ImplicitConversions._

import rx.lang.scala.{Observable => Obs}
import com.lightning.wallet.Utils.app
import fr.acinq.bitcoin.Transaction


object LocalBroadcaster extends Broadcaster {
  def feeRatePerKw = RatesSaver.rates.feeLive.value / 2
  def currentHeight = app.kit.peerGroup.getMostCommonChainHeight
  def send(tx: Transaction) = app.kit.blockingSend(tx).toString

  def getParentsDepth: ParentTxidToDepth = {
    val txs = app.kit.wallet.getTransactions(false).asScala
    for (tx <- txs) yield (tx.getHashAsString, tx.getConfidence.getDepthInBlocks)
  }.toMap

  override def onBecome = {
    case (_, wait: WaitFundingDoneData, _, WAIT_FUNDING_DONE) =>
      // In a thin wallet we need to watch for transactions which spend external outputs
      app.kit.wallet addWatchedScript wait.commitments.commitInput.txOut.publicKeyScript
      safeSend(wait.fundingTx).foreach(Tools.log, _.printStackTrace)

    case (chan, _, SYNC, NORMAL) =>
      // Will be sent once on app lauch
      chan process CMDFeerate(feeRatePerKw)
  }

  override def onProcess = {
    case (_, close: ClosingData, _) =>
      // Mutual closing and local commit should not be present at once but anyway an order matters here
      val toPublish = close.mutualClose ++ close.localCommit.map(_.commitTx) ++ extractTxs(close)
      Obs.from(toPublish map safeSend).concat.foreach(Tools.log, _.printStackTrace)
  }

  override def onError = {
    case chanRelated: Throwable =>
      chanRelated.printStackTrace
  }
}