package com.lightning.wallet

import android.nfc.NfcEvent
import com.lightning.wallet.R.string._
import android.content._
import android.text.{Html, Spanned}
import com.lightning.wallet.Utils._
import com.lightning.wallet.R.drawable.{await, conf1, dead}
import org.bitcoinj.core.Utils.HEX
import android.content.DialogInterface.BUTTON_POSITIVE
import com.lightning.wallet.lncloud.ImplicitConversions.string2Ops

import collection.JavaConverters.seqAsJavaListConverter
import com.lightning.wallet.ln.Tools.{none, runAnd, wrap}
import android.widget._
import android.view.{Menu, MenuItem, View, ViewGroup}
import org.ndeftools.Message
import org.ndeftools.util.activity.NfcReaderActivity
import spray.json._
import org.bitcoinj.core.Transaction.MIN_NONDUST_OUTPUT

import concurrent.ExecutionContext.Implicits.global
import android.os.Bundle
import Utils.app
import android.database.Cursor
import android.support.v4.view.MenuItemCompat
import android.support.v7.widget.SearchView.{OnCloseListener, OnQueryTextListener}
import android.view.View.OnClickListener
import android.webkit.URLUtil
import com.lightning.wallet.lncloud.JsonHttpUtils._
import com.lightning.wallet.helper._
import com.lightning.wallet.ln.MSat._
import com.lightning.wallet.ln.wire.UpdateAddHtlc
import com.lightning.wallet.ln._
import com.lightning.wallet.lncloud.{PrivateData, PrivateDataSaver}
import com.lightning.wallet.test.LNCloudSpec
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi}
import org.bitcoinj.core.{Address, Sha256Hash, Transaction}
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.uri.BitcoinURI
import rx.lang.scala.schedulers.IOScheduler

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}


trait SearchBar { me =>
  import android.support.v7.widget.SearchView
  protected[this] var searchItem: MenuItem = _
  protected[this] var search: SearchView = _

  private[this] val lst = new OnQueryTextListener {
    def onQueryTextSubmit(queryText: String) = true
    def onQueryTextChange(queryText: String) =
      runAnd(true)(me react queryText)
  }

  def setupSearch(menu: Menu) = {
    searchItem = menu findItem R.id.action_search
    val view = MenuItemCompat getActionView searchItem
    search = view.asInstanceOf[SearchView]
    search setOnQueryTextListener lst
  }

  def react(query: String)
  def mkBundle(args:(String, String)*) = new Bundle match { case bundle =>
    for (Tuple2(key, value) <- args) bundle.putString(key, value)
    bundle
  }
}

