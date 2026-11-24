package org.takgram.app

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import org.takgram.core.ConnectionPackage
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands ATAK the connection data package. It serves exactly one read-only file, and only to an app
 * the user has just been sent to with a one-time read grant.
 */
class PackageProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun file(): File = File(context!!.cacheDir, ConnectionPackage.FILE_NAME)

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (BuildConfig.DEBUG) Log.d(Tg.TAG, "provider openFile $uri mode=$mode")
        if (uri.lastPathSegment != ConnectionPackage.FILE_NAME || mode != "r") throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file(), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        if (BuildConfig.DEBUG) Log.d(Tg.TAG, "provider query $uri projection=${projection?.joinToString()}")
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map { c ->
            when (c) {
                OpenableColumns.DISPLAY_NAME -> ConnectionPackage.FILE_NAME
                OpenableColumns.SIZE -> file().length()
                else -> null
            }
        }
        return MatrixCursor(cols).apply { addRow(row) }
    }

    override fun getType(uri: Uri): String = "application/zip"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val AUTHORITY = "org.takgram.app.files"
        private const val ATAK_CIV = "com.atakmap.app.civ"
        private const val ATAK_IMPORTER = "com.atakmap.android.maps.ImportFileActivity"

        /**
         * Writes the package and asks ATAK to import it.
         * @return false if no app on the phone accepted it (ATAK is not installed).
         */
        fun sendToAtak(ctx: Context): Boolean {
            File(ctx.cacheDir, ConnectionPackage.FILE_NAME).writeBytes(ConnectionPackage.build())
            val uri = Uri.parse("content://$AUTHORITY/${ConnectionPackage.FILE_NAME}")
            // ATAK-CIV 5.8's importer acts on ACTION_SEND with a stream; it ignores ACTION_VIEW.
            val view = Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            view.clipData = ClipData.newRawUri(ConnectionPackage.FILE_NAME, uri) // carries the read grant
            // Straight to ATAK-CIV's importer when it is installed; otherwise let the user pick the TAK app.
            for (attempt in listOf(Intent(view).setClassName(ATAK_CIV, ATAK_IMPORTER), Intent.createChooser(view, "Open with ATAK"))) {
                try {
                    ctx.startActivity(attempt)
                    return true
                } catch (e: Exception) {
                    // try the next way
                }
            }
            return false
        }
    }
}
