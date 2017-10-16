package com.lightning.wallet

import android.widget._
import collection.JavaConverters._
import android.widget.DatePicker._
import com.lightning.wallet.R.string._
import com.hootsuite.nachos.terminator.ChipTerminatorHandler._
import org.bitcoinj.wallet.{DeterministicSeed, KeyChainGroup, Wallet}
import com.lightning.wallet.Utils.{app, isMnemonicCorrect}
import com.lightning.wallet.ln.Tools.{none, runAnd, wrap}
import org.bitcoinj.core.{BlockChain, PeerGroup}
import android.view.{View, ViewGroup}

import com.hootsuite.nachos.NachoTextView
import org.bitcoinj.store.SPVBlockStore
import com.lightning.wallet.ln.LNParams
import org.bitcoinj.crypto.MnemonicCode
import android.text.TextUtils
import java.util.Calendar
import android.os.Bundle


class WhenPicker(host: TimerActivity, start: Long)
extends DatePicker(host) with OnDateChangedListener { me =>
  def pure = runAnd(me)(try getParent.asInstanceOf[ViewGroup] removeView me catch none)
  def human = java.text.DateFormat getDateInstance java.text.DateFormat.MEDIUM format cal.getTime
  def onDateChanged(view: DatePicker, year: Int, mon: Int, dt: Int) = cal.set(year, mon, dt)
  init(cal get Calendar.YEAR, cal get Calendar.MONTH, cal get Calendar.DATE, me)

  lazy val cal = {
    val calendar = Calendar.getInstance
    calendar setTimeInMillis start
    calendar
  }
}

class WalletRestoreActivity extends TimerActivity with ViewSwitch { me =>
  lazy val views = findViewById(R.id.restoreInfo) :: findViewById(R.id.restoreProgress) :: Nil
  lazy val restoreCode = findViewById(R.id.restoreCode).asInstanceOf[NachoTextView]
  lazy val restoreWallet = findViewById(R.id.restoreWallet).asInstanceOf[Button]
  lazy val restoreWhen = findViewById(R.id.restoreWhen).asInstanceOf[Button]
  lazy val password = findViewById(R.id.restorePass).asInstanceOf[EditText]
  lazy val datePicker = new WhenPicker(me, 1488326400L * 1000)

  def getMnemonicText = TextUtils.join("\u0020", restoreCode.getChipValues)
  override def onBackPressed = wrap(super.onBackPressed)(app.kit.stopAsync)

  // Initialize this activity, method is run once
  override def onCreate(savedState: Bundle) =
  {
    super.onCreate(savedState)
    setContentView(R.layout.activity_restore)

    val changeListener = new TextChangedWatcher {
      override def onTextChanged(s: CharSequence, x: Int, y: Int, z: Int) =
        // Both password and mnemonic should be valid in order to proceed
        checkValidity

      private def checkValidity = {
        val mnemonicIsOk = isMnemonicCorrect(getMnemonicText)
        val passIsOk = password.getText.length >= 6

        restoreWallet.setEnabled(mnemonicIsOk & passIsOk)
        if (!mnemonicIsOk) restoreWallet setText restore_mnemonic_wrong
        else if (!passIsOk) restoreWallet setText password_too_short
        else restoreWallet setText restore_wallet
      }
    }

    val allowed = MnemonicCode.INSTANCE.getWordList
    val lineStyle = android.R.layout.simple_list_item_1
    val adapter = new ArrayAdapter(me, lineStyle, allowed)

    if (app.TransData.value != null) {
      // This should be an unencrypted mnemonic string
      val chips = app.TransData.value.toString split "\\s+"
      restoreCode setText chips.toList.asJava
      app.TransData.value = null
    }

    restoreWhen setText datePicker.human
    password addTextChangedListener changeListener
    restoreCode addTextChangedListener changeListener
    restoreCode.addChipTerminator(' ', BEHAVIOR_CHIPIFY_TO_TERMINATOR)
    restoreCode.addChipTerminator(',', BEHAVIOR_CHIPIFY_TO_TERMINATOR)
    restoreCode.addChipTerminator('\n', BEHAVIOR_CHIPIFY_TO_TERMINATOR)
    restoreCode setDropDownBackgroundResource R.color.button_material_dark
    restoreCode setAdapter adapter
  }

  def doRecoverWallet =
    app.kit = new app.WalletKit {
      setVis(View.GONE, View.VISIBLE)
      startAsync

      def startUp = {
        val whenTime = datePicker.cal.getTimeInMillis / 1000
        val seed = new DeterministicSeed(getMnemonicText, null, "", whenTime)
        val keyChainGroup = new KeyChainGroup(app.params, seed, true)
        val (crypter, key) = app newCrypter password.getText
        LNParams setup seed.getSeedBytes

        // Recreate encrypted wallet and use checkpoints
        store = new SPVBlockStore(app.params, app.chainFile)
        wallet = new Wallet(app.params, keyChainGroup)
        wallet.encrypt(crypter, key)
        useCheckPoints(whenTime)

        // Must be initialized after checkpoints
        blockChain = new BlockChain(app.params, wallet, store)
        peerGroup = new PeerGroup(app.params, blockChain)

        if (app.isAlive) {
          setupAndStartDownload
          wallet saveToFile app.walletFile
          exitTo apply classOf[BtcActivity]
        }
      }
    }

  def recWallet(v: View) = hideKeys(doRecoverWallet)
  def setWhen(v: View) = mkForm(mkChoiceDialog(restoreWhen setText datePicker.human,
    none, dialog_ok, dialog_cancel), title = null, content = datePicker.pure)
}