class LNActivity extends NfcReaderActivity
with ToolbarActivity with HumanTimeDisplay
with ListUpdater with SearchBar { me =>

  lazy val fab = findViewById(R.id.fab).asInstanceOf[com.github.clans.fab.FloatingActionMenu]
  lazy val lnItemsList = findViewById(R.id.lnItemsList).asInstanceOf[ListView]
  lazy val lnTitle = getString(ln_title)
  //lazy val adapter = new LNAdapter

  // Adapter for ln txs list
//  class LNAdapter extends BaseAdapter {
//    def getView(paymentPosition: Int, cv: View, parent: ViewGroup) = {
//      val view = if (null == cv) getLayoutInflater.inflate(R.layout.frag_transaction_line_narrow, null) else cv
//      val hold = if (null == view.getTag) new LNView(view) else view.getTag.asInstanceOf[LNView]
//      hold fillView getItem(paymentPosition)
//      view
//    }
//
//    var payments = Vector.empty[HtlcWrap]
//    def getItemId(position: Int) = position
//    def getItem(position: Int) = payments(position)
//    def getCount = payments.length
//  }

  // Payment history and search results loader
  // Remembers last search term in case of reload
//  class RecentPayments extends ReactCallback(me) {
//    val observeTablePath = app.db sqlPath Payments.table
//    private var lastBundle: Bundle = null
//
//    def onCreateLoader(id: Int, b: Bundle) =
//      if (lastBundle == null) recent else search
//
//    def reload(bundle: Bundle) = runAnd(lastBundle = bundle) {
//      getSupportLoaderManager.restartLoader(1, null, this).forceLoad
//    }
//
//    def search = new HtlcWrapLoader {
//      val queryTerm = lastBundle.getString(TEXT) + "*"
//      def getCursor = app.db.select(Payments.searchVirtualSql, queryTerm)
//    }
//
//    def recent = new HtlcWrapLoader {
//      val cutoff = System.currentTimeMillis - 3600 * 24 * 2 * 1000
//      def getCursor = app.db.select(Payments selectRecentSql cutoff)
//    }
//
//    abstract class HtlcWrapLoader extends ReactLoader[HtlcWrap](me) {
//      def createItem(shiftCursor: RichCursor) = PaymentsWrap toHtlcWrap shiftCursor
//      val consume: Vector[HtlcWrap] => Unit = items => println(items.size)
//    }
//  }


//  lazy val recentPayments = new RecentPayments

  // Payment view
//  class LNView(view: View)
//  extends TxViewHolder(view) {
//    def fillView(hw: HtlcWrap) = {
//      println(s"Filling view $hw")
////      val paymentMarking = if (lnp.incoming > 0) sumIn else sumOut
////      val humanSum = paymentMarking format lnp.invoice.amount.bit
////      val time = when(System.currentTimeMillis, lnp.stamp)
////      val image = if (lnp.status == Payments.fail) dead
////        else if (lnp.status == Payments.await) await
////        else conf1
////
////      transactSum setText Html.fromHtml(humanSum)
////      transactWhen setText Html.fromHtml(time)
////      transactCircle setImageResource image
//    }
//  }

  // Temporairly update title and subtitle info
  def notifySubTitle(subtitle: String, infoType: Int) = {
    add(subtitle, infoType).timer.schedule(me del infoType, 25000)
    me runOnUiThread ui
  }

  // Initialize this activity, method is run once
  override def onCreate(savedState: Bundle) =
  {
    super.onCreate(savedState)
    wrap(initToolbar)(me setContentView R.layout.activity_ln)
    add(me getString ln_notify_working, Informer.LNSTATE).ui.run

    //throw new Exception("test")
    //me exitTo classOf[LNStartActivity]

      //wrap(initToolbar)(me setContentView R.layout.activity_ln)
//      add(me getString ln_notify_connecting, Informer.LNSTATE).ui.run
//      app.prefs.edit.putString(AbstractKit.LANDING, AbstractKit.LN).commit
//
//      lnItemsList setAdapter adapter
//      recentPayments.reload(null)
//      timer.schedule(anyToRunnable {
//        println("external update notification happened")
//        app.getContentResolver.notifyChange(app.db sqlPath Payments.table, null)
//      }, 10000, 5000)

      //(new BlindTokensListenerSpec).allTests
//      startListUpdates(adapter, lnItemsList)

//      val a = System.currentTimeMillis
//
//      val wraps = for (n <- 1 to 50000) yield {
//        val preimage = BinaryData(Utils.rand getBytes 32)
//
//        val add = UpdateAddHtlc(100, 100, 10000, 1000, Crypto.sha256(preimage), BinaryData("0x0000"))
//        val htlc = Htlc(n % 2 == 0, add, None, Some(s"Hello $n"))
//
//        HtlcWrap(if (n % 2 == 0) Some(preimage.toString) else None, htlc,
//          if (n % 10 == 0) Payments.waitHidden else Payments.waitVisible)
//      }
//
//      println(System.currentTimeMillis - a)
//
//      app.db txWrap {
//        wraps.foreach(PaymentsWrap.put)
//      }
//
//      println(System.currentTimeMillis - a)

//      val a = System.currentTimeMillis
//      val cursor = app.db.select(Payments.selectRecentSql(0))
//      cursor.moveToFirst()
//      println(PaymentsWrap.toHtlcWrap(RichCursor(cursor)))
//      println(System.currentTimeMillis - a)

      //(new BlindTokensListenerSpec).allTests
  }

  // Menu area

  def react(query: String) = println(query)
  override def onCreateOptionsMenu(menu: Menu) = {
    getMenuInflater.inflate(R.menu.ln_normal_ops, menu)
    setupSearch(menu)
    true
  }

  override def onOptionsItemSelected(m: MenuItem) = runAnd(true) {
    if (m.getItemId == R.id.actionSetBackupServer) new SetBackupServer
    else if (m.getItemId == R.id.actionCloseChannel) closeChannel
  }

  // Data reading

  override def onResume: Unit =
    wrap(super.onResume)(checkTransData)

  def readNdefMessage(msg: Message) = try {
    val asText = readFirstTextNdefMessage(msg)
    app.TransData recordValue asText
    checkTransData

  } catch { case _: Throwable =>
    // Could not process a message
    app toast nfc_error
  }

  def onNfcStateEnabled = none
  def onNfcStateDisabled = none
  def onNfcFeatureNotFound = none
  def onNfcStateChange(ok: Boolean) = none
  def readNonNdefMessage = app toast nfc_error
  def readEmptyNdefMessage = app toast nfc_error

  // Working with transitional data
  def checkTransData = app.TransData.value match {
    case uri: BitcoinURI => me goTo classOf[BtcActivity]
    case adr: Address => me goTo classOf[BtcActivity]

    case invoice: Invoice =>
      me displayInvoice invoice
      app.TransData.value = null

    case unusable =>
      Tools log s"Unusable $unusable"
      app.TransData.value = null
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

  def makePaymentRequest = {
    val humanCap = sumIn format withSign(LNParams.maxHtlcValue)
    val title = getString(ln_receive_max_amount).format(humanCap).html
    val content = getLayoutInflater.inflate(R.layout.frag_input_send_ln, null, false)
    val alert = mkForm(negPosBld(dialog_cancel, dialog_next), title, content)
    val rateManager = new RateManager(content)

    def attempt = rateManager.result match {
      case Failure(_) => app toast dialog_sum_empty
      case Success(ms) => println(ms)
    }

    val ok = alert getButton BUTTON_POSITIVE
    ok setOnClickListener onButtonTap(attempt)
  }

  def goReceive(top: View) = {
    me delayUI makePaymentRequest
    fab close true
  }

  private def displayInvoice(invoice: Invoice) = {
    val humanKey = humanPubkey(invoice.nodeId.toString)
    val info = invoice.message getOrElse getString(ln_no_description)
    val humanSum = humanFiat(sumOut format withSign(invoice.sum), invoice.sum)
    val title = getString(ln_payment_title).format(info, humanKey, humanSum)
    mkForm(negPosBld(dialog_cancel, dialog_pay), title.html, null)
  }

  class SetBackupServer { self =>
    val (view, field) = str2Tuple(LNParams.cloudPrivateKey.publicKey.toString)
    val dialog = mkChoiceDialog(proceed, none, dialog_next, dialog_cancel)
    val alert = mkForm(dialog, getString(ln_backup_key).html, view)
    field setTextIsSelectable true

    def proceed: Unit = rm(alert) {
      val (view1, field1) = generatePasswordPromptView(inpType = textType, txt = ln_backup_ip)
      val dialog = mkChoiceDialog(trySave(field1.getText.toString), none, dialog_ok, dialog_cancel)
      PrivateDataSaver.tryGetObject.foreach(field1 setText _.url)
      mkForm(dialog, me getString ln_backup, view1)
    }

    def trySave(url: String) =
      if (url.isEmpty) PrivateDataSaver.remove
      else if (URLUtil isValidUrl url) self save PrivateData(Nil, url)
      else mkForm(me negBld dialog_ok, null, me getString ln_backup_url_error)

    def save(data: PrivateData) = {
      PrivateDataSaver saveObject data
      LNParams.cloud = LNParams.currentLNCloud
      app toast ln_backup_success
    }

    def onError(error: Throwable): Unit = error.getMessage match {
      case "keynotfound" => mkForm(me negBld dialog_ok, null, me getString ln_backup_key_error)
      case "siginvalid" => mkForm(me negBld dialog_ok, null, me getString ln_backup_sig_error)
      case _ => mkForm(me negBld dialog_ok, null, me getString ln_backup_net_error)
    }
  }

  def closeChannel = passPlus(me getString ln_close) { pass =>
    println(pass)
  }
}