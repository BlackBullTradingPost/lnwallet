package com.lightning.wallet.ln

import fr.acinq.bitcoin._
import com.lightning.wallet.ln.wire._
import com.lightning.wallet.ln.Scripts._
import com.lightning.wallet.ln.crypto.ShaChain._

import scala.util.{Success, Try}
import fr.acinq.bitcoin.Crypto.{Point, PublicKey, Scalar}
import com.lightning.wallet.ln.crypto.Generators
import com.lightning.wallet.ln.MSat.satFactor


object Helpers { me =>
  private val toUpdateFulfillHtlc = UpdateFulfillHtlc(BinaryData.empty, 0L, _)
  def extractPreimages(tx: Transaction) = tx.txIn.map(_.witness.stack) collect {
    case Seq(BinaryData.empty, _, _, BinaryData.empty, script) => toUpdateFulfillHtlc(script.slice(109, 109 + 20): BinaryData)
    case Seq(_, BinaryData.empty, script) => toUpdateFulfillHtlc(script.slice(69, 69 + 20): BinaryData) // claim-htlc-timeout
  }

  def makeLocalTxs(commitTxNumber: Long, localParams: LocalParams,
                   remoteParams: AcceptChannel, commitmentInput: InputInfo,
                   localPerCommitmentPoint: Point, spec: CommitmentSpec) = {

    val remotePubkey = Generators.derivePubKey(remoteParams.paymentBasepoint, localPerCommitmentPoint)
    val localPubkey = Generators.derivePubKey(localParams.paymentKey.toPoint, localPerCommitmentPoint)
    val localDelayedPubkey = Generators.derivePubKey(localParams.delayedPaymentKey.toPoint, localPerCommitmentPoint)
    val localRevocationPubkey = Generators.revocationPubKey(remoteParams.revocationBasepoint, localPerCommitmentPoint)

    val commitTx = Scripts.makeCommitTx(commitmentInput, commitTxNumber, localParams.paymentKey.toPoint,
      remoteParams.paymentBasepoint, localParams.isFunder, Satoshi(localParams.dustLimitSatoshis), localPubkey,
      localRevocationPubkey, remoteParams.toSelfDelay, localDelayedPubkey, remotePubkey, spec)

    val (htlcTimeoutTxs, htlcSuccessTxs) = Scripts.makeHtlcTxs(commitTx.tx, Satoshi(localParams.dustLimitSatoshis),
      localRevocationPubkey, remoteParams.toSelfDelay, localPubkey, localDelayedPubkey, remotePubkey, spec)

    (commitTx, htlcTimeoutTxs, htlcSuccessTxs)
  }

