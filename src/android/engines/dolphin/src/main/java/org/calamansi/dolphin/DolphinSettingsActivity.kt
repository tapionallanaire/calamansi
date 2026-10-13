// SPDX-License-Identifier: GPL-2.0-or-later
package org.calamansi.dolphin

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.calamansi.engine.EngineFiles
import org.calamansi.engine.NintendoPlatform
import org.dolphinemu.dolphinemu.NativeLibrary
import org.dolphinemu.dolphinemu.features.settings.ui.MenuTag
import org.dolphinemu.dolphinemu.features.settings.ui.SettingsActivity
import org.dolphinemu.dolphinemu.model.GameFile

/** The upstream native INI editor includes actual per-port mapping, sensor and extension editors. */
class DolphinSettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val requested = intent.getStringExtra(EngineFiles.EXTRA_PLATFORM)
            val platform = requested?.let(NintendoPlatform::valueOf)
                ?: if (intent.getBooleanExtra(
                        "is_wii",
                        true
                    )
                ) {
                    NintendoPlatform.WII
                } else {
                    NintendoPlatform.GAMECUBE
                }
            require(platform == NintendoPlatform.GAMECUBE || platform == NintendoPlatform.WII)
            if (intent.data == null && intent.getStringExtra("game_id").isNullOrEmpty()) {
                // Global Dolphin defaults include both controller trees.
                intent.putExtra("is_wii", true)
            }
            DolphinRuntime.attachSettings(this, platform)
            DolphinRuntime.initialize(this)
            val uri = intent.data
            val game = uri?.let {
                requireNotNull(GameFile.parse(DolphinActivity.nativePath(it))) {
                    "The game image is not readable."
                }
            }
            if (game != null) {
                require(game.getPlatform() in 0..3) { "Unsupported GameCube/Wii settings target" }
                require(NativeLibrary.IsUninitialized()) {
                    "Stop emulation before editing per-game settings."
                }
                require(
                    requested == null ||
                        requested ==
                        if (game.getPlatform() >=
                            2
                        ) {
                            NintendoPlatform.WII.name
                        } else {
                            NintendoPlatform.GAMECUBE.name
                        }
                ) {
                    "ROM platform does not match settings target"
                }
                intent.putExtra("game_id", game.getGameId())
                intent.putExtra(Intent.EXTRA_TITLE, game.getTitle())
                intent.putExtra("revision", game.getRevision())
                intent.putExtra("is_wii", game.getPlatform() >= 2)
            }
            if (!intent.hasExtra("menu_tag")) intent.putExtra("menu_tag", MenuTag.SETTINGS)
            startActivity(
                Intent(this, SettingsActivity::class.java).apply {
                    intent.extras?.let(::putExtras)
                }
            )
            finish()
        } catch (error: Exception) {
            DolphinRuntime.stopSettingsAfterFailure()
            AlertDialog.Builder(this).setTitle("GameCube/Wii settings").setMessage(error.message)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }.show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        DolphinRuntime.onHostDestroyed(this)
    }
}
