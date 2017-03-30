package com.lightning.wallet.ln

import com.lightning.wallet.ln.wire._
import com.lightning.wallet.ln.crypto.Sphinx._
import com.lightning.wallet.ln.wire.LightningMessageCodecs._
import com.lightning.wallet.ln.Tools.{BinaryDataList, random}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{BinaryData, Crypto}
import scodec.bits.BitVector
import scodec.Attempt
import scala.util.Try


object PaymentOps {
  // First amount is larger than final amount since intermediary nodes collect fees
  // The fee (in msat) that a node should be paid to forward an HTLC of 'amount' millisatoshis
  def nodeFee(baseMsat: Long, proportional: Long, msat: Long): Long = baseMsat + (proportional * msat) / 1000000
  def buildCommand(finalAmountMsat: Long, finalExpiryBlockCount: Int, paymentHash: BinaryData, hops: PaymentRoute) = {
    val (perHopPayloads, firstAmountMsat, firstExpiry) = buildRoute(finalAmountMsat, finalExpiryBlockCount, hops drop 1)
    (firstAmountMsat, buildOnion(for (hop <- hops) yield hop.nextNodeId, perHopPayloads, paymentHash), firstExpiry)
  }

  def buildRoute(finalAmountMsat: Long, finalExpiryBlockCount: Int, hops: PaymentRoute) = {
    val startConditions = (Vector.empty[PerHopPayload], finalAmountMsat, finalExpiryBlockCount)

    hops.reverse.foldLeft(startConditions) { case (Tuple3(payloads, msat, expiry), hop) =>
      val feeMsat = nodeFee(hop.lastUpdate.feeBaseMsat, hop.lastUpdate.feeProportionalMillionths, msat)
      (PerHopPayload(msat, expiry) +: payloads, msat + feeMsat, expiry + hop.lastUpdate.cltvExpiryDelta)
    }
  }

  def buildOnion(nodes: BinaryDataList, payloads: Vector[PerHopPayload], assocData: BinaryData): OnionPacket = {
    require(nodes.size == payloads.size + 1, s"Сount mismatch: there should be one less payload than ${nodes.size}")
    val payloadsBin = payloads.map(perHopPayloadCodec.encode).map(serializationResult) :+ BinaryData("00" * 20)
    makePacket(PrivateKey(random getBytes 32), nodes map PublicKey.apply, payloadsBin, assocData)
  }

  def reduceRoutes(fail: UpdateFailHtlc, packet: OnionPacket,
                   ops: PaymentRouteOps): SeqPaymentRoute =

    parseErrorPacket(packet.sharedSecrets, fail.reason) map {
      case ErrorPacket(nodeId, _: Perm) if ops.targetNodeId == nodeId =>
        // Permanent error from target node, nothing we can do here
        Nil

      case ErrorPacket(nodeId, _) if ops.targetNodeId == nodeId =>
        // Target node may have other channels so try to maybe use them
        PaymentRouteOps.withoutFailedChannel(ops)

      case ErrorPacket(nodeId, _: Node) =>
        // Midway node has failed so try to use routes without it
        PaymentRouteOps.withoutFailedNode(ops, nodeId.toBin)

      case ErrorPacket(nodeId, _) =>
        // Just try the other route
        ops.remaining.tail

    } getOrElse Nil

  def failHtlc(nodeSecret: PrivateKey, htlc: Htlc, failure: FailureMessage) = {
    val packet = parsePacket(nodeSecret, htlc.add.paymentHash, htlc.add.onionRoutingPacket)
    CMDFailHtlc(htlc.add.id, createErrorPacket(packet.sharedSecret, failure), commit = true)
  }

  def parseIncomingHtlc(nodeSecret: PrivateKey, add: UpdateAddHtlc) = Try {
    val packet: ParsedPacket = parsePacket(nodeSecret, add.paymentHash, add.onionRoutingPacket)
    val payload = LightningMessageCodecs.perHopPayloadCodec decode BitVector(packet.payload.data)
    (payload, packet.nextAddress, packet.nextPacket, packet.sharedSecret)
  } map {
    case (Attempt.Successful(_), nextNodeAddress, _, sharedSecret) if nextNodeAddress.forall(0.==) =>
      // Looks like we are the final recipient of HTLC, the only viable option since we don't route
      Right(add, sharedSecret)

    case (Attempt.Successful(_), _, _, sharedSecret) =>
      // We don't route so could not resolve downstream node address
      val reason = createErrorPacket(sharedSecret, UnknownNextPeer)
      val fail = CMDFailHtlc(add.id, reason, commit = true)
      Left(fail)

    case (Attempt.Failure(_), _, _, sharedSecret) =>
      val reason = createErrorPacket(sharedSecret, PermanentNodeFailure)
      val fail = CMDFailHtlc(add.id, reason, commit = true)
      // Could not parse payload
      Left(fail)

  } getOrElse {
    val code = FailureMessageCodecs.BADONION
    val hash = Crypto sha256 add.onionRoutingPacket
    val fail = CMDFailMalformedHtlc(add.id, hash, code, commit = true)
    Left(fail)
  }
}
