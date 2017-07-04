package com.lightning.wallet.lncloud

import com.lightning.wallet.ln._
import com.lightning.wallet.Utils._
import com.lightning.wallet.lncloud.ImplicitConversions._
import org.bitcoinj.core.{StoredBlock, Transaction}

import org.bitcoinj.core.listeners.NewBestBlockListener
import com.lightning.wallet.TxTracker


object ChannelManager {
  var all = Vector.empty[Channel]
  val blockchainListener = new TxTracker with NewBestBlockListener {
    override def txConfirmed(tx: Transaction) = for (chan <- operational) chan process CMDSomethingConfirmed(tx)
    override def coinsSent(tx: Transaction) = for (chan <- all) chan process CMDSomethingSpent(tx, isFunding = false)
    override def notifyNewBestBlock(block: StoredBlock) = for (chan <- operational) chan process CMDDepth(block.getHeight)
  }

  app.kit.wallet addCoinsSentEventListener blockchainListener
  app.kit.blockChain addNewBestBlockListener blockchainListener
  app.kit.wallet addTransactionConfidenceEventListener blockchainListener
  def operational = all.filterNot(_.state == Channel.CLOSING)
}