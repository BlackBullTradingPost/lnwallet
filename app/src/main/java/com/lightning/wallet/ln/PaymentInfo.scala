package com.lightning.wallet.ln

import com.lightning.wallet.ln.wire._
import com.lightning.wallet.ln.crypto._
import com.lightning.wallet.ln.PaymentInfo._
import com.lightning.wallet.ln.crypto.Sphinx._
import com.lightning.wallet.ln.wire.FailureMessageCodecs._
import com.lightning.wallet.ln.wire.LightningMessageCodecs._
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, sha256}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import scala.util.{Success, Try}

import com.lightning.wallet.ln.Tools.random
import scodec.bits.BitVector
import scodec.Attempt


trait PaymentInfo {
  def actualStatus: Int
  val preimage: BinaryData
  val request: PaymentRequest
  val chanId: BinaryData
  val status: Int
}

case class IncomingPayment(received: MilliSatoshi, preimage: BinaryData,
                           request: PaymentRequest, chanId: BinaryData,
                           status: Int) extends PaymentInfo {

  def actualStatus = status
}

case class RoutingData(routes: Vector[PaymentRoute], badNodes: Set[PublicKey],
                       badChannels: Set[Long], onion: SecretsAndPacket,
                       amountWithFee: Long, expiry: Long)

case class OutgoingPayment(routing: RoutingData, preimage: BinaryData,
                           request: PaymentRequest, chanId: BinaryData,
                           status: Int) extends PaymentInfo {

  def actualStatus = if (preimage != NOIMAGE) SUCCESS else status
}

trait PaymentInfoBag {
  def updateFailWaiting: Unit
  def updateStatus(status: Int, hash: BinaryData): Unit
  def updatePreimage(update: UpdateFulfillHtlc): Unit
  def updateReceived(add: UpdateAddHtlc): Unit

  def newPreimage: BinaryData = BinaryData(random getBytes 32)
  def getPaymentInfo(hash: BinaryData): Try[PaymentInfo]
  def putPaymentInfo(info: PaymentInfo): Unit
}

object PaymentInfo {
  // Used for unresolved outgoing payment infos
  val NOIMAGE = BinaryData("empty" getBytes "UTF-8")
  val FROMBLACKLISTED = "fromblacklisted"

  final val HIDDEN = 0
  final val WAITING = 1
  final val SUCCESS = 2
  final val FAILURE = 3

  def nodeFee(baseMsat: Long, proportional: Long, msat: Long) =
    baseMsat + (proportional * msat) / 1000000L

  def buildPayloads(finalAmountMsat: Long, finalExpiry: Int, hops: PaymentRoute) = {
    val paymentPayloads = Vector apply PerHopPayload(0L, finalAmountMsat, finalExpiry)
    val startValues = (paymentPayloads, finalAmountMsat, finalExpiry)

    (startValues /: hops.reverse) { case (loads, msat, expiry) \ hop =>
      val nextFee = nodeFee(hop.lastUpdate.feeBaseMsat, hop.lastUpdate.feeProportionalMillionths, msat)
      val perHopPayloads = PerHopPayload(hop.lastUpdate.shortChannelId, msat, expiry) +: loads
      (perHopPayloads, msat + nextFee, expiry + hop.lastUpdate.cltvExpiryDelta)
    }
  }

  def buildOnion(nodes: PublicKeyVec, payloads: Vector[PerHopPayload], assocData: BinaryData): SecretsAndPacket = {
    require(nodes.size == payloads.size, "Payload count mismatch: there should be exactly as much payloads as node pubkeys")
    makePacket(PrivateKey(random getBytes 32), nodes, payloads.map(php => serialize(perHopPayloadCodec encode php).toArray), assocData)
  }

  // Build a new OutgoingPayment given previous RoutingData
  // If we have routes available AND channel has an id AND request has amount
  def buildPayment(rd: RoutingData, pr: PaymentRequest, chan: Channel) = for {

    route <- rd.routes.headOption
    chanId <- chan.pull(_.channelId)
    MilliSatoshi(amount) <- pr.amount

    (payloads, amountWithFee, finalExpiry) = buildPayloads(amount, LNParams.sendExpiry, route)
    onion = buildOnion(chan.data.announce.nodeId +: route.map(_.nextNodeId), payloads, pr.paymentHash)
    rd1 = RoutingData(rd.routes.tail, rd.badNodes, rd.badChannels, onion, amountWithFee, finalExpiry)
  } yield OutgoingPayment(rd1, NOIMAGE, pr, chanId, WAITING)

