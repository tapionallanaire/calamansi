package org.calamansi.nds

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.VideoFiltering
import org.calamansi.settings.EngineSettingsActivity
import org.citra.citra_emu.R

class NdsSettingsActivity : EngineSettingsActivity() {
    private lateinit var settings: NdsPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = NdsPreferences(this, intent.data)
        val scope = if (intent.data == null) {
            getString(R.string.settings_global_scope, "Nintendo DS")
        } else {
            getString(
                R.string.settings_game_scope,
                "Nintendo DS",
                intent.getStringExtra(Intent.EXTRA_TITLE)
                    ?: intent.data?.lastPathSegment.orEmpty()
            )
        }
        configureSettings(
            "Nintendo DS",
            scope,
            if (intent.data == null) "" else getString(R.string.settings_inheritance)
        )
        category(
            R.string.preferences_general,
            R.string.settings_general_summary,
            R.drawable.ic_general_settings
        ) {
            select("Fast-forward speed", "fast_forward", (1..8).map { "$it×" }, 2, 1)
        }
        category(
            R.string.preferences_graphics,
            R.string.settings_graphics_summary,
            R.drawable.ic_graphics
        ) {
            select("Renderer", "renderer", listOf("Software", "OpenGL"), 1)
            select("Internal resolution (OpenGL)", "resolution", (1..8).map { "$it×" }, 3, 1)
            toggle("Threaded software rendering", "threaded", true)
            select("DS renderer processing", "filter", VideoFiltering.entries.map { it.name }, 0)
        }
        category(
            R.string.preferences_audio,
            R.string.settings_audio_summary,
            R.drawable.ic_audio
        ) {
            toggle("Audio output", "audio", true)
            sliderRow("Volume", settings.int("volume", 100), 100) {
                settings.editable.edit().putInt("volume", it).apply()
            }
            select(
                "Audio interpolation",
                "interpolation",
                listOf("None", "Linear", "Cosine", "Cubic"),
                1
            )
            select("Audio precision", "bitrate", listOf("Automatic", "10 bit", "16 bit"), 0)
            select("Audio latency", "latency", listOf("Low", "Medium", "High"), 1)
        }
        category(
            R.string.settings_controls,
            R.string.settings_controls_summary,
            R.drawable.ic_controls_settings
        ) {
            toggle("Joystick maps to DS D-pad", "joystick", true)
            sliderRow("Joystick dead zone (%)", settings.int("dead_zone", 25), 75) {
                settings.editable.edit().putInt("dead_zone", it).apply()
            }
            Input.SYSTEM_BUTTONS.forEach { input ->
                lateinit var button: Button
                fun refresh() {
                    button.text = "${input.name}: ${KeyEvent.keyCodeToString(settings.key(input))}"
                }
                button = actionRow(input.name) {
                    val dialog = MaterialAlertDialogBuilder(this)
                        .setTitle(input.name)
                        .setMessage("Press a controller key for ${input.name}")
                        .setNegativeButton(android.R.string.cancel, null)
                        .create()
                    dialog.setOnKeyListener { _, code, event ->
                        if (event.action == KeyEvent.ACTION_UP && code != KeyEvent.KEYCODE_BACK) {
                            settings.editable.edit().putInt("key_${input.name}", code).apply()
                            refresh()
                            dialog.dismiss()
                            true
                        } else {
                            false
                        }
                    }
                    dialog.show()
                }
                refresh()
            }
        }
        category(
            R.string.preferences_system,
            R.string.settings_system_summary,
            R.drawable.ic_system_settings
        ) {
            note("DS direct boot uses built-in FreeBIOS and generated firmware.")
            select(
                "Firmware language (next launch)",
                "language",
                listOf("Japanese", "English", "French", "German", "Italian", "Spanish"),
                1
            )
        }
        category(
            R.string.settings_advanced,
            R.string.settings_advanced_summary,
            R.drawable.ic_code
        ) {
            note(
                "JIT and language changes apply at the next launch. " +
                    "Other options apply when returning to the game."
            )
            toggle("JIT (next launch)", "jit", true)
            select("Presentation frame skip", "frame_skip", listOf("None", "1", "2", "3"), 0)
            val perGame = intent.data != null
            val titleId = if (perGame) {
                R.string.settings_reset_nds_game
            } else {
                R.string.settings_reset_nds
            }
            actionRow(getString(titleId)) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(titleId)
                    .setMessage(
                        if (perGame) {
                            R.string.settings_reset_nds_game_message
                        } else {
                            R.string.settings_reset_nds_message
                        }
                    )
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        settings.editable.edit().clear().apply()
                        recreate()
                    }
                    .show()
            }
        }
        showCategories()
    }

    private fun toggle(label: String, key: String, default: Boolean) {
        toggleRow(label, settings.bool(key, default)) {
            settings.editable.edit().putBoolean(key, it).apply()
        }
    }

    private fun select(
        label: String,
        key: String,
        values: List<String>,
        default: Int,
        base: Int = 0
    ) {
        choiceRow(label, values, settings.int(key, default) - base) {
            settings.editable.edit().putInt(key, it + base).apply()
        }
    }
}
