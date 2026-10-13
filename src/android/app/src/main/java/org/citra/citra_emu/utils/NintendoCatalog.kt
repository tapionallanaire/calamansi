package org.citra.citra_emu.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import androidx.core.os.BundleCompat
import java.io.Closeable
import java.io.IOException
import java.util.EnumMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.calamansi.cemu.CemuMetadataService
import org.calamansi.dolphin.DolphinMetadataService
import org.calamansi.engine.EngineId
import org.calamansi.engine.EngineMetadata
import org.calamansi.engine.NintendoPlatform
import org.calamansi.nds.NdsMetadataService
import org.calamansi.retroarch.RetroArchMetadataService
import org.citra.citra_emu.model.Game

class NintendoCatalog(private val context: Context) : Closeable {
    private val workers = EnumMap<EngineId, Worker>(EngineId::class.java)
    private val replies = HandlerThread("NintendoCatalogReplies").apply { start() }

    fun accepts(uri: Uri): Boolean = candidates(uri).isNotEmpty()

    fun read(uri: Uri): Game? {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ROM metadata must be read off the UI thread"
        }
        for (engine in candidates(uri)) {
            val worker = workers.getOrPut(engine) { Worker(engine) }
            val data = worker.read(uri)
            if (!data.getBoolean(EngineMetadata.VALID)) continue
            val platform = NintendoPlatform.valueOf(
                requireNotNull(data.getString(EngineMetadata.PLATFORM))
            )
            require(platform.engine == engine) { "Metadata platform does not match its engine" }
            val icon = BundleCompat.getParcelable(data, EngineMetadata.ICON, Bitmap::class.java)
            val pixels = icon?.let { bitmap ->
                IntArray(bitmap.width * bitmap.height).also {
                    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                }
            }
            val filename = FileUtil.getFilename(uri)
            return Game(
                valid = true,
                title = data.getString(EngineMetadata.TITLE)?.takeIf {
                    it.isNotBlank()
                } ?: filename,
                description = uri.toString(),
                path = uri.toString(),
                company = data.getString(EngineMetadata.COMPANY).orEmpty(),
                regions = data.getString(EngineMetadata.REGIONS).orEmpty(),
                icon = pixels,
                fileType = platform.label,
                filename = filename,
                platform = platform,
                iconWidth = icon?.width ?: 48,
                iconHeight = icon?.height ?: 48,
                iconArgb = true
            )
        }
        return null
    }

    private fun candidates(uri: Uri): List<EngineId> =
        when (FileUtil.getExtension(uri).lowercase()) {
            "nds" -> listOf(EngineId.NDS)
            "gb", "gbc", "gba", "nes", "sfc", "smc", "zip" -> listOf(EngineId.RETROARCH)
            "iso", "gcm", "rvz", "gcz", "ciso", "wbfs", "wad" -> listOf(EngineId.DOLPHIN)
            "wud", "wux", "wua", "rpx" -> listOf(EngineId.CEMU)
            else -> emptyList()
        }

    override fun close() {
        workers.values.forEach(Worker::close)
        replies.quitSafely()
    }

    private inner class Worker(engine: EngineId) :
        ServiceConnection,
        Closeable {
        private val connected = CountDownLatch(1)
        private val responses = ArrayBlockingQueue<Bundle>(1)

        @Volatile private var remote: Messenger? = null

        @Volatile private var requestId = 0
        private val receiver = Messenger(
            Handler(replies.looper) { message ->
                if (message.what == EngineMetadata.READ && message.arg1 == requestId) {
                    responses.offer(message.data)
                }
                true
            }
        )
        private val bound: Boolean

        init {
            val service = when (engine) {
                EngineId.NDS -> NdsMetadataService::class.java
                EngineId.RETROARCH -> RetroArchMetadataService::class.java
                EngineId.DOLPHIN -> DolphinMetadataService::class.java
                EngineId.CEMU -> CemuMetadataService::class.java
            }
            bound = context.bindService(Intent(context, service), this, Context.BIND_AUTO_CREATE)
            if (!bound) throw IOException("Cannot bind ROM metadata service")
        }

        fun read(uri: Uri): Bundle {
            if (!connected.await(
                    30,
                    TimeUnit.SECONDS
                )
            ) {
                throw IOException("Metadata service did not connect")
            }
            val endpoint = remote ?: throw IOException("Metadata service disconnected")
            responses.clear()
            requestId++
            endpoint.send(
                Message.obtain(null, EngineMetadata.READ).apply {
                    arg1 = requestId
                    replyTo = receiver
                    data = Bundle().apply { putString(EngineMetadata.URI, uri.toString()) }
                }
            )
            val result = responses.poll(30, TimeUnit.SECONDS)
                ?: throw IOException("Metadata service did not read ${FileUtil.getFilename(uri)}")
            result.getString(EngineMetadata.ERROR)?.let { throw IOException(it) }
            return result
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            remote = Messenger(binder)
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            remote = null
        }

        override fun onBindingDied(name: ComponentName) {
            remote = null
            connected.countDown()
        }

        override fun onNullBinding(name: ComponentName) {
            connected.countDown()
        }

        override fun close() {
            if (bound) context.unbindService(this)
        }
    }
}
