// SPDX-License-Identifier: MPL-2.0
package org.calamansi.cemu

import android.content.BroadcastReceiver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Button
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import info.cemu.cemu.input.InputManager
import info.cemu.cemu.nativeinterface.NativeGameTitles
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeSettings
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.calamansi.engine.EngineSession
import org.calamansi.engine.NintendoPlatform
import org.calamansi.settings.EngineSettingsActivity
import org.citra.citra_emu.R

class CemuSettingsActivity : EngineSettingsActivity() {
    private val input = InputManager()
    private var captureMapping: Int? = null
    private var captureLabel: Button? = null
    private var importName: String? = null
    private var ready = false
    private var session: String? = null
    private var ownsSession = false
    private var destroyed = false
    private var stopReceiver: BroadcastReceiver? = null

    @Volatile private var stopping = false
    private var nativeStopped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CemuRuntime.attach(this)
        importName = savedInstanceState?.getString("import")
        configureSettings(
            "Wii U",
            getString(R.string.settings_global_scope, "Wii U"),
            "Graphics, audio, controls and system settings apply to all Wii U games. " +
                "Changes affect Wii U emulation only and may require restarting the title."
        )
        CemuRuntime.unsupportedReason()?.let {
            note(it)
            return
        }
        note("Loading Wii U settings…")
        try {
            val active = EngineSession.snapshot(this)
            session = if (active == null) {
                ownsSession = true
                EngineSession.begin(
                    this,
                    NintendoPlatform.WII_U,
                    intent.data,
                    "Wii U settings",
                    CemuSettingsActivity::class.java,
                    EngineSession.KIND_SETTINGS
                )
                    .also(CemuRuntime::bindSession)
            } else {
                check(
                    active.getString("platform") == NintendoPlatform.WII_U.name &&
                        active.getString("state") == "running" &&
                        active.getString("kind") == EngineSession.KIND_GAME
                ) {
                    "Stop the active engine before opening Wii U settings"
                }
                requireNotNull(active.getString("token")).also(CemuRuntime::requireSession)
            }
        } catch (error: Exception) {
            showError(error.message.orEmpty())
            return
        }
        if (ownsSession) {
            stopReceiver =
                EngineSession.registerStopHandler(
                    applicationContext,
                    requireNotNull(session),
                    ::stopOwnedSession
                )
        }
        if (ownsSession) {
            actionRow("Stop", "Safely close the Wii U settings session.") { stopOwnedSession() }
        }
        CemuRuntime.execute({
            CemuRuntime.requireSession(requireNotNull(session))
            CemuRuntime.initialize()
            if (ownsSession) {
                intent.data?.let { CemuMetadataService.findGame(it) { stopping }?.titleId }
            } else {
                CemuRuntime.activeTitleId()
            }
        }) { result ->
            result.onSuccess { titleId ->
                if (destroyed) return@onSuccess
                ready = true
                try {
                    showSettings(titleId)
                } catch (
                    error: Exception
                ) {
                    ready = false
                    showError(error.message.orEmpty())
                }
                if (intent.data != null &&
                    titleId == null
                ) {
                    configureSettings(
                        "Wii U",
                        getString(R.string.settings_global_scope, "Wii U"),
                        "Settings apply to all Wii U games. No valid title metadata; " +
                            "per-game profile settings are unavailable for this path."
                    )
                }
            }.onFailure { error -> showError(error.message.orEmpty()) }
        }
    }

    private fun <T> call(action: () -> T): T = CemuRuntime.call {
        CemuRuntime.requireSession(requireNotNull(session))
        action()
    }

    private fun mutate(action: () -> Unit) {
        val owner = session ?: return
        CemuRuntime.execute({
            CemuRuntime.requireSession(owner)
            action()
            NativeSettings.saveSettings()
            CemuRuntime.throwNativeError()
        }) {
            it.onFailure { error -> showError(error.message.orEmpty()) }
        }
    }

    private fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        toggleRow(label, call(get)) { checked -> mutate { set(checked) } }
    }

    private fun choice(
        label: String,
        names: Array<String>,
        values: IntArray,
        get: () -> Int,
        set: (Int) -> Unit
    ) {
        val nativeValue = call(get)
        val index = values.indexOf(nativeValue)
        val choices = if (index < 0) values + nativeValue else values
        val labels = if (index < 0) {
            names + getString(R.string.settings_custom_value, nativeValue.toString())
        } else {
            names
        }
        choiceRow(label, labels.toList(), if (index < 0) values.size else index) { selected ->
            mutate { set(choices[selected]) }
        }
    }

    private fun showSettings(titleId: Long?) {
        category(
            R.string.preferences_graphics,
            R.string.settings_graphics_summary,
            R.drawable.ic_graphics
        ) { populate { showGraphics() } }
        category(
            R.string.preferences_audio,
            R.string.settings_audio_summary,
            R.drawable.ic_audio
        ) { populate { showAudio() } }
        category(
            R.string.settings_controls,
            R.string.settings_controls_summary,
            R.drawable.ic_controls_settings
        ) { populate { showControls() } }
        category(
            R.string.preferences_system,
            R.string.settings_system_summary,
            R.drawable.ic_system_settings
        ) { populate { showSystem() } }
        category(
            R.string.settings_advanced,
            R.string.settings_advanced_summary,
            R.drawable.ic_code
        ) { populate { showAdvanced() } }
        if (titleId != null) {
            category(
                R.string.settings_game_profile,
                R.string.settings_game_profile_summary,
                R.drawable.ic_general_settings
            ) { populate { showGameProfile(titleId) } }
        }
        showCategories()
    }

    private fun populate(build: () -> Unit) {
        captureMapping = null
        captureLabel = null
        try {
            build()
        } catch (error: Exception) {
            showError(error.message.orEmpty())
        }
    }

    private fun showGraphics() {
        note("Wii U defaults · graphics settings apply to all games.")
        toggle(
            "Asynchronous shader compilation",
            NativeSettings::getAsyncShaderCompile,
            NativeSettings::setAsyncShaderCompile
        )
        toggle(
            "Accurate graphics barriers",
            NativeSettings::getAccurateBarriers,
            NativeSettings::setAccurateBarriers
        )
        choice(
            "VSync",
            arrayOf("Off", "Double buffering", "Triple buffering"),
            intArrayOf(0, 1, 2),
            NativeSettings::getVsyncMode,
            NativeSettings::setVsyncMode
        )
        choice(
            "Display scaling",
            arrayOf("Keep aspect ratio", "Stretch"),
            intArrayOf(0, 1),
            NativeSettings::getFullscreenScaling,
            NativeSettings::setFullscreenScaling
        )
        val filters = arrayOf("Bilinear", "Bicubic", "Bicubic Hermite", "Nearest neighbour")
        choice(
            "Upscaling filter",
            filters,
            intArrayOf(0, 1, 2, 3),
            NativeSettings::getUpscalingFilter,
            NativeSettings::setUpscalingFilter
        )
        choice(
            "Downscaling filter",
            filters,
            intArrayOf(0, 1, 2, 3),
            NativeSettings::getDownscalingFilter,
            NativeSettings::setDownscalingFilter
        )
        toggle(
            "Show frame rate",
            NativeSettings::isOverlayFPSEnabled,
            NativeSettings::setOverlayFPSEnabled
        )
    }

    private fun showAudio() {
        note("Wii U defaults · TV and GamePad audio settings apply to all games.")
        for (tv in listOf(true, false)) {
            val screen = if (tv) "TV" else "GamePad"
            toggle("$screen audio", {
                NativeSettings.getAudioDeviceEnabled(tv)
            }, { NativeSettings.setAudioDeviceEnabled(it, tv) })
            choice(
                "$screen audio channels",
                arrayOf(
                    "Mono",
                    "Stereo",
                    "Surround"
                ),
                intArrayOf(0, 1, 2),
                {
                    NativeSettings.getAudioDeviceChannels(tv)
                },
                { NativeSettings.setAudioDeviceChannels(it, tv) }
            )
            choice(
                "$screen audio volume",
                Array(11) {
                    "${it * 10}%"
                },
                IntArray(11) {
                    it * 10
                },
                {
                    NativeSettings.getAudioDeviceVolume(tv)
                },
                { NativeSettings.setAudioDeviceVolume(it, tv) }
            )
        }
    }

    private fun showGameProfile(titleId: Long) {
        note(
            "Game profile · ${intent.getStringExtra(Intent.EXTRA_TITLE).orEmpty()} " +
                "(${titleId.toULong().toString(16)}). These settings apply only to this title " +
                "and may require restarting it."
        )
        choice(
            "CPU mode",
            arrayOf(
                "Auto",
                "Interpreter",
                "Single-core recompiler",
                "Multi-core recompiler"
            ),
            intArrayOf(4, 0, 1, 3),
            {
                NativeGameTitles.getCpuModeForTitle(titleId)
            },
            { NativeGameTitles.setCpuModeForTitle(titleId, it) }
        )
        val quantum = NativeGameTitles.THREAD_QUANTUM_VALUES
        choice("Thread quantum", quantum.map(Int::toString).toTypedArray(), quantum, {
            NativeGameTitles.getThreadQuantumForTitle(titleId)
        }, { NativeGameTitles.setThreadQuantumForTitle(titleId, it) })
        toggle("Load shared libraries", {
            NativeGameTitles.isLoadingSharedLibrariesForTitleEnabled(titleId)
        }, { NativeGameTitles.setLoadingSharedLibrariesForTitleEnabled(titleId, it) })
        toggle("Accurate shader multiplication", {
            NativeGameTitles.isShaderMultiplicationAccuracyForTitleEnabled(titleId)
        }, { NativeGameTitles.setShaderMultiplicationAccuracyForTitleEnabled(titleId, it) })
    }

    private fun showControls() {
        note("Wii U defaults · controller mappings apply to all games.")
        note(
            "VPAD physical controller mappings — select an input, " +
                "then press a controller key or move an axis. Long-press a mapping to clear it."
        )
        val inputs =
            arrayOf(
                "A", "B", "X", "Y", "L", "R", "ZL", "ZR", "+", "−",
                "Up", "Down", "Left", "Right", "Left stick click", "Right stick click",
                "Left stick up", "Left stick down", "Left stick left", "Left stick right",
                "Right stick up", "Right stick down", "Right stick left", "Right stick right",
                "Microphone", "Screen", "Home"
            )
        inputs.forEachIndexed { index, name ->
            val mapping = index + 1
            lateinit var button: Button
            button = actionRow(
                "$name: ${call { NativeInput.getControllerMapping(0, mapping) }}"
            ) {
                captureMapping = mapping
                captureLabel = button
                button.text = "$name: waiting for controller input…"
            }.apply {
                setOnLongClickListener {
                    mutate { NativeInput.clearControllerMapping(0, mapping) }
                    text = "$name: unmapped"
                    true
                }
            }
        }
    }

    private fun showSystem() {
        note("Wii U defaults · language and imported system files apply to all games.")
        choice(
            "Console language",
            arrayOf(
                "Japanese", "English", "French", "German", "Italian", "Spanish",
                "Chinese", "Korean", "Dutch", "Portuguese", "Russian", "Taiwanese"
            ),
            IntArray(12) {
                it
            },
            NativeSettings::getConsoleLanguage,
            NativeSettings::setConsoleLanguage
        )
        note(
            "Nintendo fonts and encrypted-disc keys are not included. " +
                "Import your own legitimate Wii U dump; game files may separately " +
                "require their original title ticket."
        )
        for (name in listOf(
            "CafeCn.ttf",
            "CafeKr.ttf",
            "CafeStd.ttf",
            "CafeTw.ttf",
            "shareddata.bin",
            "keys.txt"
        )) {
            actionRow("Import $name") {
                importName = name
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    },
                    IMPORT
                )
            }
        }
        note(
            "Missing required font paths:\n${CemuRuntime.requiredUserFiles().joinToString(
                "\n"
            ).ifEmpty {
                "None"
            }}"
        )
    }

    private fun showAdvanced() {
        note("Wii U defaults · Settings and imports are stored separately from other consoles.")
        note("Wii U data uses private console storage. Manage imported files in System settings.")
        note(
            "CPU/profile, audio and renderer changes may require stopping and restarting the title."
        )
        if (ownsSession) {
            actionRow("Stop", "Safely close the Wii U settings session.") { stopOwnedSession() }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val mapping = captureMapping
        if (ready && mapping != null && captureLabel?.isAttachedToWindow == true &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.device != null &&
            event.source and android.view.InputDevice.SOURCE_GAMEPAD != 0
        ) {
            try {
                CemuRuntime.call {
                    CemuRuntime.requireSession(requireNotNull(session))
                    input.mapKeyEventToMappingId(0, mapping, event)
                }
                finishMapping(mapping)
            } catch (error: Exception) {
                showError(error.message.orEmpty())
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val mapping = captureMapping
        if (ready && mapping != null && captureLabel?.isAttachedToWindow == true) {
            try {
                if (CemuRuntime.call {
                        CemuRuntime.requireSession(requireNotNull(session))
                        input.mapMotionEventToMappingId(0, mapping, event)
                    }
                ) {
                    finishMapping(mapping)
                    return true
                }
            } catch (error: Exception) {
                showError(error.message.orEmpty())
            }
        }
        return super.onGenericMotionEvent(event)
    }

    private fun finishMapping(mapping: Int) {
        captureMapping = null
        captureLabel?.text = call { NativeInput.getControllerMapping(0, mapping) }
        captureLabel = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("import", importName)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Android Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val name = importName ?: return
        importName = null
        CemuRuntime.execute({ importUserFile(uri, name) }) { result ->
            result.onSuccess {
                Toast.makeText(
                    this,
                    "Imported $name; restart the Wii U title to apply.",
                    Toast.LENGTH_LONG
                ).show()
            }
                .onFailure { showError(it.message.orEmpty()) }
        }
    }

    private fun importUserFile(uri: Uri, name: String) {
        require(
            name in
                listOf(
                    "CafeCn.ttf",
                    "CafeKr.ttf",
                    "CafeStd.ttf",
                    "CafeTw.ttf",
                    "shareddata.bin",
                    "keys.txt"
                )
        )
        val destination = if (name.endsWith(".ttf")) {
            File(CemuRuntime.root(), "mlc01/sys/title/0005001b/10042400/content/$name")
        } else {
            File(CemuRuntime.root(), name)
        }
        destination.parentFile?.mkdirs()
        val temporary = File.createTempFile("cemu-import-", ".tmp", destination.parentFile)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(16384)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total >
                            32L * 1024 * 1024
                        ) {
                            throw IOException("Wii U shared files must not exceed 32 MiB")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            } ?: throw IOException("Cannot read selected user file")
            if (temporary.length() == 0L) throw IOException("The selected file is empty")
            if (name == "shareddata.bin" && temporary.length() != 32L * 1024 * 1024) {
                throw IOException(
                    "shareddata.bin must be the complete 32 MiB Wii U shared-area dump"
                )
            }
            if (name.endsWith(".ttf")) {
                val magic = ByteArray(4)
                temporary.inputStream().use {
                    if (it.read(magic) !=
                        4
                    ) {
                        throw IOException("Truncated font")
                    }
                }
                if (!magic.contentEquals(byteArrayOf(0, 1, 0, 0)) &&
                    String(magic, Charsets.US_ASCII) !in listOf("OTTO", "ttcf", "true")
                ) {
                    throw IOException("Not a TrueType/OpenType font dump")
                }
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            temporary.delete()
        }
    }

    private fun releaseSession() {
        if (!nativeStopped || !destroyed) return
        val owner = session ?: return
        applicationContext.unregisterReceiver(requireNotNull(stopReceiver))
        stopReceiver = null
        EngineSession.end(this, owner)
        session = null
    }

    private fun stopOwnedSession() {
        if (!ownsSession || stopping) return
        val owner = session ?: return
        stopping = true
        ready = false
        CemuRuntime.closeSession(owner)
        EngineSession.update(this, owner, "stopping")
        CemuRuntime.execute({ CemuRuntime.stop() }) { result ->
            result.onSuccess {
                nativeStopped = true
                if (destroyed) releaseSession() else finish()
            }.onFailure {
                stopping = false
                EngineSession.update(this, owner, "error")
                showError(
                    "Wii U settings cleanup failed; ownership retained. Retry Stop. ${it.message}"
                )
            }
        }
    }

    override fun onExitSettings() {
        if (ownsSession && session != null) stopOwnedSession() else super.onExitSettings()
    }

    override fun onDestroy() {
        destroyed = true
        ready = false
        stopOwnedSession()
        super.onDestroy()
        android.os.Handler(mainLooper).post { releaseSession() }
    }

    private fun showError(message: String) {
        if (!isFinishing && !isDestroyed &&
            !destroyed
        ) {
            MaterialAlertDialogBuilder(this).setTitle("Wii U settings error").setMessage(message)
                .setPositiveButton(android.R.string.ok, null).show()
        }
    }

    companion object {
        private const val IMPORT = 1
    }
}
