package com.gremier.gkeys.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.gremier.gkeys.R

object OverlayPermissionHelper {

    fun hasOverlayPermission(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /** Android 13+ blocks overlay for sideloaded APKs until the user allows restricted settings. */
    fun needsRestrictedSettingsUnlock(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun showRestrictedSettingsHelp(context: Context, onContinue: () -> Unit) {
        if (context !is Activity) {
            onContinue()
            return
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.overlay_restricted_title)
            .setMessage(R.string.overlay_restricted_message)
            .setPositiveButton(R.string.overlay_restricted_continue) { _, _ -> onContinue() }
            .setNegativeButton(R.string.overlay_restricted_cancel, null)
            .show()
    }

    fun openAppInfo(context: Context) {
        val packageUri = Uri.parse("package:${context.packageName}")
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        Toast.makeText(context, R.string.overlay_open_app_info_toast, Toast.LENGTH_LONG).show()
    }

    fun openOverlayToggle(context: Context) {
        if (hasOverlayPermission(context)) {
            Toast.makeText(context, "Overlay permission already allowed", Toast.LENGTH_SHORT).show()
            return
        }
        val packageUri = Uri.parse("package:${context.packageName}")
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            Toast.makeText(context, R.string.overlay_turn_on_toast, Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            openAppInfo(context)
        }
    }

    /**
     * Guides sideloaded installs through restricted-settings unlock, then overlay toggle.
     * On Android 12 and below, opens the overlay toggle directly.
     */
    fun requestOverlayPermission(context: Context) {
        if (hasOverlayPermission(context)) {
            Toast.makeText(context, "Overlay permission already allowed", Toast.LENGTH_SHORT).show()
            return
        }
        if (needsRestrictedSettingsUnlock()) {
            showRestrictedSettingsHelp(context) { openAppInfo(context) }
        } else {
            openOverlayToggle(context)
        }
    }

    /** Call after the user may have enabled restricted settings — opens the overlay toggle. */
    fun requestOverlayPermissionAfterRestrictedUnlock(context: Context) {
        if (hasOverlayPermission(context)) {
            Toast.makeText(context, "Overlay permission already allowed", Toast.LENGTH_SHORT).show()
            return
        }
        openOverlayToggle(context)
    }
}
