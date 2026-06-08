package com.gremier.gkeys.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

object OverlayPermissionHelper {

    fun hasOverlayPermission(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    /** Opens the system screen to allow display-over-other-apps for this app. */
    fun requestOverlayPermission(context: Context) {
        if (hasOverlayPermission(context)) {
            Toast.makeText(context, "Overlay permission already allowed", Toast.LENGTH_SHORT).show()
            return
        }
        val packageUri = Uri.parse("package:${context.packageName}")
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
            Toast.makeText(
                context,
                "Turn on “Allow display over other apps” for Gkeys",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                Toast.makeText(
                    context,
                    "Open Advanced → Display over other apps → Gkeys → Allow",
                    Toast.LENGTH_LONG
                ).show()
            } catch (_: Exception) {
                Toast.makeText(
                    context,
                    "Open Settings → Apps → Gkeys → Display over other apps",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
