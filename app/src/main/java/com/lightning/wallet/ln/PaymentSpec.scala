package com.lightning.wallet.ln

import com.lightning.wallet.ln.wire._
import com.lightning.wallet.ln.crypto._
import com.lightning.wallet.ln.crypto.Sphinx._
import com.lightning.wallet.ln.wire.LightningMessageCodecs._

import scala.util.{Success, Try}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey, sha256}
import com.lightning.wallet.ln.wire.FailureMessageCodecs.BADONION
import com.lightning.wallet.ln.Tools.random
import scodec.bits.BitVector
import scodec.Attempt


case class Invoice(message: Option[String], nodeId: PublicKey, sum: MilliSatoshi, paymentHash: BinaryData)
case class IncomingPaymentSpec(invoice: Invoice, status: String, stamp: Long, preimage: BinaryData) extends PaymentSpec
case class OutgoingPaymentSpec(invoice: Invoice, status: String, stamp: Long, preimage: Option[BinaryData], expiry: Long,
                               routes: Vector[PaymentRoute], onion: SecretsAndPacket, amountWithFee: Long) extends PaymentSpec

object Invoice {
  def serialize(invoice: Invoice) = {
    val hash = invoice.paymentHash.toString
    val node = invoice.nodeId.toString
    val sum = invoice.sum.amount
    s"$node:$sum:$hash"
  }

  def parse(encoded: String) = {
    val Array(node, sum, hash) = encoded.split(':')
    Invoice(None, PublicKey(node), MilliSatoshi(sum.toLong), hash)
  }
}

trait PaymentSpecBag {
  def addPreimage(preimage: BinaryData): Unit
  def newPreimage: BinaryData = BinaryData(random getBytes 32)
  def getIncomingPaymentSpec(hash: BinaryData): Try[IncomingPaymentSpec]
  def getOutgoingPaymentSpec(hash: BinaryData): Try[OutgoingPaymentSpec]
}

trait PaymentSpec
extends Serializable {
  val invoice: Invoice
  val status: String
  val stamp: Long
}

object PaymentSpec {
  // PaymentSpec states
  val WAIT_HIDDEN = "WaitHidden"
  val WAIT_VISIBLE = "WaitVisible"
  val TEMPORARY_FAIL = "TemporaryFail"
  val PERMANENT_FAIL = "PermanentFail"
  val SUCCESS = "Success"

  // The fee (in msat) that a node should be paid to forward an HTLC of 'amount' millisatoshis
  def nodeFee(baseMsat: Long, proportional: Long, msat: Long): Long = baseMsat + (proportional * msat) / 1000000

  // finalExpiryBlockCount = current count + some delta
  // First amount is larger than final amount since intermediary nodes collect fees
  def buildRoute(finalAmountMsat: Long, finalExpiryBlockCount: Int, hops: PaymentRoute) = {
    val startConditions = (Vector.empty[PerHopPayload], finalAmountMsat, finalExpiryBlockCount)

    (startConditions /: hops.reverse) { case (Tuple3(payloads, msat, expiry), hop) =>
      val feeMsat = nodeFee(hop.lastUpdate.feeBaseMsat, hop.lastUpdate.feeProportionalMillionths, msat)
      val perHopPayload = PerHopPayload(hop.lastUpdate.shortChannelId, msat, expiry) +: payloads
      (perHopPayload, msat + feeMsat, expiry + hop.lastUpdate.cltvExpiryDelta)
    }
  }

  def buildOnion(nodes: PublicKeyVec, payloads: Vector[PerHopPayload], assocData: BinaryData): SecretsAndPacket = {
    require(nodes.size == payloads.size + 1, "Payload count mismatch: there should be one less payload than " + nodes.size)
    val payloadsBin = payloads.map(perHopPayloadCodec.encode).map(serializationResult) :+ BinaryData("00" * PayloadLength)
    makePacket(PrivateKey(random getBytes 32), nodes, payloadsBin.map(_.toArray), assocData)
  }

  def makeOutgoingSpec(rest: Vector[PaymentRoute], inv: Invoice) = rest.headOption map { route =>
    val (onion, amount, expiry) = makeOutgoingParams(route, inv)

    doMakeOutgoingSpec(route, rest.tail, LNParams.broadcaster.currentHeight + LNParams.minExpiryBlocks, inv)
  }

