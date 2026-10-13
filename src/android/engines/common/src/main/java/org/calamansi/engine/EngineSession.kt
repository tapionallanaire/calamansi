package org.calamansi.engine

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat

object EngineSession {
    const val KIND_GAME = "game"
    const val KIND_SETTINGS = "settings"
    const val KIND_METADATA = "metadata"

    fun uri(context: Context): Uri = Uri.parse("content://${context.packageName}.engine-session")

    fun begin(
        context: Context,
        platform: NintendoPlatform,
        uri: Uri?,
        title: String,
        activityClass: Class<*>,
        kind: String = KIND_GAME
    ): String {
        val args = Bundle().apply {
            putString("platform", platform.name)
            putString("path", uri?.toString().orEmpty())
            putString("kind", kind)
            putString("title", title)
            putString("activity", activityClass.name)
            putString("process", Application.getProcessName())
        }
        return requireNotNull(context.contentResolver.call(uri(context), "begin", null, args))
            .getString("token") ?: error("Engine session registration failed")
    }

    fun snapshot(context: Context): Bundle? =
        context.contentResolver.call(uri(context), "snapshot", null, null)

    fun update(context: Context, token: String, state: String) {
        context.contentResolver.call(
            uri(context),
            "update",
            token,
            Bundle().apply { putString("state", state) }
        )
    }

    fun end(context: Context, token: String) {
        context.contentResolver.call(uri(context), "end", token, null)
    }

    fun requestStop(context: Context, token: String) {
        context.sendBroadcast(
            Intent("${context.packageName}.engine.stop").apply {
                setPackage(context.packageName)
                putExtra("token", token)
            }
        )
    }

    fun registerStopHandler(context: Context, token: String, stop: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra("token") == token) stop()
            }
        }
        // Native shutdown can outlive its Activity or Service; retain the stop receiver until joined.
        ContextCompat.registerReceiver(
            context.applicationContext,
            receiver,
            IntentFilter("${context.packageName}.engine.stop"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        return receiver
    }
}
