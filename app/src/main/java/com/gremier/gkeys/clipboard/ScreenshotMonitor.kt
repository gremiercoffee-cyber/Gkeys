package com.gremier.gkeys.clipboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Watches MediaStore for new screenshots and reports their content URIs.
 */
class ScreenshotMonitor(
    private val context: Context,
    private val onScreenshot: (Uri) -> Unit
) {
    companion object {
        private const val TAG = "ScreenshotMonitor"
        private const val LOOKBACK_MS = 5 * 60 * 1000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastSeenId = -1L
    private var running = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scanRecentScreenshots()
        }
    }

    fun start() {
        if (running || !hasMediaPermission()) return
        running = true
        try {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
            scanRecentScreenshots()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start screenshot monitor", e)
            running = false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            context.contentResolver.unregisterContentObserver(observer)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to stop screenshot monitor", e)
        }
    }

    fun hasMediaPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun scanRecentScreenshots() {
        if (!hasMediaPermission()) return
        try {
            val cutoffSec = (System.currentTimeMillis() - LOOKBACK_MS) / 1000
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DATA
            )
            val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
            val args = arrayOf(cutoffSec.toString())
            val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            context.contentResolver.query(collection, projection, selection, args, sort)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val relCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    if (id <= lastSeenId) continue
                    val name = cursor.getString(nameCol).orEmpty()
                    val relative = if (relCol >= 0) cursor.getString(relCol).orEmpty() else ""
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol).orEmpty() else ""
                    if (!isScreenshot(name, relative, dataPath)) continue

                    lastSeenId = id
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    onScreenshot(uri)
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Screenshot scan failed", e)
        }
    }

    private fun isScreenshot(displayName: String, relativePath: String, dataPath: String): Boolean {
        val combined = "$relativePath/$displayName/$dataPath".lowercase()
        return combined.contains("screenshot") ||
            combined.contains("screen_shot") ||
            combined.contains("screencapture")
    }
}
