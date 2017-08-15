package com.lightning.wallet.test

import com.lightning.wallet.ln._
import com.lightning.wallet.ln.Tools._
import com.lightning.wallet.ln.wire._
import com.lightning.wallet.lncloud._
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi}
import fr.acinq.bitcoin.Crypto.PrivateKey

import scala.util.Try


class LNCloudSpec {
  def randomKey = PrivateKey(random getBytes 32)
  val (priv_a, priv_b, priv_c, priv_d, priv_e) = (randomKey, randomKey, randomKey, randomKey, randomKey)
  val (a, b, c, d, e) = (priv_a.publicKey, priv_b.publicKey, priv_c.publicKey, priv_d.publicKey, priv_e.publicKey)
  val sig = Crypto.encodeSignature(Crypto.sign(BinaryData.empty, priv_a)) :+ 1.toByte
  val defaultChannelUpdate = ChannelUpdate(sig, 0, 0, "0000", 0, 42000, 0, 0)
  val channelUpdate_ab = defaultChannelUpdate.copy(shortChannelId = 1, cltvExpiryDelta = 4, feeBaseMsat = 642000, feeProportionalMillionths = 7)
  val channelUpdate_bc = defaultChannelUpdate.copy(shortChannelId = 2, cltvExpiryDelta = 5, feeBaseMsat = 153000, feeProportionalMillionths = 4)
  val channelUpdate_cd = defaultChannelUpdate.copy(shortChannelId = 3, cltvExpiryDelta = 10, feeBaseMsat = 60000, feeProportionalMillionths = 1)
  val channelUpdate_de = defaultChannelUpdate.copy(shortChannelId = 4, cltvExpiryDelta = 7, feeBaseMsat = 766000, feeProportionalMillionths = 10)

  val preimage = BinaryData("9273f6a0a42b82d14c759e3756bd2741d51a0b3ecc5f284dbe222b59ea903942")

  object TestPaymentSpecBag extends PaymentInfoBag {
    def failPending(status: Long, chanId: BinaryData): Unit = ???
    def updateRouting(out: OutgoingPayment): Unit = ???
    def updateStatus(status: Long, hash: BinaryData): Unit = ???
    def updatePreimage(update: UpdateFulfillHtlc): Unit = ???
    def updateReceived(add: UpdateAddHtlc): Unit = ???

    def getPaymentInfo(hash: BinaryData): Try[PaymentInfo] = Try {
      OutgoingPayment(null, preimage, null, MilliSatoshi(0), null, PaymentInfo.SUCCESS)
    }

    def putPaymentInfo(info: PaymentInfo): Unit = ???
  }

  val hops = Vector(
    Hop(a, b, channelUpdate_ab),
    Hop(b, c, channelUpdate_bc),
    Hop(c, d, channelUpdate_cd),
    Hop(d, e, channelUpdate_de))

  val chan = new Channel {

    data = InitData(NodeAnnouncement(null, 1, e, null, null, null, null))

    override def doProcess(change: Any) = change match {
      case SilentAddHtlc(spec) =>
        println(s"Channel mock got a silent spec: $spec")
        process(UpdateFulfillHtlc(null, 1, preimage))

      case _ =>
        println(s"Channel mock got something: $change")
    }

    override def SEND(message: LightningMessage) = ???
    override def STORE(content: HasCommitments) = content
  }


  def allTests = {

  }
}
