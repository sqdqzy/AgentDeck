package com.agentdeck.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes text to a user visible file and offers it for sharing.
 *
 * Two strategies are used depending on the API level:
 *  - Android 10+ writes into the shared Downloads collection through
 *    MediaStore, which needs no permission,
 *  - Android 9 and below write into the app's external files directory and
 *    share the file through a FileProvider URI.
 */
object LogExporter {

    private const val TAG = "LogExporter"

    /** The name of the shared file, without the timestamp suffix. */
    private const val BASE_NAME = "agentdeck-log"

    /**
     * Copies [text] to the clipboard.
     *
     * @return true when the clipboard accepted the text.
     */
    fun copyToClipboard(
        context: Context,
        text: String,
        label: String = "AgentDeck 日志",
    ): Boolean = try {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            false
        } else {
            manager.setPrimaryClip(ClipData.newPlainText(label, text))
            true
        }
    } catch (t: Throwable) {
        Log.e(TAG, "cannot copy the log to the clipboard", t)
        false
    }

    /**
     * Saves [text] as a file and returns a shareable URI, or null on failure.
     */
    fun save(
        context: Context,
        text: String,
        baseName: String = BASE_NAME,
    ): Uri? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, text, baseName)
        } else {
            saveWithFileProvider(context, text, baseName)
        }
    } catch (t: Throwable) {
        Log.e(TAG, "cannot save the log file", t)
        null
    }

    /**
     * Saves into the shared Downloads collection. Requires API 29+.
     */
    private fun saveWithMediaStore(
        context: Context,
        text: String,
        baseName: String,
    ): Uri? {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName(baseName))
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    /**
     * Saves into the app's external files directory and exposes it through a
     * FileProvider. Used on Android 9 and below.
     */
    private fun saveWithFileProvider(
        context: Context,
        text: String,
        baseName: String,
    ): Uri? {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, fileName(baseName))
        file.writeText(text, Charsets.UTF_8)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * Opens the system share sheet for the given URI.
     */
    fun share(
        context: Context,
        uri: Uri,
        mimeType: String = "text/plain",
    ): Boolean = try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "分享日志")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
        true
    } catch (t: Throwable) {
        Log.e(TAG, "cannot share the log file", t)
        false
    }

    /** Builds the file name, including a timestamp so exports never clash. */
    private fun fileName(baseName: String): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.getDefault())
            .format(java.util.Date())
        return "$baseName-$stamp.log"
    }
}
