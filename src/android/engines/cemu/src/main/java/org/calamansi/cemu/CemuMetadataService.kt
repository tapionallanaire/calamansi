// SPDX-License-Identifier: MPL-2.0
package org.calamansi.cemu

import android.content.BroadcastReceiver
import android.net.Uri
import android.os.Bundle
import info.cemu.cemu.nativeinterface.NativeGameTitles
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.calamansi.engine.EngineMetadata
import org.calamansi.engine.EngineMetadataService
import org.calamansi.engine.EngineSession
import org.calamansi.engine.NintendoPlatform

class CemuMetadataService : EngineMetadataService() {
    @Volatile private var activeSession: String? = null

    @Volatile private var cancelled = false

    @Volatile private var reading = false

    @Volatile private var destroyed = false
    private var stopReceiver: BroadcastReceiver? = null

    private fun releaseSession(session: String) {
        stopReceiver?.let { applicationContext.unregisterReceiver(it) }
        stopReceiver = null
        EngineSession.end(this, session)
        activeSession = null
    }

    private fun requestStop() {
        cancelled = true
        if (reading) return // The read's finally block owns the drain.
        val owner = activeSession ?: return
        EngineSession.update(this, owner, "stopping")
        reading = true
        CemuRuntime.execute({
            CemuRuntime.closeSession(owner)
            CemuRuntime.stop()
        }) { result ->
            reading = false
            result.onSuccess { releaseSession(owner) }
                .onFailure { EngineSession.update(this, owner, "error") }
        }
    }

    override fun onDestroy() {
        cancelled = true
        destroyed = true
        super.onDestroy()
    }

    override fun readMetadata(uri: Uri, platformHint: String?): Bundle? {
        if (platformHint != null && platformHint != NintendoPlatform.WII_U.name) return null
        CemuRuntime.attach(this)
        CemuRuntime.unsupportedReason()?.let { throw IOException(it) }
        val game = try {
            CemuRuntime.call {
                check(!destroyed) { "Wii U metadata service was destroyed" }
                check(activeSession == null) {
                    "Previous Wii U metadata cleanup still owns the session; retry Stop"
                }
                val session = EngineSession.begin(
                    this,
                    NintendoPlatform.WII_U,
                    uri,
                    "",
                    CemuMetadataService::class.java,
                    EngineSession.KIND_METADATA
                )
                activeSession = session
                cancelled = false
                reading = true
                CemuRuntime.bindSession(session)
                try {
                    stopReceiver =
                        EngineSession.registerStopHandler(
                            applicationContext,
                            session,
                            ::requestStop
                        )
                    findGame(uri) { cancelled }
                } finally {
                    CemuRuntime.closeSession(session)
                    EngineSession.update(this, session, "stopping")
                    // Cleanup failure deliberately retains the durable token.
                    try {
                        CemuRuntime.stop()
                    } catch (error: Throwable) {
                        EngineSession.update(this, session, "error")
                        reading = false
                        throw error
                    }
                    releaseSession(session)
                    reading = false
                }
            }
        } catch (error: ExecutionException) {
            throw IOException("Wii U metadata failed: ${error.cause?.message}", error.cause)
        } catch (error: LinkageError) {
            throw IOException("Wii U native library is unavailable", error)
        }
        if (game == null || !game.isValid()) return null
        return Bundle().apply {
            putBoolean(EngineMetadata.VALID, true)
            putString(EngineMetadata.TITLE, game.name)
            putString(EngineMetadata.PLATFORM, NintendoPlatform.WII_U.name)
            val regions = listOf(
                1 to "Japan",
                2 to "North America",
                4 to "Europe",
                8 to "Australia",
                16 to "China",
                32 to "Korea",
                64 to "Taiwan"
            )
                .filter { game.region and it.first != 0 }.joinToString(", ") { it.second }
            putString(EngineMetadata.REGIONS, regions)
            game.icon?.let { putParcelable(EngineMetadata.ICON, it) }
            // CafeTitleList has no publisher field; do not fabricate one.
        }
    }

    companion object {
        // Executed only on CemuRuntime's serialized backend worker. Metadata is
        // CafeTitleList/GameTitleLoader output, never a filename/extension guess.
        fun findGame(uri: Uri, cancelled: () -> Boolean = { false }): NativeGameTitles.Game? {
            CemuRuntime.initialize()
            val path = CemuRuntime.nativePath(uri)
            val expected = if (path.substringAfterLast('/').endsWith(".rpx", true) &&
                path.substringBeforeLast('/').substringAfterLast('/') == "code"
            ) {
                path.substringBeforeLast('/').substringBeforeLast('/')
            } else {
                path
            }
            val ready = CountDownLatch(1)
            val found = AtomicReference<NativeGameTitles.Game?>()
            NativeGameTitles.setGameTitleLoadedCallback { game ->
                if (game.path?.trimEnd('/') == expected.trimEnd('/') && game.isValid()) {
                    found.set(game)
                    ready.countDown()
                }
            }
            try {
                NativeGameTitles.reloadGameTitles()
                NativeGameTitles.addTitleFromPath(path)
                // Invalid/unsupported trees and standalone RPX without title meta
                // have no real record. Bound the asynchronous callback wait.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (!cancelled() && ready.count > 0 && System.nanoTime() < deadline) {
                    ready.await(100, TimeUnit.MILLISECONDS)
                }
                if (cancelled()) throw IOException("Wii U metadata scan cancelled")
                CemuRuntime.throwNativeError()
                return found.get()
            } finally {
                NativeGameTitles.setGameTitleLoadedCallback(null)
            }
        }
    }
}
