package org.calamansi.engine

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import java.io.IOException

object EngineMetadata {
    const val READ = 1
    const val URI = "uri"
    const val TITLE = "title"
    const val COMPANY = "company"
    const val REGIONS = "regions"
    const val PLATFORM = "platform"
    const val ICON = "icon"
    const val VALID = "valid"
    const val ERROR = "error"
}

abstract class EngineMetadataService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("EngineMetadata").apply { start() }
        messenger = Messenger(
            Handler(worker.looper) { request ->
                if (request.what != EngineMetadata.READ) return@Handler false
                val result = try {
                    val uri = Uri.parse(requireNotNull(request.data.getString(EngineMetadata.URI)))
                    require(uri.scheme == "content" || uri.scheme == "file")
                    readMetadata(uri, request.data.getString(EngineMetadata.PLATFORM))
                        ?: Bundle().apply { putBoolean(EngineMetadata.VALID, false) }
                } catch (e: IOException) {
                    failure(e)
                } catch (e: SecurityException) {
                    failure(e)
                } catch (e: IllegalArgumentException) {
                    failure(e)
                }
                try {
                    request.replyTo?.send(
                        Message.obtain(null, EngineMetadata.READ).apply {
                            arg1 = request.arg1
                            data = result
                        }
                    )
                } catch (_: RemoteException) {
                    // The library scan was cancelled or its process exited.
                }
                true
            }
        )
    }

    protected abstract fun readMetadata(uri: Uri, platformHint: String?): Bundle?

    private fun failure(error: Exception) = Bundle().apply {
        putBoolean(EngineMetadata.VALID, false)
        putString(EngineMetadata.ERROR, error.message)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        worker.quitSafely()
        super.onDestroy()
    }
}
