package com.lightning.wallet.lncloud

import spray.json._
import com.lightning.wallet.ln._
import collection.JavaConverters._
import org.bitcoinj.wallet.listeners._
import com.lightning.wallet.lncloud.JsonHttpUtils._
import com.lightning.wallet.lncloud.ImplicitJsonFormats._
import com.lightning.wallet.lncloud.ImplicitConversions._

import rx.lang.scala.{Subscription, Observable => Obs}
import fr.acinq.bitcoin.{BinaryData, OutPoint}
import org.bitcoinj.core.{Coin, Transaction}

import com.lightning.wallet.helper.RichCursor
import com.lightning.wallet.Utils.app
import org.bitcoinj.wallet.Wallet
import scala.util.Try


object ChainWatcher {
  def watchTxDepthLocal(watchTxId: String) = Obs[Int] { obs =>
    val listener = new org.bitcoinj.core.listeners.TransactionConfidenceEventListener {
      def onTransactionConfidenceChanged(wallet: org.bitcoinj.wallet.Wallet, tx: Transaction) =
        if (tx.getHashAsString == watchTxId) obs onNext tx.getConfidence.getDepthInBlocks
    }

    app.kit.wallet addTransactionConfidenceEventListener listener
    Subscription(app.kit.wallet removeTransactionConfidenceEventListener listener)
  }

  def watchInputUsedLocal(fundPoint: OutPoint) = Obs[Transaction] { obs =>
    val usesInput = (_: Transaction).getInputs.asScala.map(_.getOutpoint) exists { point =>
      // We are looking for an input which spends exactly our funding tx hash and output index
      point.getIndex == fundPoint.index && point.getHash.toString == fundPoint.hash.toString
    }

    val listener = new WalletCoinsReceivedEventListener with WalletCoinsSentEventListener {
      def onCoinsSent(w: Wallet, tx: Transaction, prev: Coin, now: Coin) = if (usesInput apply tx) obs onNext tx
      def onCoinsReceived(w: Wallet, tx: Transaction, prev: Coin, now: Coin) = if (usesInput apply tx) obs onNext tx
    }

    app.kit.wallet addCoinsSentEventListener listener
    app.kit.wallet addCoinsReceivedEventListener listener

    Subscription {
      app.kit.wallet removeCoinsSentEventListener listener
      app.kit.wallet removeCoinsReceivedEventListener listener
    }
  }
}

object StorageWrap {
  def put(value: String, key: String) = LNParams.db txWrap {
    LNParams.db.change(sql = Storage.newSql, params = value, key)
    LNParams.db.change(sql = Storage.updSql, params = value, key)
  }

  def get(key: String): Try[String] = {
    val cursor = LNParams.db.select(Storage.selectSql, key)
    RichCursor(cursor).headTry(_ string Storage.value)
  }
}

object PaymentSpecWrap extends PaymentSpecBag { me =>
  def updateStatus(status: String, hash: BinaryData) = {
    LNParams.db.change(PaymentSpecs.updStatusSql, status, hash.toString)
    app.getContentResolver.notifyChange(LNParams.db sqlPath PaymentSpecs.table, null)
  }

  private def getRawSpec(hash: BinaryData) = {
    val cursor = LNParams.db.select(PaymentSpecs.selectByHashSql, hash.toString)
    RichCursor(cursor).headTry(_ string PaymentSpecs.data)
  }

  def getIncomingPaymentSpec(hash: BinaryData) = getRawSpec(hash) map to[IncomingPaymentSpec]
  def getOutgoingPaymentSpec(hash: BinaryData) = getRawSpec(hash) map to[OutgoingPaymentSpec]

  def putPaymentSpec(spec: PaymentSpec) = LNParams.db txWrap {
    val paymentHashString: String = spec.invoice.paymentHash.toString
    LNParams.db.change(PaymentSpecs.newSql, spec.toJson.toString, paymentHashString, spec.status, spec.stamp.toString)
    LNParams.db.change(PaymentSpecs.newVirtualSql, s"${spec.invoice.message.orNull} $paymentHashString", paymentHashString)
    app.getContentResolver.notifyChange(LNParams.db sqlPath PaymentSpecs.table, null)
  }

  def replaceOutgoingPaymentSpec(spec: OutgoingPaymentSpec) =
    LNParams.db.change(PaymentSpecs.updSql, spec.toJson.toString,
      spec.status, spec.stamp.toString, spec.invoice.paymentHash.toString)

  def addPreimage(spec: OutgoingPaymentSpec)(preimage: BinaryData): Unit = {
    val spec1 = spec.copy(preimage = Some(preimage), status = PaymentSpec.SUCCESS)
    me replaceOutgoingPaymentSpec spec1
  }
}