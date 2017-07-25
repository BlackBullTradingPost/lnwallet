package com.lightning.wallet.lncloud

import spray.json._
import DefaultJsonProtocol._
import com.lightning.wallet.ln._
import com.lightning.wallet.ln.PaymentInfo._
import com.lightning.wallet.lncloud.LNCloud._
import com.lightning.wallet.lncloud.JsonHttpUtils._
import com.lightning.wallet.lncloud.ImplicitConversions._
import com.lightning.wallet.lncloud.ImplicitJsonFormats._
import com.lightning.wallet.ln.wire.LightningMessageCodecs._

import fr.acinq.bitcoin.{BinaryData, Crypto, Transaction}
import org.bitcoinj.core.{ECKey, PeerAddress, PeerGroup}
import rx.lang.scala.{Observable => Obs}

import collection.JavaConverters.mapAsJavaMapConverter
import com.github.kevinsawicki.http.HttpRequest.post
import fr.acinq.eclair.payment.PaymentRequest
import rx.lang.scala.schedulers.IOScheduler
import com.google.common.net.InetAddresses
import com.lightning.wallet.ln.Tools.none
import fr.acinq.bitcoin.Crypto.PublicKey
import com.lightning.wallet.Utils.app
import org.bitcoinj.core.Utils.HEX
import java.net.ProtocolException


// User can set this one instead of public one
case class PrivateData(acts: List[LNCloudAct], url: String)
class PrivatePathfinder(val lnCloud: LNCloud, val channel: Channel)
extends StateMachine[PrivateData] with Pathfinder { me =>

  def doProcess(some: Any) = (data, some) match {
    case (PrivateData(actions, _), act: LNCloudAct) =>
      me stayWith data.copy(acts = act :: actions take 500)
      me doProcess CMDStart

    case (PrivateData(action :: rest, _), CMDStart) =>
      // Private server should use a public key based authorization so we add a signature to params
      val request = action.run(me signedParams action.data, lnCloud).doOnCompleted(me doProcess CMDStart)
      request.foreach(_ => me stayWith data.copy(acts = rest), _.printStackTrace)

    case _ =>
      // Let know if received an unhandled message in some state
      Tools log s"PrivatePathfinder: unhandled $some : $data"
  }

  def signedParams(data: BinaryData): Seq[HttpParam] = {
    val signature = Crypto encodeSignature Crypto.sign(Crypto sha256 data, LNParams.cloudPrivateKey)
    Seq("sig" -> signature.toString, "key" -> LNParams.cloudPrivateKey.publicKey.toString, body -> data.toString)
  }
}

// By default a public pathfinder is used but user may provide their own private pathfinder anytime they want
case class PublicData(info: Option[RequestAndMemo], tokens: List[ClearToken], acts: List[LNCloudAct] = Nil)
class PublicPathfinder(val bag: PaymentInfoBag, val lnCloud: LNCloud, val channel: Channel)
extends StateMachine[PublicData] with Pathfinder { me =>

  // STATE MACHINE

  def doProcess(some: Any) = (data, some) match {
    case PublicData(None, Nil, _) ~ CMDStart => for {
      request ~ blindMemo <- retry(getInfo, pickInc, 2 to 3)
      payment <- retry(makeOutgoingSpecOpt(request), pickInc, 2 to 3)
    } me doProcess Tuple2(payment, blindMemo)

    // This payment request may arrive in some time after an initialization above,
    // hence we state that it can only be accepted if info == None to avoid race condition
    case PublicData(None, tokens, _) ~ Tuple2(payment: OutgoingPayment, memo: BlindMemo) =>
      me stayWith data.copy(info = Some(payment.request, memo), tokens = tokens)
      channel doProcess SilentAddHtlc(payment)

    // No matter what the state is: do it if we have acts and tokens
    case PublicData(_, (blindPoint, clearToken, clearSignature) :: ts, action :: rest) ~ CMDStart =>
      val params = Seq("point" -> blindPoint, "cleartoken" -> clearToken, "clearsig" -> clearSignature, body -> action.data.toString)
      action.run(params, lnCloud).doOnCompleted(me doProcess CMDStart).foreach(_ => me stayWith data.copy(tokens = ts, acts = rest), onError)

      // 'data' may change while request is on so we always copy it
      def onError(serverError: Throwable) = serverError.getMessage match {
        case "tokeninvalid" | "tokenused" => me stayWith data.copy(tokens = ts)
        case _ => me stayWith data
      }

    // Start request while payment is in progress
    // Payment may be finished already so we ask the bag
    case PublicData(Some(invoice ~ memo), _, _) ~ CMDStart =>
      bag.getPaymentInfo(invoice.paymentHash) getOrElse null match {
        case OutgoingPayment(_, _, _, _, SUCCESS) => me resolveSuccess memo
        case OutgoingPayment(_, _, _, _, FAILURE) => resetPayment
        case pending: OutgoingPayment => me stayWith data
        case null => resetPayment
      }

    case (_, action: LNCloudAct) =>
      // We must always record incoming actions
      val actions1 = action :: data.acts take 500
      me stayWith data.copy(acts = actions1)
      me doProcess CMDStart

    case _ =>
      // Let know if received an unhandled message in some state
      Tools log s"LNCloudPublic: unhandled $some : $data"
  }

  // ADDING NEW TOKENS

  def resetPayment = me stayWith data.copy(info = None)
  def sumIsAppropriate(req: PaymentRequest): Boolean = req.amount.exists(_.amount < 25000000L)
  def resolveSuccess(memo: BlindMemo) = getClearTokens(memo).doOnCompleted(me doProcess CMDStart)
    .foreach(plus => me stayWith data.copy(info = None, tokens = plus ::: data.tokens),
      serverError => if (serverError.getMessage == "notfound") resetPayment)

  // TALKING TO SERVER

  def getInfo = lnCloud.call("blindtokens/info", identity) flatMap { raw =>
    val JsString(pubKeyQ) +: JsString(pubKeyR) +: JsNumber(qty) +: _ = raw
    val signerSessionPubKey = ECKey.fromPublicOnly(HEX decode pubKeyR)
    val signerMasterPubKey = ECKey.fromPublicOnly(HEX decode pubKeyQ)

    // Prepare a list of BlindParam and a list of BigInteger clear tokens for each BlindParam
    val blinder = new ECBlind(signerMasterPubKey.getPubKeyPoint, signerSessionPubKey.getPubKeyPoint)
    val memo = BlindMemo(blinder params qty.toInt, blinder tokens qty.toInt, signerSessionPubKey.getPublicKeyAsHex)

    // Get a request -> memo tuple from server
    lnCloud.call("blindtokens/buy", PaymentRequestFmt read _.head, "seskey" -> memo.sesPubKeyHex,
      "tokens" -> memo.makeBlindTokens.toJson.toString.hex).filter(sumIsAppropriate).map(_ -> memo)
  }

  def getClearTokens(memo: BlindMemo) =
    lnCloud.call("blindtokens/redeem", _.map(json2String(_).bigInteger),
      "seskey" -> memo.sesPubKeyHex).map(memo.makeClearSigs).map(memo.pack)
}

