package com.lightning.wallet

import java.util.Date

import com.lightning.wallet.R.string._
import com.lightning.wallet.Utils._
import org.bitcoinj.core.Transaction.MIN_NONDUST_OUTPUT
import android.content.DialogInterface.BUTTON_POSITIVE
import com.lightning.wallet.lncloud.ImplicitConversions._
import com.lightning.wallet.ln.Tools.{none, runAnd, wrap}
import com.lightning.wallet.R.drawable.{await, conf1, dead}
import android.widget._
import android.view.{Menu, MenuItem, View, ViewGroup}
import org.ndeftools.Message
import com.lightning.wallet.ln.LNParams._
import org.ndeftools.util.activity.NfcReaderActivity
import android.os.Bundle
import Utils.app
import android.app.AlertDialog
import com.lightning.wallet.ln.PaymentInfo._
import com.github.clans.fab.FloatingActionMenu
import android.support.v4.view.MenuItemCompat
import android.support.v7.widget.SearchView.OnQueryTextListener
import android.webkit.URLUtil
import com.lightning.wallet.helper.{ReactCallback, ReactLoader, RichCursor}
import com.lightning.wallet.ln.MSat._
import com.lightning.wallet.ln.Channel._
import com.lightning.wallet.ln._
import com.lightning.wallet.ln.Tools.{none, random}
import com.lightning.wallet.ln.wire.{CommitSig, RevokeAndAck}

import scala.concurrent.duration._
import com.lightning.wallet.lncloud._
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi}
import fr.acinq.bitcoin.Crypto.sha256
import org.bitcoinj.core.Address
import org.bitcoinj.uri.BitcoinURI

