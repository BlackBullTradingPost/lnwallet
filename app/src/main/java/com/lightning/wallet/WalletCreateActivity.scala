package com.lightning.wallet

import R.string._
import com.lightning.wallet.Utils.{app, wrap}
import org.bitcoinj.core.{BlockChain, PeerGroup}
import org.bitcoinj.wallet.{DeterministicSeed, Wallet}
import android.widget.{Button, EditText, LinearLayout, TextView}
import android.view.WindowManager.LayoutParams
import android.text.method.LinkMovementMethod
import org.bitcoinj.store.SPVBlockStore
import android.text.TextUtils
import android.os.Bundle
import android.view.View
import com.lightning.wallet.ln.LNParams
import com.lightning.wallet.lncloud.RatesSaver


object Mnemonic {
  def text(seed: DeterministicSeed) = TextUtils.join("\u0020", seed.getMnemonicCode)
  def decrypt(pass: String) = app.kit.wallet.getKeyCrypter match { case scrypt =>
    app.kit.wallet.getKeyChainSeed.decrypt(scrypt, pass, scrypt deriveKey pass)
  }
}

class WalletCreateActivity extends TimerActivity with ViewSwitch { me =>
  lazy val createWallet = findViewById(R.id.createWallet).asInstanceOf[Button]
  lazy val createPass = findViewById(R.id.createPass).asInstanceOf[EditText]

  // After wallet is created we emphasize an importance of mnemonic
  lazy val walletReady = findViewById(R.id.walletReady).asInstanceOf[TextView]
  lazy val mnemonicText = findViewById(R.id.mnemonicText).asInstanceOf[TextView]
  lazy val openWallet = findViewById(R.id.openWallet).asInstanceOf[Button]
  lazy val info = findViewById(R.id.mnemonicInfo).asInstanceOf[TextView]

  lazy val views =
    findViewById(R.id.createInfo) ::
    findViewById(R.id.createProgress) ::
    findViewById(R.id.createDone) :: Nil

  // Initialize this activity, method is run once
  override def onCreate(savedState: Bundle) =
  {
    super.onCreate(savedState)
    setContentView(R.layout.activity_create)
    info setMovementMethod LinkMovementMethod.getInstance
    getWindow.setFlags(LayoutParams.FLAG_SECURE, LayoutParams.FLAG_SECURE)

    createPass addTextChangedListener new TextChangedWatcher {
      override def onTextChanged(s: CharSequence, st: Int, n: Int, af: Int) = {
        val buttonMessage = if (s.length >= 6) wallet_create else password_too_short
        createWallet setEnabled s.length >= 6
        createWallet setText buttonMessage
      }
    }
  }

  def makeNewWallet =
    app.kit = new app.WalletKit {
      setVis(View.GONE, View.VISIBLE, View.GONE)
      startAsync

      override def startUp = {
        // Get seed before encryption
        wallet = new Wallet(app.params)
        val seed = wallet.getKeyChainSeed

        // Encrypt wallet and use checkpoints
        store = new SPVBlockStore(app.params, app.chainFile)
        useCheckPoints(wallet.getEarliestKeyCreationTime)
        app.kit encryptWallet createPass.getText

        // These should be initialized after checkpoints
        blockChain = new BlockChain(app.params, wallet, store)
        peerGroup = new PeerGroup(app.params, blockChain)

        if (app.isAlive) {
          setupAndStartDownload
          wallet saveToFile app.walletFile
          me runOnUiThread mnemonicText.setText(Mnemonic text seed)
          me runOnUiThread setVis(View.GONE, View.GONE, View.VISIBLE)
          LNParams setSeed seed
          RatesSaver.process
        }
      }
    }

  override def onBackPressed = wrap(super.onBackPressed)(app.kit.stopAsync)
  def goBtcWallet(view: View) = me exitTo classOf[BtcActivity]
  def newWallet(view: View) = hideKeys(makeNewWallet)

  def revealMnemonic(show: View) = {
    walletReady setText sets_noscreen
    mnemonicText setVisibility View.VISIBLE
    openWallet setVisibility View.VISIBLE
    show setVisibility View.GONE
  }
}