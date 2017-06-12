package com.lightning.wallet.lncloud

import spray.json._
import scala.concurrent.duration._
import spray.json.DefaultJsonProtocol._
import com.lightning.wallet.lncloud.JsonHttpUtils._
import com.lightning.wallet.lncloud.ImplicitJsonFormats._

import org.bitcoinj.core.{Coin, Transaction}
import rx.lang.scala.{Scheduler, Observable => Obs}
import com.lightning.wallet.lncloud.RatesSaver.RatesMap
import com.github.kevinsawicki.http.HttpRequest
import com.lightning.wallet.helper.Statistics
import com.lightning.wallet.ln.LNParams
import com.lightning.wallet.ln.~
import spray.json.JsonFormat
import scala.util.Try


object JsonHttpUtils {
  def obsOn[T](provider: => T, scheduler: Scheduler): Obs[T] =
    Obs.just(null).subscribeOn(scheduler).map(_ => provider)

  def retry[T](obs: Obs[T], pick: (Throwable, Int) => Duration, times: Range): Obs[T] =
    obs.retryWhen(_.zipWith(Obs from times)(pick) flatMap Obs.timer)

  def withDelay[T](obs: Obs[T], startMillis: Long, timeoutMillis: Long) = {
    val adjustedTimeout = startMillis + timeoutMillis - System.currentTimeMillis
    val delayLeft = if (adjustedTimeout < 0) 0L else adjustedTimeout
    obs.delay(delayLeft.millis)
  }

  val get = HttpRequest.get(_: String, true) connectTimeout 10000
  def pickInc(err: Throwable, next: Int): FiniteDuration = next.seconds
  def to[T : JsonFormat](raw: String): T = raw.parseJson.convertTo[T]
  def jsonFieldAs[T : JsonFormat](field: String)(raw: String) =
    raw.parseJson.asJsObject.fields(field).convertTo[T]
}

trait Saver {
  val KEY: String
  def tryGet: Try[String] = StorageWrap.get(KEY)
  def save(snap: JsValue) = StorageWrap.put(snap.toString, KEY)
}

object PublicDataSaver extends Saver {
  def tryGetObject: Try[PublicData] = tryGet map to[PublicData]
  def saveObject(data: PublicData): Unit = save(data.toJson)
  def empty = PublicData(info = None, tokens = Nil)
  val KEY = "lnCloudPublic"
}

object PrivateDataSaver extends Saver {
  def tryGetObject: Try[PrivateData] = tryGet map to[PrivateData]
  def saveObject(data: PrivateData): Unit = save(data.toJson)
  def remove = LNParams.db.change(StorageTable.killSql, KEY)
  val KEY = "lnCloudPrivate"
}

object RatesSaver extends Saver {
  type RatesMap = Map[String, Double]
  type BlockNum2Fee = Map[String, Double]
  type Result = (BlockNum2Fee, RatesMap)
  val KEY = "rates1"

  private val updatePeriod: FiniteDuration = 20.minutes
  var rates = tryGet map to[Rates] getOrElse Rates(Nil, Map.empty, 0L)

  def process = {
    def getResult: Obs[Result] = for (rates <- LNParams.cloud.getRates) yield rates.convertTo[Result]
    def periodically = retry(getResult, pickInc, 2 to 6 by 2).repeatWhen(_ delay updatePeriod)
    def delayed = withDelay(periodically, rates.stamp, updatePeriod.toMillis)

    delayed foreach { case newFee ~ newFiat =>
      val updatedFeeHistory = newFee("3") +: rates.feeHistory take 6
      rates = Rates(updatedFeeHistory, newFiat, System.currentTimeMillis)
      save(rates.toJson)
    }
  }
}

case class Rates(feeHistory: Seq[Double], exchange: RatesMap, stamp: Long) {
  lazy val statistics = new Statistics[Double] { def extract(item: Double) = item }
  lazy val cutOutliers = Coin parseCoin statistics.meanWithin(feeHistory, stdDevs = 1)
  lazy val feeLive = if (feeHistory.isEmpty) Transaction.DEFAULT_TX_FEE else cutOutliers
  lazy val feeRisky = feeLive div 2
}