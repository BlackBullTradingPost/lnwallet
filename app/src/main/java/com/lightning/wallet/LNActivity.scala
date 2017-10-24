package com.lightning.wallet

import android.view._
import android.widget._
import com.lightning.wallet.ln._
import com.lightning.wallet.Utils._
import com.lightning.wallet.helper._
import com.lightning.wallet.lnutils._
import com.lightning.wallet.R.string._
import com.lightning.wallet.ln.Channel._
import com.lightning.wallet.ln.LNParams._
import com.lightning.wallet.ln.PaymentInfo._
import com.lightning.wallet.lnutils.ImplicitConversions._
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi, Satoshi}
import com.lightning.wallet.R.drawable.{await, conf1, dead}
import com.lightning.wallet.ln.Tools.{runAnd, wrap}
import scala.util.{Failure, Success, Try}

import fr.castorflex.android.smoothprogressbar.SmoothProgressDrawable
import fr.castorflex.android.smoothprogressbar.SmoothProgressBar
import android.support.v7.widget.SearchView.OnQueryTextListener
import android.content.Context.LAYOUT_INFLATER_SERVICE
import android.content.DialogInterface.BUTTON_POSITIVE
import org.ndeftools.util.activity.NfcReaderActivity
import com.github.clans.fab.FloatingActionMenu
import com.lightning.wallet.ln.wire.CommitSig
import android.support.v4.view.MenuItemCompat
import android.view.ViewGroup.LayoutParams
import com.lightning.wallet.ln.Tools.none
import com.lightning.wallet.Utils.app
import org.bitcoinj.uri.BitcoinURI
import org.bitcoinj.core.Address
import org.ndeftools.Message
import android.os.Bundle
import java.util.Date


trait SearchBar { me =>
  import android.support.v7.widget.SearchView
  protected[this] var searchItem: MenuItem = _
  protected[this] var search: SearchView = _

  private[this] val lst = new OnQueryTextListener {
    def onQueryTextSubmit(queryText: String) = true
    def onQueryTextChange(queryText: String) =
      runAnd(true)(me react queryText)
  }

  def react(query: String)
  def setupSearch(menu: Menu) = {
    searchItem = menu findItem R.id.action_search
    val view = MenuItemCompat getActionView searchItem
    search = view.asInstanceOf[SearchView]
    search setOnQueryTextListener lst
  }
}

trait DataReader extends NfcReaderActivity {
  def readEmptyNdefMessage = app toast nfc_error
  def readNonNdefMessage = app toast nfc_error
  def onNfcStateChange(ok: Boolean) = none
  def onNfcFeatureNotFound = none
  def onNfcStateDisabled = none
  def onNfcStateEnabled = none
  def checkTransData: Unit

  def readNdefMessage(msg: Message) = try {
    val asText = readFirstTextNdefMessage(msg)
    app.TransData recordValue asText
    checkTransData

  } catch { case _: Throwable =>
    // Could not process a message
    app toast nfc_error
  }
}

