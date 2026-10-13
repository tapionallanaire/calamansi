// Adapted from melonDS-android 2.0.1; GPL-3.0-or-later. Calamansi: isolated file/content URI bridge.
package me.magnum.melonds.common

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

class UriFileHandler(private val context: Context) {
    fun open(uriString: String, mode: String): Int = try {
        val uri = Uri.parse(uriString)
        // Stable JNI supplies Android descriptor modes, not stdio fopen modes.
        val androidMode = when (mode) {
            "r", "w", "wt", "wa", "rw", "rwt" -> mode

            "rwa" -> "rw"

            // Native fdopen(a+) enables append on the detached descriptor.
            else -> throw IllegalArgumentException("Unsupported native open mode: $mode")
        }
        val descriptor = when (uri.scheme) {
            "file" -> ParcelFileDescriptor.open(
                File(requireNotNull(uri.path)),
                ParcelFileDescriptor.parseMode(androidMode)
            )

            "content" -> context.contentResolver.openFileDescriptor(uri, androidMode)

            else -> throw IllegalArgumentException("Unsupported ROM URI scheme: ${uri.scheme}")
        }
        descriptor?.detachFd() ?: -1
    } catch (e: Exception) {
        Log.e("CalamansiNDS", "Cannot open $uriString ($mode)", e)
        -1
    }
}