  def makeRemoteTxs(commitTxNumber: Long, localParams: LocalParams,
                    remoteParams: AcceptChannel, commitmentInput: InputInfo,
                    remotePerCommitmentPoint: Point, spec: CommitmentSpec) = {

    val localPubkey = Generators.derivePubKey(localParams.paymentKey.toPoint, remotePerCommitmentPoint)
    val remotePubkey = Generators.derivePubKey(remoteParams.paymentBasepoint, remotePerCommitmentPoint)
    val remoteDelayedPubkey = Generators.derivePubKey(remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
    val remoteRevocationPubkey = Generators.revocationPubKey(localParams.revocationSecret.toPoint, remotePerCommitmentPoint)
    val commitTx = Scripts.makeCommitTx(commitmentInput, commitTxNumber, remoteParams.paymentBasepoint, localParams.paymentKey.toPoint,
      !localParams.isFunder, Satoshi(remoteParams.dustLimitSatoshis), remotePubkey, remoteRevocationPubkey, localParams.toSelfDelay,
      remoteDelayedPubkey, localPubkey, spec)

    val (htlcTimeoutTxs, htlcSuccessTxs) = Scripts.makeHtlcTxs(commitTx.tx, Satoshi(localParams.dustLimitSatoshis),
      remoteRevocationPubkey, localParams.toSelfDelay, remotePubkey, remoteDelayedPubkey, localPubkey, spec)

    (commitTx, htlcTimeoutTxs, htlcSuccessTxs, remotePubkey, remoteRevocationPubkey)
  }

  object Closing {
    def isValidFinalScriptPubkey(scriptPubKey: BinaryData) = Try(Script parse scriptPubKey) match {
      case Success(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pkh, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) => pkh.data.size == 20
      case Success(OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil) => scriptHash.data.size == 20
      case Success(OP_0 :: OP_PUSHDATA(pubkeyHash, _) :: Nil) if pubkeyHash.length == 20 => true
      case Success(OP_0 :: OP_PUSHDATA(scriptHash, _) :: Nil) if scriptHash.length == 32 => true
      case _ => false
    }

    def makeFirstClosing(commitments: Commitments, localScriptPubkey: BinaryData,
                         remoteScriptPubkey: BinaryData, rate: Long): ClosingSigned = {

      // This is just to estimate the weight, it depends on size of the pubkey scripts
      val dummy: ClosingTx = Scripts.addSigs(makeFunderClosingTx(commitments.commitInput, localScriptPubkey,
        remoteScriptPubkey, dustLimit = Satoshi(0), closingFee = Satoshi(0), spec = commitments.localCommit.spec),
        commitments.localParams.fundingPrivKey.publicKey, commitments.remoteParams.fundingPubkey, "aa" * 71, "bb" * 71)

      val closingWeight = Transaction.weight(dummy.tx)
      val closingFee = Scripts.weight2fee(feeratePerKw = rate, closingWeight)
      val (_, msg) = makeClosing(commitments, localScriptPubkey, remoteScriptPubkey, closingFee)
      msg
    }

    def makeClosing(commitments: Commitments, localScriptPubkey: BinaryData,
                    remoteScriptPubkey: BinaryData, closingFee: Satoshi) = {

      require(isValidFinalScriptPubkey(localScriptPubkey), "Invalid localScriptPubkey")
      require(isValidFinalScriptPubkey(remoteScriptPubkey), "Invalid remoteScriptPubkey")
      val dustLimitSat = math.max(commitments.localParams.dustLimitSatoshis,
        commitments.remoteParams.dustLimitSatoshis)

      val closingTx = makeFunderClosingTx(commitments.commitInput, localScriptPubkey,
        remoteScriptPubkey, Satoshi(dustLimitSat), closingFee, commitments.localCommit.spec)

      val localClosingSig = Scripts.sign(closingTx, commitments.localParams.fundingPrivKey)
      val closingSigned = ClosingSigned(commitments.channelId, closingFee.amount, localClosingSig)
      (closingTx, closingSigned)
    }

    def makeFunderClosingTx(commitTxInput: InputInfo, localScriptPubKey: BinaryData, remoteScriptPubKey: BinaryData,
                            dustLimit: Satoshi, closingFee: Satoshi, spec: CommitmentSpec): ClosingTx = {

      require(spec.htlcs.isEmpty, "No HTLCs allowed")
      val toRemoteAmount = Satoshi(spec.toRemoteMsat / satFactor)
      val toLocalAmount = Satoshi(spec.toLocalMsat / satFactor) - closingFee
      val toLocalOutput = if (toLocalAmount >= dustLimit) TxOut(toLocalAmount, localScriptPubKey) :: Nil else Nil
      val toRemoteOutput = if (toRemoteAmount >= dustLimit) TxOut(toRemoteAmount, remoteScriptPubKey) :: Nil else Nil
      val input = TxIn(commitTxInput.outPoint, Array.emptyByteArray, sequence = 0xffffffffL) :: Nil
      val tx = Transaction(version = 2, input, toLocalOutput ++ toRemoteOutput, lockTime = 0)
      ClosingTx(commitTxInput, LexicographicalOrdering sort tx)
    }

    def checkClosingSignature(commitments: Commitments, localScriptPubkey: BinaryData, remoteScriptPubkey: BinaryData,
                              remoteClosingFee: Satoshi, remoteClosingSig: BinaryData): Option[Transaction] = {

      val (closingTx, closingSigned) = makeClosing(commitments, localScriptPubkey, remoteScriptPubkey, remoteClosingFee)
      Scripts checkSpendable Scripts.addSigs(closingTx, commitments.localParams.fundingPrivKey.publicKey,
        commitments.remoteParams.fundingPubkey, closingSigned.signature, remoteClosingSig)
    }

    def nextClosingFee(localClosingFee: Satoshi, remoteClosingFee: Satoshi): Satoshi =
      (localClosingFee + remoteClosingFee) / 4 * 2

    def claimCurrentLocalCommitTxOutputs(commitments: Commitments, tx: Transaction, bag: PaymentInfoBag) = {
      val localPerCommitmentPoint = Generators.perCommitPoint(commitments.localParams.shaSeed, commitments.localCommit.index.toInt)
      val localRevocationPubkey = Generators.revocationPubKey(commitments.remoteParams.revocationBasepoint, localPerCommitmentPoint)
      val localDelayedPrivkey = Generators.derivePrivKey(commitments.localParams.delayedPaymentKey, localPerCommitmentPoint)
      val localTxs = commitments.localCommit.htlcTxsAndSigs

      def makeClaimDelayedOutput(tx: Transaction): ClaimDelayedOutputTx = {
        val claimDelayed = Scripts.makeClaimDelayedOutputTx(tx, localRevocationPubkey, commitments.localParams.toSelfDelay,
          localDelayedPrivkey.publicKey, commitments.localParams.defaultFinalScriptPubKey, LNParams.broadcaster.feeRatePerKw)

        val sig = Scripts.sign(claimDelayed, localDelayedPrivkey)
        Scripts.addSigs(claimDelayed, sig)
      }

      val allSuccessTxs = for {
        HtlcTxAndSigs(info: HtlcSuccessTx, localSig, remoteSig) <- localTxs
        IncomingPayment(preimage, _, _, _) <- bag.getPaymentInfo(info.paymentHash).toOption
        success <- Scripts checkSpendable Scripts.addSigs(info, localSig, remoteSig, preimage)
        successDelayedClaim <- Scripts checkSpendable makeClaimDelayedOutput(success)
      } yield success -> successDelayedClaim

      val allTimeoutTxs = for {
        HtlcTxAndSigs(info: HtlcTimeoutTx, localSig, remoteSig) <- localTxs
        timeout <- Scripts checkSpendable Scripts.addSigs(info, localSig, remoteSig)
        timeoutDelayedClaim <- Scripts checkSpendable makeClaimDelayedOutput(timeout)
      } yield timeout -> timeoutDelayedClaim

      val (successTxs, claimSuccessTxs) = allSuccessTxs.unzip
      val (timeoutTxs, claimTimeoutTxs) = allTimeoutTxs.unzip

      val claimMainDelayedTx = Scripts checkSpendable makeClaimDelayedOutput(tx)
      LocalCommitPublished(claimMainDelayedTx.toList, successTxs, timeoutTxs,
        claimSuccessTxs, claimTimeoutTxs, commitTx = tx)
    }

    def claimRemoteCommitTxOutputs(commitments: Commitments, remoteCommit: RemoteCommit,
                                   bag: PaymentInfoBag): RemoteCommitPublished = {

      val localPrivkey = Generators.derivePrivKey(commitments.localParams.paymentKey, remoteCommit.remotePerCommitmentPoint)
      val (remoteCommitTx, _, _, remotePubkey, remoteRevPubkey) = makeRemoteTxs(remoteCommit.index, commitments.localParams,
        commitments.remoteParams, commitments.commitInput, remoteCommit.remotePerCommitmentPoint, remoteCommit.spec)

      def signedSuccess(add: UpdateAddHtlc, preimage: BinaryData) = {
        val info = Scripts.makeClaimHtlcSuccessTx(remoteCommitTx.tx, localPrivkey.publicKey, remotePubkey,
          remoteRevPubkey, commitments.localParams.defaultFinalScriptPubKey, add, LNParams.broadcaster.feeRatePerKw)

        val sig = Scripts.sign(info, localPrivkey)
        Scripts.addSigs(info, sig, preimage)
      }

      def signedTimeout(add: UpdateAddHtlc) = {
        val info = Scripts.makeClaimHtlcTimeoutTx(remoteCommitTx.tx, localPrivkey.publicKey, remotePubkey,
          remoteRevPubkey, commitments.localParams.defaultFinalScriptPubKey, add, LNParams.broadcaster.feeRatePerKw)

        val sig = Scripts.sign(info, localPrivkey)
        Scripts.addSigs(info, sig)
      }

      val claimSuccessTxs = for {
        Htlc(false, add) <- remoteCommit.spec.htlcs
        IncomingPayment(preimage, _, _, _) <- bag.getPaymentInfo(add.paymentHash).toOption
        claimSuccess <- Scripts checkSpendable signedSuccess(add, preimage)
      } yield claimSuccess

      val claimTimeoutTxs = for {
        Htlc(true, add) <- remoteCommit.spec.htlcs
        claimTimeout <- Scripts checkSpendable signedTimeout(add)
      } yield claimTimeout

      val claimMainTx = Scripts checkSpendable {
        val info = Scripts.makeClaimP2WPKHOutputTx(remoteCommitTx.tx, localPrivkey.publicKey,
          commitments.localParams.defaultFinalScriptPubKey, LNParams.broadcaster.feeRatePerKw)

        val sig = Scripts.sign(info, localPrivkey)
        Scripts.addSigs(info, localPrivkey.publicKey, sig)
      }

      RemoteCommitPublished(claimMainTx.toList,
        claimHtlcSuccessTxs = claimSuccessTxs.toList,
        claimHtlcTimeoutTxs = claimTimeoutTxs.toList,
        commitTx = remoteCommitTx.tx)
    }

    def claimRevokedRemoteCommitTxOutputs(commitments: Commitments, tx: Transaction) = {
      val txNumber = Scripts.obscuredCommitTxNumber(number = Scripts.decodeTxNumber(tx.txIn.head.sequence, tx.lockTime),
        !commitments.localParams.isFunder, commitments.remoteParams.paymentBasepoint, commitments.localParams.paymentKey.toPoint)

      val index = moves(largestTxIndex - txNumber)
      val hashes = commitments.remotePerCommitmentSecrets.hashes

      getHash(hashes, index) map { remotePerCommitmentSecret =>
        val remotePerCommitmentSecretScalar = Scalar(remotePerCommitmentSecret)
        val remotePerCommitmentPoint = remotePerCommitmentSecretScalar.toPoint

        val remoteDelayedPubkey = Generators.derivePubKey(commitments.remoteParams.delayedPaymentBasepoint, remotePerCommitmentPoint)
        val remoteRevocationPrivkey = Generators.revocationPrivKey(commitments.localParams.revocationSecret, remotePerCommitmentSecretScalar)
        val localPrivkey = Generators.derivePrivKey(commitments.localParams.paymentKey, remotePerCommitmentPoint)

        val claimMainTx = Scripts checkSpendable {
          val claimMain = Scripts.makeClaimP2WPKHOutputTx(tx, localPrivkey.publicKey,
            commitments.localParams.defaultFinalScriptPubKey, LNParams.broadcaster.feeRatePerKw)

          val sig = Scripts.sign(claimMain, localPrivkey)
          Scripts.addSigs(claimMain, localPrivkey.publicKey, sig)
        }

        val claimPenaltyTx = Scripts checkSpendable {
          val txinfo = Scripts.makeMainPenaltyTx(tx, remoteRevocationPrivkey.publicKey,
            commitments.localParams.defaultFinalScriptPubKey, commitments.remoteParams.toSelfDelay,
            remoteDelayedPubkey, LNParams.broadcaster.feeRatePerKw)

          val sig = Scripts.sign(txinfo, remoteRevocationPrivkey)
          Scripts.addSigs(txinfo, sig)
        }

        RevokedCommitPublished(claimMainTx.toList, claimPenaltyTx.toList,
          claimHtlcTimeoutTxs = List.empty, htlcTimeoutTxs = List.empty,
          htlcPenaltyTxs = List.empty, commitTx = tx)
      }
    }
  }

  object Funding {
    def makeFundingInputInfo(fundingTxHash: BinaryData, fundingTxOutputIndex: Int,
                             fundingSatoshis: Satoshi, fundingPubkey1: PublicKey,
                             fundingPubkey2: PublicKey): InputInfo = {

      val multisig = Scripts.multiSig2of2(fundingPubkey1, fundingPubkey2)
      val fundingTxOut = TxOut(fundingSatoshis, Script pay2wsh multisig)
      val outPoint = OutPoint(fundingTxHash, fundingTxOutputIndex)
      InputInfo(outPoint, fundingTxOut, Script write multisig)
    }

    // Assuming we are always a funder
    def makeFirstFunderCommitTxs(cmd: CMDOpenChannel, remoteParams: AcceptChannel,
                                 fundingTxHash: BinaryData, fundingTxOutputIndex: Int,
                                 remoteFirstPoint: Point) = {

      val toLocalMsat = cmd.fundingAmountSat * satFactor - cmd.pushMsat
      val commitmentInput = makeFundingInputInfo(fundingTxHash, fundingTxOutputIndex,
        Satoshi(cmd.fundingAmountSat), cmd.localParams.fundingPrivKey.publicKey,
        remoteParams.fundingPubkey)

      val localPerCommitmentPoint = Generators.perCommitPoint(cmd.localParams.shaSeed, 0)
      val localSpec = CommitmentSpec(Set.empty, Set.empty, Map.empty, cmd.initialFeeratePerKw, toLocalMsat, cmd.pushMsat)
      val remoteSpec = CommitmentSpec(Set.empty, Set.empty, Map.empty, cmd.initialFeeratePerKw, cmd.pushMsat, toLocalMsat)
      val (localCommitTx, _, _) = makeLocalTxs(0, cmd.localParams, remoteParams, commitmentInput, localPerCommitmentPoint, localSpec)
      val (remoteCommitTx, _, _, _, _) = makeRemoteTxs(0, cmd.localParams, remoteParams, commitmentInput, remoteFirstPoint, remoteSpec)
      (localSpec, localCommitTx, remoteSpec, remoteCommitTx)
    }
  }
}