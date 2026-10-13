package org.calamansi.nds

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.view.KeyEvent
import java.security.MessageDigest
import me.magnum.melonds.domain.model.AudioBitrate
import me.magnum.melonds.domain.model.AudioInterpolation
import me.magnum.melonds.domain.model.AudioLatency
import me.magnum.melonds.domain.model.ConsoleType
import me.magnum.melonds.domain.model.EmulatorConfiguration
import me.magnum.melonds.domain.model.FirmwareConfiguration
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.MicSource
import me.magnum.melonds.domain.model.RendererConfiguration
import me.magnum.melonds.domain.model.VideoFiltering
import me.magnum.melonds.domain.model.VideoRenderer
import org.calamansi.engine.EngineFiles
import org.calamansi.engine.EngineId

class NdsPreferences(private val context: Context, uri: Uri?) {
    private val global = EngineFiles.preferences(context, EngineId.NDS)
    val editable: SharedPreferences = if (uri ==
        null
    ) {
        global
    } else {
        context.getSharedPreferences("engine_nds_rom_${identity(uri)}", Context.MODE_PRIVATE)
    }
    private fun source(key: String) = if (editable.contains(key)) editable else global
    fun bool(key: String, default: Boolean) = source(key).getBoolean(key, default)
    fun int(key: String, default: Int) = source(key).getInt(key, default)

    fun configuration(): EmulatorConfiguration = EmulatorConfiguration(
        useCustomBios = false, showBootScreen = false,
        dsBios7Uri = null, dsBios9Uri = null, dsFirmwareUri = null,
        dsiBios7Uri = null, dsiBios9Uri = null, dsiFirmwareUri = null, dsiNandUri = null,
        internalDirectory = EngineFiles.root(context, EngineId.NDS).absolutePath,
        fastForwardSpeedMultiplier = int("fast_forward", 2).coerceIn(1, 8).toFloat(),
        rewindEnabled = false, rewindPeriodSeconds = 1, rewindWindowSeconds = 10,
        useJit = bool("jit", true), consoleType = ConsoleType.DS,
        soundEnabled = bool("audio", true),
        audioInterpolation = AudioInterpolation.entries[int("interpolation", 1).coerceIn(0, 3)],
        audioBitrate = AudioBitrate.entries[int("bitrate", 0).coerceIn(0, 2)],
        volume = int("volume", 100).coerceIn(0, 100),
        audioLatency = AudioLatency.entries[int("latency", 1).coerceIn(0, 2)],
        micSource = MicSource.BLOW,
        firmwareConfiguration = FirmwareConfiguration(
            "Calamansi",
            "",
            int("language", 1).coerceIn(0, 5),
            0,
            1,
            1,
            false,
            null
        ),
        rendererConfiguration = RendererConfiguration(
            VideoRenderer.entries[int("renderer", 1).coerceIn(0, 1)],
            VideoFiltering.entries[int("filter", 0).coerceIn(0, VideoFiltering.entries.lastIndex)],
            bool("threaded", true),
            int("resolution", 3).coerceIn(1, 8)
        )
    )

    fun key(input: Input): Int = int("key_${input.name}", defaultKeys.getValue(input))

    companion object {
        fun identity(uri: Uri): String = MessageDigest.getInstance(
            "SHA-256"
        ).digest(uri.toString().toByteArray()).joinToString("") {
            "%02x".format(it)
        }
        val defaultKeys = mapOf(
            Input.A to KeyEvent.KEYCODE_BUTTON_A,
            Input.B to KeyEvent.KEYCODE_BUTTON_B,
            Input.X to KeyEvent.KEYCODE_BUTTON_X, Input.Y to KeyEvent.KEYCODE_BUTTON_Y,
            Input.L to KeyEvent.KEYCODE_BUTTON_L1, Input.R to KeyEvent.KEYCODE_BUTTON_R1,
            Input.START to KeyEvent.KEYCODE_BUTTON_START,
            Input.SELECT to KeyEvent.KEYCODE_BUTTON_SELECT,
            Input.LEFT to KeyEvent.KEYCODE_DPAD_LEFT, Input.RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT,
            Input.UP to KeyEvent.KEYCODE_DPAD_UP, Input.DOWN to KeyEvent.KEYCODE_DPAD_DOWN
        )
    }
}
