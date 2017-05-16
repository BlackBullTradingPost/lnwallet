package com.lightning.wallet

import com.lightning.wallet.ln.Tools._
import com.lightning.wallet.R.string._

import android.view.{Menu, View, ViewGroup}
import android.widget.{BaseAdapter, ListView, TextView}
import com.lightning.wallet.lncloud.{FailoverLNCloud, LNCloudPrivateSaver}
import com.lightning.wallet.ln.wire.LightningMessageCodecs.AnnounceChansNum
import com.lightning.wallet.lncloud.ImplicitConversions.string2Ops
import com.lightning.wallet.ln.wire.NodeAnnouncement
import com.lightning.wallet.helper.ThrottledWork
import com.lightning.wallet.Utils.humanPubkey
import com.lightning.wallet.ln.LNParams
import com.lightning.wallet.Utils.app
import android.os.Bundle


class LNStartActivity extends ToolbarActivity with ViewSwitch with SearchBar { me =>
  lazy val chansNumber = getResources getStringArray R.array.ln_ops_start_node_channels
  lazy val lnStartNodesList = findViewById(R.id.lnStartNodesList).asInstanceOf[ListView]
  lazy val lnStartNodeInfo = findViewById(R.id.lnStartNodeInfo).asInstanceOf[TextView]
  lazy val views = lnStartNodesList :: lnStartNodeInfo :: Nil
  lazy val nodeView = getString(ln_ops_start_node_view)
  private[this] val adapter = new NodesAdapter

  private[this] val privateCloudTry =
    for (data <- LNCloudPrivateSaver.tryGetObject)
      yield new FailoverLNCloud(LNParams.lnCloud, data.url)

  type AnnounceChansNumVec = Vector[AnnounceChansNum]
  private[this] val worker = new ThrottledWork[AnnounceChansNumVec] {
    def work(searchInput: String) = privateCloudTry getOrElse LNParams.lnCloud findNodes searchInput
    def process(res: AnnounceChansNumVec) = wrap(me runOnUiThread adapter.notifyDataSetChanged)(adapter.nodes = res)
  }

  def react(query: String) = worker onNewQuery query
  def notifySubTitle(sub: String, infoType: Int) =
    me runOnUiThread add(sub, infoType).ui

  def mkNodeView(info: AnnounceChansNum) = {
    val (announcement: NodeAnnouncement, chansNum) = info
    val humanId: String = humanPubkey(announcement.nodeId.toString)
    val humanChans: String = app.plurOrZero(chansNumber, chansNum)
    nodeView.format(announcement.alias, humanChans, humanId).html
  }

  // Adapter for btc tx list
  class NodesAdapter extends BaseAdapter {
    def getView(nodePosition: Int, cv: View, parent: ViewGroup) = {
      val view = getLayoutInflater.inflate(R.layout.frag_single_line, null)
      val textLine = view.findViewById(R.id.textLine).asInstanceOf[TextView]
      textLine setText mkNodeView(nodes apply nodePosition)
      view
    }

    var nodes = Vector.empty[AnnounceChansNum]
    def getItem(position: Int) = nodes(position)
    def getItemId(position: Int) = position
    def getCount = nodes.size
  }

  // Initialize this activity, method is run once
  override def onCreate(savedState: Bundle) =
  {
    super.onCreate(savedState)
    wrap(initToolbar)(me setContentView R.layout.activity_ln_start)
    notifySubTitle(me getString ln_select_peer, Informer.LNSTATE)
    getSupportActionBar setTitle ln_ops_start
    lnStartNodesList setAdapter adapter
    worker onNewQuery new String
  }

  override def onCreateOptionsMenu(menu: Menu) = {
    getMenuInflater.inflate(R.menu.ln_start_ops, menu)
    setupSearch(menu)
    true
  }
}