  private def without(rs: Vector[PaymentRoute], test: Hop => Boolean) = rs.filterNot(_ exists test)
  private def failHtlc(sharedSecret: BinaryData, failure: FailureMessage, add: UpdateAddHtlc) =
    CMDFailHtlc(reason = createErrorPacket(sharedSecret, failure), id = add.id)

  // Additionally reduce remaining routes and remember failed nodes and channels
  def cutRoutes(fail: UpdateFailHtlc, rd: RoutingData, finalNodeId: PublicKey) =

    parseErrorPacket(rd.onion.sharedSecrets, fail.reason) collect {
      case ErrorPacket(nodeKey, _: Perm) if finalNodeId == nodeKey =>
        // Permanent error from a final node, nothing we can do
        rd.copy(routes = Vector.empty)

      case ErrorPacket(nodeKey, _: Node) =>
        // Look for channels left without this node, also remember this node as failed
        rd.copy(routes = without(rd.routes, _.nodeId == nodeKey), badNodes = rd.badNodes + nodeKey)

      case ErrorPacket(_, message: Update) =>
        // Node may have other channels left so try them out, also remember this channel as failed
        val routesWithoutChannel = without(rd.routes, _.lastUpdate.shortChannelId == message.update.shortChannelId)
        rd.copy(routes = routesWithoutChannel, badChannels = rd.badChannels + message.update.shortChannelId)

      // Nothing to cut
    } getOrElse rd

  // After mutually signed HTLCs are present we need to parse and fail/fulfill them
  def resolveHtlc(nodeSecret: PrivateKey, add: UpdateAddHtlc, bag: PaymentInfoBag, minExpiry: Int) = Try {
    val packet = parsePacket(privateKey = nodeSecret, associatedData = add.paymentHash, add.onionRoutingPacket)
    Tuple3(perHopPayloadCodec decode BitVector(packet.payload), packet.nextPacket, packet.sharedSecret)
  } map {
    case (Attempt.Successful(decoded), nextPacket, sharedSecret) if nextPacket.isLast =>
      // We are the final HTLC recipient, the only viable option since we don't route

      bag getPaymentInfo add.paymentHash match {
        case Success(_) if add.expiry < minExpiry =>
          // GUARD: not enough time to redeem it on-chain
          failHtlc(sharedSecret, FinalExpiryTooSoon, add)

        case Success(_) if decoded.value.outgoing_cltv_value != add.expiry =>
          // GUARD: final outgoing CLTV value does not equal the one from message
          failHtlc(sharedSecret, FinalIncorrectCltvExpiry(add.expiry), add)

        case Success(pay) if pay.request.amount.exists(add.amountMsat > _.amount * 2) =>
          // GUARD: they have sent too much funds, this is a protective measure against that
          failHtlc(sharedSecret, IncorrectPaymentAmount, add)

        case Success(pay) if pay.request.amount.exists(add.amountMsat < _.amount) =>
          // GUARD: amount is less than what we requested, we won't accept such payment
          failHtlc(sharedSecret, IncorrectPaymentAmount, add)

        case Success(pay: IncomingPayment) =>
          // We have a valid *incoming* payment
          CMDFulfillHtlc(add.id, pay.preimage)

        case _ =>
          // Payment spec has not been found
          failHtlc(sharedSecret, UnknownPaymentHash, add)
      }

    case (Attempt.Successful(_), _, sharedSecret) =>
      // We don't route so can't find the next node
      failHtlc(sharedSecret, UnknownNextPeer, add)

    case (Attempt.Failure(_), _, sharedSecret) =>
      // Payload could not be parsed at all so fail it
      failHtlc(sharedSecret, PermanentNodeFailure, add)

  } getOrElse {
    val hash = sha256(add.onionRoutingPacket)
    CMDFailMalformedHtlc(add.id, hash, BADONION)
  }
}