// SPDX-License-Identifier: GPL-2.0-or-later
package org.calamansi.dolphin

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import java.io.File
import org.calamansi.engine.EngineFiles
import org.calamansi.engine.NintendoPlatform
import org.dolphinemu.dolphinemu.activities.EmulationActivity
import org.dolphinemu.dolphinemu.model.GameFile

class DolphinActivity : EmulationActivity() {
    private var shutdownComplete = false
    private var bootstrapError: Exception? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Fragment restoration invokes JNI from super.onCreate, before prepareLaunch.
        try {
            val uri = requireNotNull(
                intent.data
                    ?: intent.getStringArrayExtra(EXTRA_SELECTED_GAMES)?.firstOrNull()?.let {
                        if (it.startsWith("content:") ||
                            it.startsWith("file:")
                        ) {
                            Uri.parse(it)
                        } else {
                            Uri.fromFile(File(it))
                        }
                    }
            ) { "A GameCube/Wii launch requires a ROM URI" }
            val platform = NintendoPlatform.valueOf(
                requireNotNull(intent.getStringExtra(EngineFiles.EXTRA_PLATFORM)) {
                    "A GameCube/Wii launch requires its detected platform"
                }
            )
            require(platform == NintendoPlatform.GAMECUBE || platform == NintendoPlatform.WII)
            val title =
                requireNotNull(intent.getStringExtra(Intent.EXTRA_TITLE)) {
                    "A GameCube/Wii launch requires its actual title"
                }
            DolphinRuntime.attach(this, uri, platform, title)
            DolphinRuntime.initialize(this)
        } catch (error: Exception) {
            bootstrapError = error
        }
        super.onCreate(if (bootstrapError == null) savedInstanceState else null)
    }
    override fun prepareLaunch(): Boolean = try {
        bootstrapError?.let { throw it }
        DolphinRuntime.initialize(this)
        val uri =
            intent.data
                ?: intent.getStringArrayExtra(EXTRA_SELECTED_GAMES)?.firstOrNull()?.let {
                    if (it.startsWith("content:") ||
                        it.startsWith("file:")
                    ) {
                        Uri.parse(it)
                    } else {
                        Uri.fromFile(File(it))
                    }
                }
        requireNotNull(uri) { "A GameCube/Wii launch requires a ROM URI" }
        val game =
            requireNotNull(GameFile.parse(nativePath(uri))) {
                "Unsupported or unreadable GameCube/Wii image"
            }
        val platform = when (game.getPlatform()) {
            0, 1 -> NintendoPlatform.GAMECUBE
            2, 3 -> NintendoPlatform.WII
            else -> error("Unsupported GameCube/Wii image")
        }
        val requested = intent.getStringExtra(EngineFiles.EXTRA_PLATFORM)
        require(requested == null || requested == platform.name) {
            "ROM platform does not match launch"
        }
        // Ownership was acquired before native bootstrap/fragment restoration.
        intent.putExtra(EXTRA_SELECTED_GAMES, arrayOf(nativePath(uri)))
        DolphinControllerDefaults.install(this)
        true
    } catch (error: Exception) {
        shutdownComplete = true
        DolphinRuntime.stop()
        showBootError(error)
        false
    }

    override fun onNewIntent(intent: Intent) {
        // HOME resume is a task reorder, never a second boot. The shared provider gates switches.
        super.onNewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        DolphinRuntime.update(EmulationActivity.hasUserPausedEmulation)
    }

    override fun onPause() {
        super.onPause()
        DolphinRuntime.update(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        DolphinRuntime.onDestroyed(this)
    }

    override fun finish() {
        if (shutdownComplete) super.finish() else DolphinRuntime.stop()
    }

    internal fun finishAfterShutdown() {
        shutdownComplete = true
        super.finish()
    }

    internal fun showBootError(error: Exception) {
        AlertDialog.Builder(this).setTitle("GameCube/Wii emulation could not start").setMessage(error.message)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }.show()
    }

    companion object {
        fun nativePath(uri: Uri): String {
            require(uri.scheme == "content" || uri.scheme == "file") {
                "Unsupported GameCube/Wii ROM URI"
            }
            return if (uri.scheme == "file") requireNotNull(uri.path) else uri.toString()
        }
    }
}
