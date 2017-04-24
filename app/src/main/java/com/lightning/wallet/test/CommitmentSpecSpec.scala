package com.lightning.wallet.test

import com.lightning.wallet.ln.{CommitmentSpec, Htlc}
import com.lightning.wallet.ln.wire.{UpdateAddHtlc, UpdateFailHtlc, UpdateFulfillHtlc}
import fr.acinq.bitcoin.{BinaryData, Crypto}


class CommitmentSpecSpec {
  def allTests = {

    {
      println("add, fulfill and fail htlcs from the sender side")
      
      val spec = CommitmentSpec(feeratePerKw = 1000, toLocalMsat = 5000 * 1000, toRemoteMsat = 0)
      val R = Crypto.sha256(BinaryData("42" * 32))
      val H = Crypto.sha256(R)

      val add1 = UpdateAddHtlc("00" * 32, 1, 2000 * 1000, 400, H, "")
      val spec1 = CommitmentSpec.reduce(spec, Vector(Htlc(false, add1, null)), Vector.empty)
      println(spec1 == spec.copy(htlcs = Set(Htlc(false, add1, null)), toLocalMsat = 3000 * 1000))

      val add2 = UpdateAddHtlc("00" * 32, 2, 1000 * 1000, 400, H, "")
      val spec2 = CommitmentSpec.reduce(spec1, Vector(Htlc(false, add2, null)), Vector.empty)
      println(spec2 == spec1.copy(htlcs = Set(Htlc(false, add1, null), Htlc(false, add2, null)), toLocalMsat = 2000 * 1000))

      val ful1 = UpdateFulfillHtlc("00" * 32, add1.id, R)
      val spec3 = CommitmentSpec.reduce(spec2, Vector.empty, Vector(ful1))
      println(spec3 == spec2.copy(htlcs = Set(Htlc(false, add2, null)), toRemoteMsat = 2000 * 1000))

      val fail1 = UpdateFailHtlc("00" * 32, add2.id, R)
      val spec4 = CommitmentSpec.reduce(spec3, Vector.empty, Vector(fail1))
      println(spec4 == spec3.copy(htlcs = Set(), toLocalMsat = 3000 * 1000))
    }

    {
      println("add, fulfill and fail htlcs from the receiver side")

      val spec = CommitmentSpec(feeratePerKw = 1000, toLocalMsat = 0, toRemoteMsat = 5000 * 1000)
      val R = Crypto.sha256(BinaryData("42" * 32))
      val H = Crypto.sha256(R)

      val add1 = UpdateAddHtlc("00" * 32, 1, 2000 * 1000, 400, H, "")
      val spec1 = CommitmentSpec.reduce(spec, Vector.empty, Vector(Htlc(true, add1, null)))
      println(spec1 == spec.copy(htlcs = Set(Htlc(true, add1, null)), toRemoteMsat = 3000 * 1000))

      val add2 = UpdateAddHtlc("00" * 32, 2, 1000 * 1000, 400, H, "")
      val spec2 = CommitmentSpec.reduce(spec1, Vector.empty, Vector(Htlc(true, add2, null)))
      println(spec2 == spec1.copy(htlcs = Set(Htlc(true, add1, null), Htlc(true, add2, null)), toRemoteMsat = 2000 * 1000))

      val ful1 = UpdateFulfillHtlc("00" * 32, add1.id, R)
      val spec3 = CommitmentSpec.reduce(spec2, Vector(ful1), Vector.empty)
      println(spec3 == spec2.copy(htlcs = Set(Htlc(true, add2, null)), toLocalMsat = 2000 * 1000))

      val fail1 = UpdateFailHtlc("00" * 32, add2.id, R)
      val spec4 = CommitmentSpec.reduce(spec3, Vector(fail1), Vector.empty)
      println(spec4 == spec3.copy(htlcs = Set(), toRemoteMsat = 3000 * 1000))
    }
    
  }
}
