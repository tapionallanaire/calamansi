package org.calamansi.retroarch

import android.content.Intent
import android.os.Bundle
import java.io.File
import org.calamansi.engine.EngineFiles
import org.calamansi.engine.EngineId
import org.calamansi.engine.NintendoPlatform
import org.calamansi.settings.EngineSettingsActivity
import org.citra.citra_emu.R

class RetroArchSettingsActivity : EngineSettingsActivity() {
    private lateinit var platform: NintendoPlatform
    private lateinit var file: File
    private val platforms = NintendoPlatform.entries.filter { it.engine == EngineId.RETROARCH }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        platform = platforms.firstOrNull {
            it.name == intent.getStringExtra(EngineFiles.EXTRA_PLATFORM)
        } ?: NintendoPlatform.GB
        configureProfile()
        category(
            R.string.preferences_general,
            R.string.settings_general_summary,
            R.drawable.ic_general_settings
        ) {
            if (intent.data == null) {
                choiceRow(
                    getString(R.string.settings_console_profile),
                    platforms.map { it.label },
                    platforms.indexOf(platform)
                ) {
                    platform = platforms[it]
                    intent.putExtra(EngineFiles.EXTRA_PLATFORM, platform.name)
                    configureProfile()
                    showCategories()
                }
            }
            toggle("Save state on exit", "savestate_auto_save", true)
            toggle("Load state on start", "savestate_auto_load", false)
            sliderRow(
                "Save RAM interval (seconds; 0 disables periodic flush)",
                RetroConfig.read(file)["autosave_interval"]?.toIntOrNull() ?: 10,
                60
            ) {
                RetroConfig.set(file, "autosave_interval", it.toString())
            }
        }
        category(
            R.string.preferences_graphics,
            R.string.settings_graphics_summary,
            R.drawable.ic_graphics
        ) {
            toggle("Vertical sync", "video_vsync", true)
        }
        category(
            R.string.preferences_audio,
            R.string.settings_audio_summary,
            R.drawable.ic_audio
        ) {
            toggle("Audio output", "audio_enable", true)
        }
        category(
            R.string.settings_controls,
            R.string.settings_controls_summary,
            R.drawable.ic_controls_settings
        ) {
            toggle("Controller autodetection", "input_autodetect_enable", true)
            note(
                "Press Start + Select in a running game to open the native Quick Menu for controller remapping."
            )
        }
        category(
            R.string.settings_advanced,
            R.string.settings_advanced_summary,
            R.drawable.ic_code
        ) {
            toggle("Rewind", "rewind_enable", false)
            note(
                "Press Start + Select in a running game to open the native Quick Menu for " +
                    "core options, save states and advanced settings. Save Configuration persists changes."
            )
            note("${platform.label} settings and saves use private, isolated console storage.")
        }
        showCategories()
    }

    private fun configureProfile() {
        file = RetroConfig.file(this, platform, intent.data)
        val scope = if (intent.data == null) {
            getString(R.string.settings_global_scope, platform.label)
        } else {
            getString(
                R.string.settings_game_scope,
                platform.label,
                intent.getStringExtra(Intent.EXTRA_TITLE) ?: intent.data?.lastPathSegment.orEmpty()
            )
        }
        configureSettings(
            platform.label,
            "$scope\nChanges apply at the next game launch.",
            if (intent.data == null) {
                "GB, GBC, GBA, NES and SNES use separate console profiles. Choose a profile in General."
            } else {
                getString(R.string.settings_inheritance)
            }
        )
    }

    private fun toggle(label: String, key: String, default: Boolean) {
        toggleRow(label, RetroConfig.read(file)[key]?.toBooleanStrictOrNull() ?: default) {
            RetroConfig.set(file, key, it.toString())
        }
    }
}
