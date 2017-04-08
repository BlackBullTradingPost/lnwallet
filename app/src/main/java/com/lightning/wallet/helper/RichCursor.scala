package com.lightning.wallet.helper

import android.database.{ContentObserver, Cursor}
import com.lightning.wallet.ln.Tools.{none, runAnd}
import android.support.v4.content.{AsyncTaskLoader, Loader}
import android.support.v4.app.LoaderManager.LoaderCallbacks
import android.content.Context
import java.math.BigInteger
import android.os.Handler
import android.net.Uri
import scala.util.Try


case class RichCursor(c: Cursor) extends Iterable[RichCursor] { me =>
  def vec[T](trans: RichCursor => T) = try map(trans).toVector finally c.close
  def headTry[T](trans: RichCursor => T) = try Try(trans apply head) finally c.close

  // Converting column names info specified values
  def string(stringKey: String) = c.getString(c getColumnIndex stringKey)
  def long(longKey: String) = c.getLong(c getColumnIndex longKey)
  def bigInt(key: String) = new BigInteger(me string key)

  def iterator = new Iterator[RichCursor] {
    def hasNext = c.getPosition < c.getCount - 1
    def next = runAnd(me)(c.moveToNext)
  }
}

// Loading data with side effect
abstract class ReactLoader[T](ct: Context)
extends AsyncTaskLoader[Cursor](ct) { me =>

  def loadInBackground = {
    val cursor: Cursor = me.getCursor
    consume(RichCursor(cursor) vec createItem)
    cursor
  }

  val consume: Vector[T] => Unit
  def createItem(wrap: RichCursor): T
  def getCursor: Cursor
}

// Watch data change events
abstract class ReactCallback(ct: Context)
extends LoaderCallbacks[Cursor] { me =>

  val observeTablePath: Uri
  type LoaderCursor = Loader[Cursor]
  def getObserver(loader: LoaderCursor) = new ContentObserver(new Handler) {
    override def onChange(self: Boolean) = if (null != loader) loader.forceLoad
  }

  def onLoaderReset(loader: LoaderCursor) = none
  def onLoadFinished(loader: LoaderCursor, cursor: Cursor) = {
    cursor.setNotificationUri(ct.getContentResolver, observeTablePath)
    cursor.registerContentObserver(me getObserver loader)
  }
}