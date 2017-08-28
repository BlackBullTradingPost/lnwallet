package com.lightning.wallet

import R.string._
import java.text._
import com.lightning.wallet.Denomination._
import com.lightning.wallet.Utils.app
import fr.acinq.bitcoin.MilliSatoshi
import language.implicitConversions
import org.bitcoinj.core.Coin


object Denomination {
  val sat2msatFactor = 1000L
  val bit2msatFactor = 100000L
  val mbtc2msatFactor = 100000000L
  val btc2msatFactor = 100000000000L
  val locale = new java.util.Locale("en", "US")
  val symbols = new DecimalFormatSymbols(locale)
  val formatFiat = new DecimalFormat("#,###,###.##")
  formatFiat setDecimalFormatSymbols symbols

  implicit def mSat2Coin(msat: MilliSatoshi): Coin = Coin.valueOf(msat.amount / sat2msatFactor)
  implicit def coin2MSat(cn: Coin): MilliSatoshi = MilliSatoshi(amount = cn.value * sat2msatFactor)
  def btcBigDecimal2MSat(btc: BigDecimal) = MilliSatoshi(amount = (btc * btc2msatFactor).toLong)
}

trait Denomination {
  def rawString2MSat(raw: String) = MilliSatoshi(amount = (BigDecimal(raw) * factor).toLong)
  def formatted(msat: MilliSatoshi) = fmt format BigDecimal(msat.amount) / factor
  def withSign(msat: MilliSatoshi): String
  val fmt: DecimalFormat
  val factor: Long
  val txt: String
}

object SatDenomination extends Denomination {
  val fmt = new DecimalFormat("###,###,###.###")
  val txt = app getString amount_hint_sat
  val factor = sat2msatFactor

  fmt setDecimalFormatSymbols symbols
  def withSign(msat: MilliSatoshi) =
    "ⓢ\u00A0" + formatted(msat)
}

object BitDenomination extends Denomination {
  val fmt = new DecimalFormat("###,###,###.#####")
  val txt = app getString amount_hint_bit
  val factor = bit2msatFactor

  fmt setDecimalFormatSymbols symbols
  def withSign(msat: MilliSatoshi) =
    formatted(msat) + "\u00A0Bits"
}

object MBtcDenomination extends Denomination {
  val fmt = new DecimalFormat("###,###.########")
  val txt = app getString amount_hint_mbtc
  val factor = mbtc2msatFactor

  fmt setDecimalFormatSymbols symbols
  def withSign(msat: MilliSatoshi) =
    formatted(msat) + "\u00A0mBTC"
}

object BtcDenomination extends Denomination {
  val fmt = new DecimalFormat("###.###########")
  val txt = app getString amount_hint_btc
  val factor = btc2msatFactor

  fmt setDecimalFormatSymbols symbols
  def withSign(msat: MilliSatoshi) =
    "Ƀ\u00A0" + formatted(msat)
}