class LNActivity extends DataReader
with ToolbarActivity with ListUpdater
with SearchBar { me =>

  val imgMap = Array(await, await, conf1, dead)
  lazy val paymentStatesMap = getResources getStringArray R.array.ln_payment_states
  lazy val layoutInflater = app.getSystemService(LAYOUT_INFLATER_SERVICE).asInstanceOf[LayoutInflater]
  lazy val viewParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
  lazy val container = findViewById(R.id.container).asInstanceOf[RelativeLayout]
  lazy val fab = findViewById(R.id.fab).asInstanceOf[FloatingActionMenu]
  lazy val paymentsViewProvider = new PaymentsViewProvider

  lazy val adapter = new CutAdapter[PaymentInfo] {
    def getItem(position: Int) = visibleItems(position)
    def getHolder(view: View) = new TxViewHolder(view) {

      def fillView(info: PaymentInfo) = {
        val timestamp = new Date(info.request.timestamp * 1000)
        val purpose = info.request.description.right.toSeq.mkString

        val marking = info match {
          case in: IncomingPayment => sumIn.format(denom formatted in.received)
          case out => sumOut.format(denom formatted out.request.finalSum * -1)
        }

        transactWhen setText when(System.currentTimeMillis, timestamp).html
        transactCircle setImageResource imgMap(info.actualStatus)
        transactSum setText s"$marking\u00A0$purpose".html
      }
    }
  }

  val chanListener = new ChannelListener {
    // Updates local ui according to changes in channel
    // Should be removed when activity is stopped

    override def onError = {
      case _ \ ReserveException(PlainAddHtlc(out), missingSat, reserveSat) =>
        // Current commit tx fee and channel reserve forbid sending of this payment
        // Inform user with all the details laid out as cleanly as possible

        val reserve = coloredIn apply Satoshi(reserveSat)
        val missing = coloredOut apply Satoshi(missingSat)
        val sending = coloredOut apply MilliSatoshi(out.routing.amountWithFee)
        onFail(getString(err_ln_fee_overflow).format(reserve, sending, missing).html)

      case _ \ AddException(_: PlainAddHtlc, code) =>
        // Let user know why payment could not be added
        onFail(me getString code)
    }

    override def onBecome = {
      case (_, norm: NormalData, _, _) if norm.isFinishing => evacuate
      case (_, _: EndingData | _: NegotiationsData | _: WaitFundingDoneData, _, _) => evacuate
      case (_, _: NormalData, _, SYNC) => update(me getString ln_notify_connecting, Informer.LNSTATE).flash.run
      case (_, _: NormalData, _, NORMAL) => update(me getString ln_notify_operational, Informer.LNSTATE).flash.run
    }

    override def onProcess = {
      case (chan, norm: NormalData, _: CommitSig)
        // GUARD: notify and vibrate because HTLC is fulfilled
        if norm.commitments.localCommit.spec.fulfilled.nonEmpty =>
        notifySubTitle(me getString ln_done, Informer.LNSUCCESS)
        updTitle(chan)
    }
  }

  private[this] var sendPayment: PaymentRequest => Unit = none
  private[this] var makePaymentRequest = anyToRunnable(none)
  private[this] var whenStop = anyToRunnable(super.onStop)
  override def onStop = whenStop.run

  def evacuate = me exitTo classOf[LNOpsActivity]
  def react(qs: String) = paymentsViewProvider reload qs
  def notifySubTitle(subtitle: String, infoType: Int) = {
    // Title will updated separately so just update subtitle
    timer.schedule(delete(infoType), 10000)
    add(subtitle, infoType).flash.run
  }

  // Initialize this activity, method is run once
  override def onCreate(savedState: Bundle) =

    if (app.isAlive) {
      super.onCreate(savedState)

      // Set action bar, main view content, wire up list events, update title later
      wrap(me setSupportActionBar toolbar)(me setContentView R.layout.activity_ln)
      add(me getString ln_notify_connecting, Informer.LNSTATE)
      me startListUpdates adapter
      me setDetecting true

      list setAdapter adapter
      list setFooterDividersEnabled false
      paymentsViewProvider reload new String
      app.kit.wallet addCoinsSentEventListener txTracker
      app.kit.wallet addCoinsReceivedEventListener txTracker
      app.kit.wallet addTransactionConfidenceEventListener txTracker
      app.kit.peerGroup addBlocksDownloadedEventListener catchListener
    } else me exitTo classOf[MainActivity]

  override def onDestroy = wrap(super.onDestroy) {
    app.kit.wallet removeCoinsSentEventListener txTracker
    app.kit.wallet removeCoinsReceivedEventListener txTracker
    app.kit.wallet removeTransactionConfidenceEventListener txTracker
    stopDetecting
  }

  override def onCreateOptionsMenu(menu: Menu) = {
    getMenuInflater.inflate(R.menu.ln_normal_ops, menu)
    setupSearch(menu)
    true
  }

  override def onResume: Unit = wrap(super.onResume) {
    app.prefs.edit.putString(AbstractKit.LANDING, AbstractKit.LIGHTNING).commit
    app.ChannelManager.alive.headOption map manageActive getOrElse evacuate
  }

  // APP MENU

  override def onOptionsItemSelected(menu: MenuItem) = runAnd(true) {
    if (menu.getItemId == R.id.actionCloseChannel) closeAllActiveChannels
    else if (menu.getItemId == R.id.actionSettings) mkSetsForm
  }

  def closeAllActiveChannels = checkPass(me getString ln_close) { pass =>
    // Close all of the channels just in case we have more than one active
    for (chan <- app.ChannelManager.alive) chan process CMDShutdown
  }

  def updTitle(chan: Channel) = animateTitle {
    val canSend = chan.pull(_.localCommit.spec.toLocalMsat)
    denom withSign MilliSatoshi(canSend getOrElse 0L)
  }

  def manageActive(chan: Channel) = {
    def onPaymentError(e: Throwable) = e.getMessage match {
      case FROMBLACKLISTED => onFail(me getString err_ln_black)
      case techDetails => onFail(techDetails)
    }

    val onPaymentResult: Option[OutgoingPayment] => Unit = {
      case Some(payment) => chan process PlainAddHtlc(payment)
      case None => onFail(me getString err_ln_no_route)
    }

    list setOnItemClickListener onTap { pos =>
      val detailsWrapper = getLayoutInflater.inflate(R.layout.frag_ln_payment_details, null)
      val paymentDetails = detailsWrapper.findViewById(R.id.paymentDetails).asInstanceOf[TextView]
      val paymentHash = detailsWrapper.findViewById(R.id.paymentHash).asInstanceOf[Button]

      val payment = adapter getItem pos
      val description = me getDescription payment.request
      val humanStatus = s"<strong>${paymentStatesMap apply payment.actualStatus}</strong>"
      paymentHash setOnClickListener onButtonTap(app setBuffer payment.request.paymentHash.toString)
      paymentHash.setText(payment.request.paymentHash.toString grouped 8 mkString "\u0020")

      if (payment.actualStatus == SUCCESS) {
        // Users may copy request and preimage for fulfilled payments to prove they've happened
        val paymentProof = detailsWrapper.findViewById(R.id.paymentProof).asInstanceOf[Button]
        paymentProof setVisibility View.VISIBLE

        paymentProof setOnClickListener onButtonTap {
          val serializedRequest = PaymentRequest.write(payment.request)
          app setBuffer getString(ln_proof).format(serializedRequest,
            payment.preimage.toString)
        }
      }

      payment match {
        case in: IncomingPayment =>
          val title = getString(ln_incoming_title).format(humanStatus)
          val humanReceived = humanFiat(coloredIn(in.received), in.received)
          paymentDetails setText s"$description<br><br>$humanReceived".html
          mkForm(me negBld dialog_ok, title.html, detailsWrapper)

        case out: OutgoingPayment =>
          val humanSent = humanFiat(coloredOut(out.request.finalSum), out.request.finalSum)
          val fee = MilliSatoshi(out.routing.amountWithFee - out.request.finalSum.amount)
          val title = getString(ln_outgoing_title).format(coloredOut(fee), humanStatus)
          val alert = mkForm(me negBld dialog_ok, title.html, detailsWrapper)
          paymentDetails setText s"$description<br><br>$humanSent".html

          def doManualPaymentRetry = rm(alert) {
            val progressBarManager = new ProgressBarManager
            notifySubTitle(me getString ln_send, Informer.LNPAYMENT)
            app.ChannelManager.outPaymentObs(out.routing.badNodes, out.routing.badChannels, out.request)
              .doOnTerminate(progressBarManager.delayedRemove).foreach(onPaymentResult, onPaymentError)
          }

          if (out.actualStatus == FAILURE && out.request.isFresh) {
            // Users may issue a server request to get an updated set of routes for failed but fresh payments
            val paymentRetryAgain = detailsWrapper.findViewById(R.id.paymentRetryAgain).asInstanceOf[Button]
            paymentRetryAgain setOnClickListener onButtonTap(doManualPaymentRetry)
            paymentRetryAgain setVisibility View.VISIBLE
          }
      }
    }

    toolbar setOnClickListener onButtonTap {
      wrap(adapter.notifyDataSetChanged)(changeDenom)
      updTitle(chan)
    }

    sendPayment = pr => {
      val info = me getDescription pr
      val canSend = chan.pull(_.localCommit.spec.toLocalMsat)
      val maxMsat = MilliSatoshi apply math.min(canSend getOrElse 0L, maxHtlcValue.amount)
      val content = getLayoutInflater.inflate(R.layout.frag_input_fiat_converter, null, false)
      val rateManager = new RateManager(getString(amount_hint_maxamount).format(denom withSign maxMsat), content)
      val alert = mkForm(negPosBld(dialog_cancel, dialog_pay), getString(ln_send_title).format(info).html, content)

      def sendAttempt = rateManager.result match {
        case Failure(_) => app toast dialog_sum_empty
        case Success(ms) if maxMsat < ms => app toast dialog_sum_big
        case Success(ms) if pr.amount.exists(_ * 2 < ms) => app toast dialog_sum_big
        case Success(ms) if htlcMinimumMsat > ms.amount => app toast dialog_sum_small
        case Success(ms) if pr.amount.exists(_ > ms) => app toast dialog_sum_small

        case _ => rm(alert) {
          val progressBarManager = new ProgressBarManager
          notifySubTitle(me getString ln_send, Informer.LNPAYMENT)
          app.ChannelManager.outPaymentObs(Set.empty, Set.empty, pr)
            .doOnTerminate(progressBarManager.delayedRemove)
            .foreach(onPaymentResult, onPaymentError)
        }
      }

      val ok = alert getButton BUTTON_POSITIVE
      ok setOnClickListener onButtonTap(sendAttempt)
      for (sum <- pr.amount) rateManager setSum Try(sum)
    }

    makePaymentRequest = anyToRunnable {
      val canReceive = chan.pull(_.localCommit.spec.toRemoteMsat)
      val content = getLayoutInflater.inflate(R.layout.frag_ln_input_receive, null, false)
      val inputDescription = content.findViewById(R.id.inputDescription).asInstanceOf[EditText]
      val alert = mkForm(negPosBld(dialog_cancel, dialog_ok), me getString ln_receive, content)
      val maxMsat = MilliSatoshi apply math.min(canReceive getOrElse 0L, maxHtlcValue.amount)
      val hint = getString(amount_hint_maxamount).format(denom withSign maxMsat)
      val rateManager = new RateManager(hint, content)

      def proceed(amount: Option[MilliSatoshi], preimg: BinaryData) = chan.pull(_.channelId) foreach { chanId =>
        val paymentRequest = PaymentRequest(chainHash, amount, paymentHash = Crypto sha256 preimg, nodePrivateKey,
          inputDescription.getText.toString.trim, fallbackAddress = None, 3600 * 6, extra = Vector.empty)

        // Unfulfilled incoming HTLCs are marked HIDDEN and not displayed to user by default
        // Received amount is set to 0 msat for now, final amount may be higher than requested
        bag putPaymentInfo IncomingPayment(MilliSatoshi(0L), preimg, paymentRequest, chanId, HIDDEN)
        app.TransData.value = paymentRequest
        me goTo classOf[RequestActivity]
      }

      def receiveAttempt = rateManager.result match {
        case Success(ms) if maxMsat < ms => app toast dialog_sum_big
        case Success(ms) if htlcMinimumMsat > ms.amount => app toast dialog_sum_small

        case result => rm(alert) {
          notifySubTitle(me getString ln_pr_make, Informer.LNPAYMENT)
          <(proceed(result.toOption, bag.newPreimage), onFail)(none)
        }
      }

      val ok = alert getButton BUTTON_POSITIVE
      ok setOnClickListener onButtonTap(receiveAttempt)
    }

    whenStop = anyToRunnable {
      chan.listeners -= chanListener
      super.onStop
    }

    chan.listeners += chanListener
    chanListener reloadOnBecome chan
    checkTransData
    updTitle(chan)
  }

  // DATA READING AND BUTTON ACTIONS

  def checkTransData = app.TransData.value match {
    case uri: BitcoinURI => me goTo classOf[BtcActivity]
    case adr: Address => me goTo classOf[BtcActivity]

    case pr: PaymentRequest =>
      app.TransData.value = null
      if (pr.isFresh) sendPayment(pr)
      else onFail(me getString err_ln_old)

    case unusable =>
      app.TransData.value = null
      Tools log s"Unusable $unusable"
  }

  // Reactions to menu
  def goBitcoin(top: View) = {
    val activity = classOf[BtcActivity]
    delayUI(me goTo activity)
    fab close true
  }

  def goQR(top: View) = {
    val activity = classOf[ScanActivity]
    delayUI(me goTo activity)
    fab close true
  }

  def goReceive(top: View) = {
    delayUI(makePaymentRequest.run)
    fab close true
  }

  def toggle(v: View) = {
    // Expand or collapse all txs
    // adapter contains all history

    adapter.switch
    adapter set adapter.availableItems
    adapter.notifyDataSetChanged
  }

  // Payment history and search results loader
  class PaymentsViewProvider extends ReactCallback(me) { self =>
    def onCreateLoader(id: Int, bundle: Bundle) = if (lastQuery.isEmpty) recent else search
    def search = new ExtendedPaymentInfoLoader { def getCursor = bag byQuery lastQuery }
    def recent = new ExtendedPaymentInfoLoader { def getCursor = bag.recentPayments }
    val observeTablePath = db sqlPath PaymentInfoTable.table
    var lastQuery = new String

    def reload(txt: String) = runAnd(lastQuery = txt) {
      // Remember last search term to handle possible reload
      getSupportLoaderManager.restartLoader(1, null, self).forceLoad
    }

    abstract class ExtendedPaymentInfoLoader extends ReactLoader[PaymentInfo](me) {
      val consume: InfoVec => Unit = payments => me runOnUiThread updatePaymentList(payments)
      def updatePaymentList(pays: InfoVec) = wrap(adapter.notifyDataSetChanged)(adapter set pays)
      def createItem(shifted: RichCursor) = bag toPaymentInfo shifted
      type InfoVec = Vector[PaymentInfo]
    }
  }

  class ProgressBarManager {
    def delayedRemove = try timer.schedule(anyToRunnable(progressBar.progressiveStop), 250) catch none
    val progressBar = layoutInflater.inflate(R.layout.frag_progress_bar, null).asInstanceOf[SmoothProgressBar]
    val drawable = progressBar.getIndeterminateDrawable.asInstanceOf[SmoothProgressDrawable]

    container.addView(progressBar, viewParams)
    drawable setCallbacks new SmoothProgressDrawable.Callbacks {
      // In some cases activity may be killed while progress bar is active so timer will throw here
      def onStop = try timer.schedule(anyToRunnable(container removeView progressBar), 250) catch none
      def onStart = try drawable.setColors(getResources getIntArray R.array.bar_colors) catch none
    }
  }

  def getDescription(pr: PaymentRequest) = pr.description match {
    case Right(description) if description.nonEmpty => description take 140
    case Left(descriptionHash) => s"<i>${descriptionHash.toString}</i>"
    case _ => s"<i>${me getString ln_no_description}</i>"
  }
}