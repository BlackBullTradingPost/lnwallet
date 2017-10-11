package com.lightning.wallet

import com.lightning.wallet.ln._
import com.lightning.wallet.Utils._
import com.lightning.wallet.R.string._
import com.lightning.wallet.ln.Channel._
import com.lightning.wallet.lncloud.ImplicitConversions._
import fr.acinq.bitcoin.{Satoshi, Transaction}
import com.lightning.wallet.ln.Tools.none
import android.widget.Button
import android.os.Bundle
import android.view.View


class LNOpsActivity extends TimerActivity { me =>
  lazy val txsConfs = getResources getStringArray R.array.txs_confs
  lazy val blocksLeft = getResources getStringArray R.array.ln_ops_chan_unilateral_status_left_blocks
  lazy val lnOpsDescription = me clickableTextField findViewById(R.id.lnOpsDescription)
  lazy val lnOpsAction = findViewById(R.id.lnOpsAction).asInstanceOf[Button]
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

  private def humanStatus(tx: Transaction) =
    LNParams.broadcaster txStatus tx.txid match {
      case Some(cfs \ false) => app.plurOrZero(txsConfs, cfs)
      case Some(_ \ true) => txsConfs.last
      case _ => txsConfs.head
    }

  private def manageFirst(chan: Channel) = {
    def manageOpening(c: Commitments, open: Transaction) = {
      val threshold = math.max(c.remoteParams.minimumDepth, LNParams.minDepth)
      val balance: String = coloredIn(c.commitInput.txOut.amount)
      val openStatus: String = humanStatus(open)

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
        // A new channel has been created with funding transaction broadcasted
        case (_, WaitFundingDoneData(_, _, _, tx, commitments), _, _) =>
          me runOnUiThread manageOpening(commitments, tx)

        // Mutual shutdown initiated
        case (_, norm: NormalData, _, _) if norm.isFinishing =>
          me runOnUiThread manageNegotiations(norm.commitments)

        // Normal is managed by main activity
        case (_, norm: NormalData, _, _) =>
          me exitTo classOf[LNActivity]

        // Closing tx fee negotiations started
        case (_, negs: NegotiationsData, _, _) =>
          me runOnUiThread manageNegotiations(negs.commitments)

        // Mutual closing is in progress because only a mutual close tx is available here
        case (_, close @ ClosingData(_, commitments, _ :: _, Nil, Nil, Nil, Nil, _), _, CLOSING) =>
          me runOnUiThread manageMutualClosing(close)

        // Someone has initiated a unilateral channel closing
        case (_, close @ ClosingData(_, _, _, localTxs, remoteTxs, remoteNextTxs, revokedTxs, _), _, CLOSING)
          if localTxs.nonEmpty || remoteTxs.nonEmpty || remoteNextTxs.nonEmpty || revokedTxs.nonEmpty =>
          me runOnUiThread manageForcedClosing(close)

        case _ =>
          // Mutual closing without txs, recovery mode
          // and any other possibly unaccounted state
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
  private def commit(data: ClosingData) = {
    val totalOutAmount: Satoshi = data.allCommits.head.txOut.map(_.amount).sum
    val fee = coloredOut(data.commitments.commitInput.txOut.amount - totalOutAmount)
    commitStatus.format(humanStatus(data.allCommits.head), fee)
  }

  private def manageMutualClosing(data: ClosingData) = {
    lnOpsDescription setText bilateralClosing.format(me commit data).html
    lnOpsAction setOnClickListener onButtonTap(goStartChannel)
    lnOpsAction setText ln_ops_start
  }

  private def manageForcedClosing(data: ClosingData) = {
    def basis(fee: Satoshi, amount: Satoshi) = amountStatus
      .format(denom formatted amount + fee, coloredOut apply fee)

    val humanPublishStatus = data.getAllStates take 3 map {
      case (Some(true \ _), fee, amt) => getString(ln_ops_chan_unilateral_status_dead).format(basis(fee, amt), coloredIn apply amt)
      case (Some(false \ 0L), fee, amt) => getString(ln_ops_chan_unilateral_status_done).format(basis(fee, amt), coloredIn apply amt)
      case (Some(false \ left), fee, amt) => statusLeft.format(app.plurOrZero(blocksLeft, left), basis(fee, amt), coloredIn apply amt)
      case (_, fee, amt) => getString(ln_ops_chan_unilateral_status_wait).format(basis(fee, amt), coloredIn apply amt)
    } mkString "<br><br>"

    lnOpsAction setText ln_ops_start
    lnOpsAction setOnClickListener onButtonTap(goStartChannel)
    lnOpsDescription setText getString(ln_ops_chan_unilateral_closing)
      .format(s"${me commit data}<br><br>$refundStatus<br>$humanPublishStatus").html
  }

  // Offer to create a new channel
  private def manageNoActiveChannel = {
    lnOpsAction setOnClickListener onButtonTap(goStartChannel)
    lnOpsDescription setText ln_ops_chan_none
    lnOpsAction setText ln_ops_start
  }
}