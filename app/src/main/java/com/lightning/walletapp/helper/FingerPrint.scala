package com.lightning.walletapp.helper

import com.lightning.walletapp.R.string._
import co.infinum.goldfinger.{Goldfinger, Error => GFError}
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.Manifest.permission.USE_FINGERPRINT
import android.support.v4.content.ContextCompat
import com.lightning.walletapp.ln.Tools.runAnd
import android.support.v4.app.ActivityCompat
import com.lightning.walletapp.AbstractKit
import com.lightning.walletapp.Utils.app
import android.app.Activity


object FingerPrint {
  final val CODE = 105
  def isEnabled = app.prefs.getBoolean(AbstractKit.FINGERPRINT_ENABLED, false)
  def askPermission(host: Activity) = ActivityCompat.requestPermissions(host, Array(USE_FINGERPRINT), CODE)
  def isPermissionGranted = ContextCompat.checkSelfPermission(app, USE_FINGERPRINT) == PERMISSION_GRANTED
  def isOperational(gf: Goldfinger) = isEnabled && isPermissionGranted && gf.hasEnrolledFingerprint

  def informUser(e: GFError) = e match {
    case GFError.INITIALIZATION_FAILED => app toast fp_err_failure
    case GFError.FAILURE => app toast fp_err_failure
    case GFError.LOCKOUT => app toast fp_err_failure
    case GFError.TIMEOUT => app toast fp_err_timeout
    case GFError.DIRTY => app toast fp_err_dirty
    case _ => app toast fp_err_try_again
  }
}