trait Pathfinder {
  val lnCloud: LNCloud
  val channel: Channel

  // This may fail for multiple reasons like network connectivity or channel state, so we should be prepared for that
  def makeOutgoingSpecOpt(request: PaymentRequest) = lnCloud.findRoutes(channel.data.announce.nodeId, request.nodeId) map { routes =>
    val (payloads, amountWithAllFees, expiryWithAllDeltas) = PaymentInfo.buildRoute(request.amount.get.amount, LNParams.expiry, routes.head)
    val onion = PaymentInfo.buildOnion(PublicKey(channel.data.announce.nodeId) +: routes.head.map(_.nextNodeId), payloads, request.paymentHash)
    OutgoingPayment(RoutingData(routes.tail, onion, amountWithAllFees, expiryWithAllDeltas), NOIMAGE, request, channel.id.get, TEMP)
  }
}

// Concrete cloud acts

case class CheckCloudAct(data: BinaryData, kind: String = "CheckCloudAct") extends LNCloudAct {
  def run(params: Seq[HttpParam], cloud: LNCloud): Obs[Unit] = cloud.call("check", println, params:_*)
}

trait LNCloudAct {
  // Sending data to server using either token or sig auth
  def run(params: Seq[HttpParam], cloud: LNCloud): Obs[Unit]
  val data: BinaryData
}

// This is a basic interface to cloud which does not require a channel
// failover invariant will fall back to default in case of failure

class LNCloud(url: String) {
  def addBitcoinNode(pr: PeerGroup): Unit = none
  def http(way: String) = post(s"$url:9001/v1/$way", true) connectTimeout 7500
  def call[T](command: String, process: Vector[JsValue] => T, params: HttpParam*) =
    obsOn(http(command).form(params.toMap.asJava).body.parseJson, IOScheduler.apply) map {
      case JsArray(JsString("error") +: JsString(why) +: _) => throw new ProtocolException(why)
      case JsArray(JsString("ok") +: responses) => process(responses)
      case err => throw new ProtocolException
    }

  def getRates = call("rates", identity)
  def getTxs(parent: String) = call("txs", toVec[Transaction], "txid" -> parent)
  def findNodes(query: String) = call("router/nodes", toVec[AnnounceChansNum], "query" -> query)
  def findRoutes(from: PublicKey, to: PublicKey) = call("router/routes", toVec[PaymentRoute],
    "from" -> from.toString, "to" -> to.toString)
}

class FailoverLNCloud(failover: LNCloud, url: String) extends LNCloud(url) {
  override def addBitcoinNode(pr: PeerGroup) = pr addAddress new PeerAddress(app.params, InetAddresses forString url, 8333)
  override def findNodes(query: String) = super.findNodes(query).onErrorResumeNext(_ => failover findNodes query)
  override def getTxs(parent: String) = super.getTxs(parent).onErrorResumeNext(_ => failover getTxs parent)
  override def getRates = super.getRates.onErrorResumeNext(_ => failover.getRates)
}

object LNCloud {
  type HttpParam = (String, Object)
  type ClearToken = (String, String, String)
  type RequestAndMemo = (PaymentRequest, BlindMemo)
  val CMDStart = "CMDStart"
  val body = "body"
}