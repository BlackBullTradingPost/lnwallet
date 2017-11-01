package com.lightning.wallet

import com.lightning.wallet.ln._
import com.lightning.wallet.Utils._
import com.lightning.wallet.R.string._
import com.lightning.wallet.ln.Broadcaster._
import com.lightning.wallet.lnutils.ImplicitConversions._
import com.lightning.wallet.ln.LNParams.broadcaster.txStatus
import com.lightning.wallet.ln.Tools.none
import fr.acinq.bitcoin.Transaction
import android.widget.Button
import android.os.Bundle
import android.view.View


class LNOpsActivity extends TimerActivity { me =>
  lazy val txsConfs = getResources getStringArray R.array.txs_confs
  lazy val blocksLeft = getResources getStringArray R.array.ln_ops_chan_unilateral_status_left_blocks
  lazy val lnOpsDescription = me clickableTextField findViewById(R.id.lnOpsDescription)
  lazy val lnOpsAction = findViewById(R.id.lnOpsAction).asInstanceOf[Button]
  lazy val unilateralClosing = getString(ln_ops_chan_unilateral_closing)
  lazy val bilateralClosing = getString(ln_ops_chan_bilateral_closing)
  lazy val statusLeft = getString(ln_ops_chan_unilateral_status_left)
  lazy val refundStatus = getString(ln_ops_chan_refund_status)
  lazy val amountStatus = getString(ln_ops_chan_amount_status)
  lazy val commitStatus = getString(ln_ops_chan_commit_status)
  def goBitcoin(view: View) = me exitTo classOf[BtcActivity]
  def goStartChannel = me exitTo classOf[LNStartActivity]

  // May change destroy action throughout an activity lifecycle
  private[this] var whenDestroy = anyToRunnable(super.onDestroy)
  override def onDestroy = whenDestroy.run

  // Initialize this activity, method is run once
  override def onCreate(savedInstanceState: Bundle) =
  {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_ln_ops)
    app.ChannelManager.all.headOption match {
      case Some(channel) => manageFirst(channel)
      case None => manageNoActiveChannel
    }
  }

  private def manageFirst(chan: Channel) = {
    def manageOpening(c: Commitments, open: Transaction) = {
      val threshold = math.max(c.remoteParams.minimumDepth, LNParams.minDepth)
      val openStatus = humanStatus(LNParams.broadcaster txStatus open.txid)
      val balance = coloredIn(c.commitInput.txOut.amount)

      lnOpsAction setText ln_force_close
      lnOpsAction setOnClickListener onButtonTap(warnAboutUnilateralClosing)
      lnOpsDescription setText getString(ln_ops_chan_opening).format(balance,
        app.plurOrZero(txsConfs, threshold), openStatus).html
    }

    def manageNegotiations(c: Commitments) = {
      val description = getString(ln_ops_chan_bilateral_negotiations)
      lnOpsAction setOnClickListener onButtonTap(warnAboutUnilateralClosing)
      lnOpsDescription setText description.html
      lnOpsAction setText ln_force_close
    }

    def warnAboutUnilateralClosing =
      mkForm(mkChoiceDialog(chan process CMDShutdown, none, ln_force_close,
        dialog_cancel), null, getString(ln_ops_chan_unilateral_warn).html)

    val chanOpsListener = new ChannelListener {
      // Updates UI accordingly to changes in channel

      override def onBecome = {
        case (_, c: ClosingData, _, _) if c.mutualClose.nonEmpty || c.tier12States.nonEmpty => me runOnUiThread manageClosing(c)
        case (_, WaitFundingDoneData(_, _, _, tx, commitments), _, _) => me runOnUiThread manageOpening(commitments, tx)
        case (_, norm: NormalData, _, _) if norm.isFinishing => me runOnUiThread manageNegotiations(norm.commitments)
        case (_, negs: NegotiationsData, _, _) => me runOnUiThread manageNegotiations(negs.commitments)
        case (_, norm: NormalData, _, _) => me exitTo classOf[LNActivity]

        case _ =>
          // Closing without txs, recovery mode
          // and other possibly unaccounted states
          me runOnUiThread manageNoActiveChannel
      }

      override def onProcess = {
        case (_, _, _: CMDBestHeight) =>
          // Need to update UI on each block
          reloadOnBecome(chan)
      }
    }

    whenDestroy = anyToRunnable {
      chan.listeners -= chanOpsListener
      super.onDestroy
    }

    chan.listeners += chanOpsListener
    chanOpsListener reloadOnBecome chan
  }

  // UI which does not need a channel
  private val humanStatus: DepthAndDead => String = {
    case confs \ false => app.plurOrZero(txsConfs, confs)
    case _ \ true => txsConfs.last
    case _ => txsConfs.head
  }

  // We need to show the best closing with most confirmations
  private def manageClosing(data: ClosingData) = data.closings maxBy {
    case Left(mutualTx) => txStatus(mutualTx.txid) match { case cfs \ _ => cfs }
    case Right(info) => txStatus(info.commitTx.txid) match { case cfs \ _ => cfs }
  } match {
    case Left(mutualTx) =>
      val mutualFee = coloredOut(data.commitments.commitInput.txOut.amount - mutualTx.txOut.map(_.amount).sum)
      val mutualTxHumanView = commitStatus.format(humanStatus(LNParams.broadcaster txStatus mutualTx.txid), mutualFee)
      lnOpsDescription setText bilateralClosing.format(mutualTxHumanView).html
      lnOpsAction setOnClickListener onButtonTap(goStartChannel)
      lnOpsAction setText ln_ops_start

    case Right(info) =>
      val tier2HumanView = info.getState collect {
        case Show(_ \ true \ _, _, fee, finalAmt) =>
          val deadDEtails = amountStatus.format(denom formatted finalAmt + fee, coloredOut apply fee)
          getString(ln_ops_chan_unilateral_status_dead).format(deadDEtails, coloredIn apply finalAmt)

        case show @ Show(_ \ false \ _, _, fee, finalAmt) if show.isPublishable =>
          val doneDetails = amountStatus.format(denom formatted finalAmt + fee, coloredOut apply fee)
          getString(ln_ops_chan_unilateral_status_done).format(doneDetails, coloredIn apply finalAmt)

        case Show(_ \ false \ left, _, fee, finalAmt) =>
          val leftDetails = amountStatus.format(denom formatted finalAmt + fee, coloredOut apply fee)
          statusLeft.format(app.plurOrZero(blocksLeft, left), leftDetails, coloredIn apply finalAmt)
      } take 3

      val commitFee = coloredOut(data.commitments.commitInput.txOut.amount - info.commitTx.txOut.map(_.amount).sum)
      val tier0HumanView = commitStatus.format(humanStatus(LNParams.broadcaster txStatus info.commitTx.txid), commitFee)
      val combinedView = tier0HumanView + s"<br><br>$refundStatus<br>" + tier2HumanView.mkString("<br><br>")
      lnOpsDescription setText unilateralClosing.format(combinedView).html
      lnOpsAction setOnClickListener onButtonTap(goStartChannel)
      lnOpsAction setText ln_ops_start
  }

  // Offer to create a new channel
  private def manageNoActiveChannel = {
    lnOpsAction setOnClickListener onButtonTap(goStartChannel)
    lnOpsDescription setText ln_ops_chan_none
    lnOpsAction setText ln_ops_start
  }
}