  def doMakeOutgoingSpec(route: PaymentRoute, rest: Vector[PaymentRoute], finalExpiryBlockCount: Int, inv: Invoice) = {
    val (perHopPayloads, amountWithAllFees, firstExpiry) = buildRoute(inv.sum.amount, finalExpiryBlockCount, route drop 1)
    OutgoingPaymentSpec(inv, status = WAIT_VISIBLE, stamp = System.currentTimeMillis, preimage = None, firstExpiry,
      routes = rest, buildOnion(route.map(_.nextNodeId), perHopPayloads, inv.paymentHash), amountWithAllFees)
  }

  def makeOutgoingParams(route: PaymentRoute, inv: Invoice) = {
    val finalExpiryBlockCount = LNParams.broadcaster.currentHeight + LNParams.minExpiryBlocks
    val (perHopPayloads, amountWithAllFees, firstExpiry) = buildRoute(inv.sum.amount, finalExpiryBlockCount, route drop 1)
    (buildOnion(route.map(_.nextNodeId), perHopPayloads, inv.paymentHash), amountWithAllFees, firstExpiry)

    OutgoingPaymentSpec(inv, status = WAIT_VISIBLE, stamp = System.currentTimeMillis, preimage = None, firstExpiry,
      routes = rest, buildOnion(route.map(_.nextNodeId), perHopPayloads, inv.paymentHash), amountWithAllFees)
  }

  private def without(routes: Vector[PaymentRoute], predicate: Hop => Boolean) = routes.filterNot(_ exists predicate)
  private def withoutChannel(routes: Vector[PaymentRoute], chanId: Long) = without(routes, _.lastUpdate.shortChannelId == chanId)
  private def withoutNode(routes: Vector[PaymentRoute], nodeId: BinaryData) = without(routes, _.nodeId == nodeId)

  def reduceRoutes(fail: UpdateFailHtlc, spec: OutgoingPaymentSpec) =
    parseErrorPacket(spec.onion.sharedSecrets, packet = fail.reason) map {
      case ErrorPacket(nodeId, _: Perm) if spec.invoice.nodeId == nodeId =>
        // Permanent error from a final node, nothing we can do here
        Vector.empty

      case ErrorPacket(nodeId, _: Node) =>
        // Look for channels without this node
        withoutNode(spec.routes, nodeId.toBin)

      case ErrorPacket(nodeId, message: Update) =>
        // This node may have other channels so try them out
        withoutChannel(spec.routes, message.update.shortChannelId)

      case _ =>
        // Just try another
        spec.routes drop 1

      // Could not parse, try another
    } getOrElse spec.routes drop 1

  private def failHtlc(sharedSecret: BinaryData, add: UpdateAddHtlc, failure: FailureMessage) =
    CMDFailHtlc(reason = createErrorPacket(sharedSecret, failure), id = add.id)

  def resolveHtlc(nodeSecret: PrivateKey, add: UpdateAddHtlc, bag: PaymentSpecBag) = Try {
    val packet = parsePacket(nodeSecret, associatedData = add.paymentHash, add.onionRoutingPacket)
    val payload = LightningMessageCodecs.perHopPayloadCodec decode BitVector(packet.payload)
    Tuple3(payload, packet.nextPacket, packet.sharedSecret)
  } map {
    case (_, nextPacket, sharedSecret) if nextPacket.isLast =>
      // We are the final recipient of HTLC, the only viable option

      bag getIncomingPaymentSpec add.paymentHash match {
        case Success(spec) if add.amountMsat > spec.invoice.sum.amount * 2 =>
          // GUARD: they have sent too much funds, this is a protective measure
          failHtlc(sharedSecret, add, IncorrectPaymentAmount)

        case Success(spec) if add.amountMsat < spec.invoice.sum.amount =>
          // GUARD: amount is less than what we requested, this won't do
          failHtlc(sharedSecret, add, IncorrectPaymentAmount)

        // We either have a valid spec or we don't
        case Success(inv) => CMDFulfillHtlc(add.id, inv.preimage)
        case _ => failHtlc(sharedSecret, add, UnknownPaymentHash)
      }

    case (Attempt.Successful(_), _, sharedSecret) =>
      // We don't route so can't find the next node
      failHtlc(sharedSecret, add, UnknownNextPeer)

    case (Attempt.Failure(_), _, sharedSecret) =>
      // Payload could not be parsed at all so fail it
      failHtlc(sharedSecret, add, PermanentNodeFailure)

  } getOrElse {
    val hash = sha256(add.onionRoutingPacket)
    CMDFailMalformedHtlc(add.id, hash, BADONION)
  }
}