import scala.util.{Failure, Success}


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
with ToolbarActivity with HumanTimeDisplay
with ListUpdater with SearchBar { me =>

  lazy val fab = findViewById(R.id.fab).asInstanceOf[FloatingActionMenu]
  lazy val paymentsViewProvider = new PaymentsViewProvider
  lazy val lnTitle = me getString ln_title
  lazy val adapter = new LNAdapter

  private[this] var sendPayment: PaymentRequest => Unit = none
  private[this] var makePaymentRequest = anyToRunnable(none)
  private[this] var whenStop = anyToRunnable(super.onStop)
  override def onStop = whenStop.run

  // Payment history and search results loader
  // Remembers last search term in case of reload
  class PaymentsViewProvider extends ReactCallback(me) { self =>
    def onCreateLoader(id: Int, bundle: Bundle) = if (lastQuery.isEmpty) recent else search
    def search = new ExtendedPaymentInfoLoader { def getCursor = bag byQuery lastQuery }
    def recent = new ExtendedPaymentInfoLoader { def getCursor = bag.recentPayments }
    val observeTablePath = db sqlPath PaymentInfoTable.table
    private var lastQuery = new String

    def reload(txt: String) = runAnd(lastQuery = txt) {
      getSupportLoaderManager.restartLoader(1, null, self).forceLoad
    }

    abstract class ExtendedPaymentInfoLoader extends ReactLoader[PaymentInfo](me) {
      val consume: InfoVec => Unit = payments => me runOnUiThread updatePaymentList(payments)
      def updatePaymentList(ps: InfoVec) = wrap(adapter.notifyDataSetChanged)(adapter.payments = ps)
      def createItem(shifted: RichCursor) = bag toPaymentInfo shifted
      type InfoVec = Vector[PaymentInfo]
    }
  }

  class LNAdapter extends BaseAdapter {
    def getView(position: Int, cv: View, parent: ViewGroup) = {
      val view = if (null == cv) getLayoutInflater.inflate(txLineType, null) else cv
      val hold = if (null == view.getTag) new LNView(view) else view.getTag.asInstanceOf[LNView]
      hold fillView getItem(position)
      view
    }

    var payments = Vector.empty[PaymentInfo]
    def getItem(position: Int) = payments(position)
    def getItemId(position: Int) = position
    def getCount = payments.size
  }

  class LNView(view: View) extends TxViewHolder(view) {
    // Display payment details with respect to it's direction

    def fillView(info: PaymentInfo) = {
      val stamp = new Date(info.request.timestamp * 1000)
      val time = when(System.currentTimeMillis, stamp)

      val image = info match {
        case out: OutgoingPayment if out.isFulfilled => conf1
        case out: OutgoingPayment if out.isPending => await
        case IncomingPayment(_, _, _, _, SUCCESS) => conf1
        case _: IncomingPayment => await
        case _ => dead
      }

      val paymentMarking = info match {
        case in: IncomingPayment => sumIn format withSign(in.received)
        case out: OutgoingPayment => sumOut format withSign(out.request.negMSat)
      }

      transactWhen setText time.html
      transactSum setText paymentMarking.html
      transactCircle setImageResource image
    }
  }

  // INTERFACE IMPLEMENTING METHODS

  def react(query: String) = paymentsViewProvider reload query
  def notifySubTitle(subtitle: String, infoType: Int): Unit = {
    add(subtitle, infoType).timer.schedule(me del infoType, 25000)
    me runOnUiThread ui
  }

  // Initialize this activity, method is run once
  override def onCreate(savedState: Bundle) =
  {
    if (app.isAlive) {
      super.onCreate(savedState)
      wrap(initToolbar)(me setContentView R.layout.activity_ln)
      add(me getString ln_notify_connecting, Informer.LNSTATE).ui.run

      list setAdapter adapter
      list setFooterDividersEnabled false
      paymentsViewProvider reload new String
      me startListUpdates adapter
      me setDetecting true

      // Wire up general listeners
      app.kit.wallet addCoinsSentEventListener txTracker
      app.kit.wallet addCoinsReceivedEventListener txTracker
      app.kit.wallet addTransactionConfidenceEventListener txTracker
      app.kit.peerGroup addBlocksDownloadedEventListener catchListener
    } else me exitTo classOf[MainActivity]
  }

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

  override def onResume =
    wrap(run = super.onResume) {
      app.ChannelManager.alive.headOption match {
        case None => me exitTo classOf[LNOpsActivity]
        case Some(chan) => manageActive(chan)
      }
    }

  // APP MENU

  override def onOptionsItemSelected(m: MenuItem) = runAnd(true) {
    if (m.getItemId == R.id.actionSetBackupServer) new SetBackupServer
    else if (m.getItemId == R.id.actionCloseChannel) closeChannel
  }

  class SetBackupServer { self =>
    val (view, field) = str2Tuple(cloudPrivateKey.publicKey.toString)
    val dialog = mkChoiceDialog(proceed, none, dialog_next, dialog_cancel)
    val alert = mkForm(dialog, getString(ln_backup_key).html, view)
    field setTextIsSelectable true

    def proceed: Unit = rm(alert) {
      val (view1, field1) = generatePasswordPromptView(inpType = textType, txt = ln_backup_ip)
      val dialog = mkChoiceDialog(trySave(field1.getText.toString), none, dialog_ok, dialog_cancel)
      PrivateDataSaver.tryGetObject.foreach(field1 setText _.url)
      mkForm(dialog, me getString ln_backup, view1)
    }

    def trySave(url: String) = delayUI {
      if (url.isEmpty) PrivateDataSaver.remove
      else self check PrivateData(Nil, url)
    }

    def check(data: PrivateData) = {
      val failover = LNParams getFailoverCloud data
      val params = new PrivateStorage(failover).signedParams(random getBytes 32)
      failover.call("check", none, params:_*).subscribe(_ => self save data, onUIError)
    }

    def save(privateData: PrivateData) = {
      PrivateDataSaver saveObject privateData
      LNParams.resetCloudAndStorage
      app toast ln_backup_success
    }

    def onUIError(e: Throwable) = me runOnUiThread onError(e)
    def onError(error: Throwable): Unit = error.getMessage match {
      case "keynotfound" => mkForm(me negBld dialog_ok, null, me getString ln_backup_key_error)
      case "siginvalid" => mkForm(me negBld dialog_ok, null, me getString ln_backup_sig_error)
      case _ => mkForm(me negBld dialog_ok, null, me getString ln_backup_net_error)
    }
  }

  def closeChannel = checkPass(me getString ln_close) { pass =>
    // Close all of the channels just in case we have more than one
    for (chan <- app.ChannelManager.alive) chan async CMDShutdown
  }

  // WHEN ACTIVE CHAN IS PRESENT

  def manageActive(chan: Channel) = {
    def receiveSendStatus: (Long, Long) =
    Some(chan.data) collect { case norm: NormalData =>
      val canReceiveAmount = norm.commitments.localCommit.spec.toRemoteMsat
      val canSendAmount = norm.commitments.localCommit.spec.toLocalMsat
      canReceiveAmount -> canSendAmount
    } getOrElse 0L -> 0L

    val chanListener = new ChannelListener {
      // Updates UI accordingly to changes in channel

      override def onBecome = {
        case (_, norm: NormalData, SYNC, NORMAL) =>
        case (_, norm: NormalData, _, _) if norm.isClosing => me exitTo classOf[LNOpsActivity]
        case (_, _: WaitFundingDoneData, _, _) => me exitTo classOf[LNOpsActivity]
        case (_, _: NegotiationsData, _, _) => me exitTo classOf[LNOpsActivity]
        case (_, _: ClosingData, _, _) => me exitTo classOf[LNOpsActivity]
      }

      override def onError = {
        case ExtendedException(cmd: RetryAddHtlc) => Tools log s"Payment retry rejected $cmd"
        case ExtendedException(cmd: SilentAddHtlc) => Tools log s"Silent payment rejected $cmd"
        case ExtendedException(cmd: PlainAddHtlc) => onFail(me getString err_general)
        case PlainAddInSyncException(add) => onFail(me getString err_ln_add_sync)
        case _: Throwable => chan process CMDShutdown
      }

      override def onProcess = {
        case (_, _, _: RevokeAndAck) =>
        case (_, _, _: CommitSig) =>
        case (_, _, CMDOffline) =>
        case (_, _, CMDOnline) =>
      }
    }

    sendPayment = pr => {
      val content = getLayoutInflater.inflate(R.layout.frag_input_fiat_converter, null, false)
      val info = pr.description match { case Right(hash) => humanPubkey(hash.toString) case Left(text) => text }
      val alert = mkForm(negPosBld(dialog_cancel, dialog_next), getString(ln_send_title).format(info).html, content)

      val (_, canSendMsat) = receiveSendStatus
      val maxValue = MilliSatoshi apply math.min(canSendMsat, maxHtlcValue.amount)
      val hint = getString(satoshi_hint_max_amount) format withSign(maxValue)
      val rateManager = new RateManager(hint, content)

      def attempt = rateManager.result match {
        case Failure(_) => app toast dialog_sum_empty
        case Success(ms) if pr.amount.exists(_ > ms) => app toast dialog_sum_small
        case Success(ms) if htlcMinimumMsat > ms.amount => app toast dialog_sum_small
        case Success(ms) if pr.amount.exists(_ * 2 < ms) => app toast dialog_sum_big
        case Success(ms) if maxValue < ms => app toast dialog_sum_big

        case Success(ms) =>
          timer.schedule(me del Informer.LNPAYMENT, 5000)
          add(me getString ln_send, Informer.LNPAYMENT).ui.run

          app.ChannelManager.outPayment(pr).foreach(_ match {
            case Some(payment) => chan process PlainAddHtlc(payment)
            case None => onFail(me getString err_general)
          }, onFailDetailed)
      }

      def onFailDetailed(e: Throwable) = e.getMessage match {
        case "fromblacklisted" => onFail(me getString err_ln_black)
        case "noroutefound" => onFail(me getString err_ln_route)
        case _ => onFail(me getString err_general)
      }

      val ok = alert getButton BUTTON_POSITIVE
      ok setOnClickListener onButtonTap(attempt)
    }

    makePaymentRequest = anyToRunnable {
      val content = getLayoutInflater.inflate(R.layout.frag_input_receive_ln, null, false)
      val inputDescription = content.findViewById(R.id.inputDescription).asInstanceOf[EditText]
      val alert = mkForm(negPosBld(dialog_cancel, dialog_next), me getString ln_receive_title, content)

      val (canReceiveMsat, _) = receiveSendStatus
      val maxValue = MilliSatoshi apply math.min(canReceiveMsat, maxHtlcValue.amount)
      val hint = getString(satoshi_hint_max_amount) format withSign(maxValue)
      val rateManager = new RateManager(hint, content)

      def proceed(sum: Option[MilliSatoshi], preimage: BinaryData) = {
        val paymentRequest = PaymentRequest(chainHash, sum, sha256(preimage),
          nodePrivateKey, inputDescription.getText.toString.trim, None, 3600 * 24)

        PaymentInfoWrap putPaymentInfo IncomingPayment(preimage,
          paymentRequest, MilliSatoshi(0), chan.id.get, HIDDEN)

        app.TransData.value = paymentRequest
        me goTo classOf[RequestActivity]
      }

      val go: Option[MilliSatoshi] => Unit = sumOption => {
        <(proceed(sumOption, bag.newPreimage), onFail)(none)
        add(me getString ln_pr_make, Informer.LNREQUEST).ui.run
        timer.schedule(me del Informer.LNREQUEST, 2500)
      }

      def attempt = rateManager.result match {
        case Success(ms) if htlcMinimumMsat > ms.amount => app toast dialog_sum_small
        case Success(ms) if maxValue < ms => app toast dialog_sum_big
        case ok @ Success(ms) => rm(alert)(go apply ok.toOption)
        case _ => rm(alert)(go apply None)
      }

      val ok = alert getButton BUTTON_POSITIVE
      ok setOnClickListener onButtonTap(attempt)
    }

    whenStop = anyToRunnable {
      chan.listeners -= chanListener
      super.onStop
    }

    chan.listeners += chanListener
    chanListener reloadOnBecome chan
    checkTransData
  }

  // DATA READING AND BUTTON ACTIONS

  def checkTransData = app.TransData.value match {
    case uri: BitcoinURI => me goTo classOf[BtcActivity]
    case adr: Address => me goTo classOf[BtcActivity]

    case pr: PaymentRequest =>
      app.TransData.value = null
      sendPayment(pr)

    case unusable =>
      app.TransData.value = null
      Tools log s"Unusable $unusable"
  }

  // Reactions to menu
  def goBitcoin(top: View) = {
    me goTo classOf[BtcActivity]
    fab close true
  }

  def goQR(top: View) = {
    me goTo classOf[ScanActivity]
    fab close true
  }

  def goReceive(top: View) = {
    me delayUI makePaymentRequest.run
    fab close true
  }
}