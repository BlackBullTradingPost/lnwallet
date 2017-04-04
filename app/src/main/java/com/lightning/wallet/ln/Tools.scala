package com.lightning.wallet.ln

import com.lightning.wallet.ln.Tools._
import com.lightning.wallet.ln.Exceptions._
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import java.text.{DecimalFormat, DecimalFormatSymbols}
import org.bitcoinj.crypto.{ChildNumber, HDKeyDerivation}
import com.lightning.wallet.ln.crypto.RandomGenerator
import org.bitcoinj.wallet.DeterministicSeed
import language.implicitConversions
import org.bitcoinj.core.Coin
import wire.LightningMessage
import java.util.Locale


object Tools {
  type Bytes = Array[Byte]
  type BinaryDataList = List[BinaryData]
  type LightningMessages = Vector[LightningMessage]
  def none: PartialFunction[Any, Unit] = { case _ => }

  val random = new RandomGenerator

  // Second 0H means "Bitcoin" according to BIP44
  // Deriving /M/nH/0H/<arbitrary depth> deterministic keys
  def derive(way: List[ChildNumber], n: Int)(seed: DeterministicSeed) = {
    val masterKey = HDKeyDerivation createMasterPrivateKey seed.getSeedBytes
    val purposeBitcoin = List(new ChildNumber(n, true), ChildNumber.ZERO_HARDENED)
    (purposeBitcoin ::: way).foldLeft(masterKey)(HDKeyDerivation.deriveChildKey)
  }

  def fromShortId(id: Long): (Int, Int, Int) = {
    val blockNumber = id.>>(40).&(0xFFFFFF).toInt
    val txOrd = id.>>(16).&(0xFFFFFF).toInt
    val outOrd = id.&(0xFFFF).toInt
    (blockNumber, txOrd, outOrd)
  }

  def toShortId(blockHeight: Int, txIndex: Int, outputIndex: Int): Long =
    blockHeight.&(0xFFFFFFL).<<(40) | txIndex.&(0xFFFFFFL).<<(16) | outputIndex.&(0xFFFFL)

  def toLongId(fundingTxHash: BinaryData, fundingOutputIndex: Int): BinaryData = {
    if (fundingOutputIndex >= 65536) throw new RuntimeException(LONG_ID_INDEX_TOO_BIG)
    if (fundingTxHash.size != 32) throw new RuntimeException(LONG_ID_HASH_WRONG_SIZE)

    val longChannelId = fundingTxHash.take(30) :+
      fundingTxHash.data(30).^(fundingOutputIndex >> 8).toByte :+
      fundingTxHash.data(31).^(fundingOutputIndex).toByte

    longChannelId
  }
}

object MSat {
  val satFactor = 1000L
  val btcFactor = 100000000000L
  val locale = new Locale("en", "US")
  val baseFiat = new DecimalFormat("#,###,###.##")
  val baseSat = new DecimalFormat("###,###,###.###")
  val symbols = new DecimalFormatSymbols(locale)
  baseFiat setDecimalFormatSymbols symbols
  baseSat setDecimalFormatSymbols symbols

  def btcBigDecimal2MilliSatoshi(btc: BigDecimal): MilliSatoshi = MilliSatoshi(amount = (btc * btcFactor).toLong)
  def satString2MilliSatoshi(sat: String): MilliSatoshi = MilliSatoshi(amount = (BigDecimal(sat) * satFactor).toLong)
  implicit def milliSatoshi2String(msat: MilliSatoshi): String = baseSat.format(BigDecimal(msat.amount) / satFactor)
  implicit def milliSatoshi2Coin(msat: MilliSatoshi): Coin = Coin.valueOf(msat.amount / satFactor)
  implicit def coin2MilliSatoshi(coin: Coin): MilliSatoshi = MilliSatoshi(coin.value * satFactor)
  implicit def coin2String(coin: Coin): String = baseSat format coin.value
  def withSign(sum: String) = s"ⓢ $sum"
}

object Features {
  val CHANNELS_PUBLIC_BIT = 0
  val INITIAL_ROUTING_SYNC_BIT = 2

  def isSet(features: BinaryData, bitIndex: Int): Boolean =
    java.util.BitSet.valueOf(features.reverse.toArray).get(bitIndex)
}

abstract class StateMachine[T](var state: List[String], var data: T) { me =>
  def process(change: Any) = me synchronized doProcess(change)
  def doProcess(change: Any)

  def become(data1: T, state1: String) = {
    println(s"Becoming $state1 with $data1")
    state = state1 :: state take 2
    data = data1
  }

  def stayWith(data1: T, before: => Unit = none) = {
    // Run arbitrary function and then update data only

    before
    become(data1, state.head)
  }

  def becomeReact(data1: T, state1: String, change: Any, before: => Unit = none) = {
    // Run arbitrary function and then update data, state and process the change right away

    before
    become(data1, state1)
    process(change)
  }
}