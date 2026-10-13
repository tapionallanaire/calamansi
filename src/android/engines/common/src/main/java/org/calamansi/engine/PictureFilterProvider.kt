package org.calamansi.engine

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process

/** A single owner avoids stale SharedPreferences caches in the private engine processes. */
class PictureFilterProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == Process.myUid()) { "Picture-filter caller is not this app" }
        val context = requireNotNull(context)
        val preferences = context.getSharedPreferences(
            "calamansi_picture_filter",
            Context.MODE_PRIVATE
        )
        val filter = when (method) {
            "get" -> PictureFilter.fromId(preferences.getInt("filter", PictureFilter.OFF.id))

            "set" -> {
                val data = requireNotNull(extras)
                require(data.containsKey("filter")) { "Missing picture filter" }
                val selected = PictureFilter.fromId(data.getInt("filter"))
                check(preferences.edit().putInt("filter", selected.id).commit()) {
                    "Could not save the picture filter"
                }
                context.contentResolver.notifyChange(PictureFilters.uri(context), null)
                selected
            }

            else -> throw IllegalArgumentException("Unknown picture-filter operation: $method")
        }
        return Bundle().apply { putInt("filter", filter.id) }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = throw UnsupportedOperationException("Use picture-filter get/set calls")

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Use picture-filter set")
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("Use picture-filter set")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Select Off to disable the picture filter")
}
