package com.lightning.walletapp

import com.lightning.walletapp.ln._
import com.lightning.walletapp.Utils._
import com.lightning.walletapp.R.string._
import com.lightning.walletapp.lnutils.RelayNode._
import com.lightning.walletapp.lnutils.JsonHttpUtils._
import com.lightning.walletapp.lnutils.ImplicitConversions._
import com.lightning.walletapp.lnutils.ImplicitJsonFormats._

import android.view.{View, ViewGroup}
import android.widget.{BaseAdapter, LinearLayout, ListView, TextView}
import com.lightning.walletapp.WalletStatusActivity.allItems
import com.lightning.walletapp.lnutils.ChannelBalances
import com.lightning.walletapp.ln.Tools.runAnd
import android.support.v7.widget.Toolbar
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.MilliSatoshi
import android.os.Bundle


object WalletStatusActivity { me =>
  def updateItems(cbs: ChannelBalances): Unit = {
    val me2JointMaxSendable = relayPeerReports.map(_.estimateFinalCanSend).reduceOption(_ max _) getOrElse 0L
    val perPeerMaxSendable = cbs.localBalances.sortBy(- _.withoutMaxFee).groupBy(_.peerNodeId).mapValues(_.head)

    val updatedMap = for {
      jointPeersNodeKey \ title <- mapping
      cbiOpt = perPeerMaxSendable get jointPeersNodeKey
      max = cbiOpt.map(_.withoutMaxFee) getOrElse 0L
      min = math.min(me2JointMaxSendable, max)
    } yield title -> MilliSatoshi(min)
    allItems = updatedMap.toList
  }

  val mapping = Map (
    PublicKey("03933884aaf1d6b108397e5efe5c86bcf2d8ca8d2f700eda99db9214fc2712b134") -> "starblocks.acinq.co",
    PublicKey("02a68237add204623021d09b0334c4992c132eb3c9dcfcb8f3cf8a57386775538e") -> "testnet.yalls.org",
    PublicKey("03c856d2dbec7454c48f311031f06bb99e3ca1ab15a9b9b35de14e139aa663b463") -> "htlc.me"
  )

  val nothing = MilliSatoshi(amount = 0L)
  var allItems = mapping.values.toList.map(_ -> nothing)
}

class WalletStatusActivity extends TimerActivity with HumanTimeDisplay { me =>
  lazy val jointNodeInfo = findViewById(R.id.jointNodeInfo).asInstanceOf[LinearLayout]
  lazy val itemsList = findViewById(R.id.itemsList).asInstanceOf[ListView]
  lazy val host = me

  val adapter = new BaseAdapter {
    def getItem(position: Int) = allItems(position)
    def getItemId(position: Int) = position
    def getCount = allItems.size

    def getView(itemPosition: Int, savedView: View, parent: ViewGroup) = {
      val view = host.getLayoutInflater.inflate(R.layout.frag_line_double, null)
      val maxSendField = view.findViewById(R.id.rightSideLine).asInstanceOf[TextView]
      val nameField = view.findViewById(R.id.leftSideLine).asInstanceOf[TextView]

      val nameString \ maxSendMsat = getItem(itemPosition)
      maxSendField setText denom.withSign(maxSendMsat).html
      nameField setText nameString
      view
    }
  }

  val killOpt =
    for (rep <- relayPeerReports.headOption)
      yield makeWebSocket(rep.chan.data.announce) { raw =>
        WalletStatusActivity updateItems to[ChannelBalances](raw)
        UITask(adapter.notifyDataSetChanged).run
      }

  override def onDestroy = {
    // Disconnect socket on exiting
    for (off <- killOpt) off.run
    super.onDestroy
  }

  def INIT(s: Bundle) = if (app.isAlive) {
    me setContentView R.layout.activity_wallet_status
    me initToolbar findViewById(R.id.toolbar).asInstanceOf[Toolbar]
    getSupportActionBar setTitle joint_title

    itemsList setVisibility viewMap(relayPeerReports.nonEmpty)
    jointNodeInfo setVisibility viewMap(relayPeerReports.isEmpty)
    runAnd(itemsList setAdapter adapter)(adapter.notifyDataSetChanged)
  } else me exitTo classOf[MainActivity]

  def openChannel(top: View) =
    me exitTo classOf[LNStartActivity]
}
