package org.calamansi.retroarch

import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import com.retroarch.browser.retroactivity.RetroActivityCommon
import java.io.File
import org.calamansi.engine.EngineFiles
import org.calamansi.engine.EngineSession
import org.calamansi.engine.NintendoPlatform
import org.calamansi.engine.PictureFilters

/** Uses RetroArch's NativeActivity, audio/video/input drivers and libretro loader. */
class RetroArchActivity : RetroActivityCommon() {
    private var token: String? = null
    private var receiver: BroadcastReceiver? = null
    private var stopping = false
    private var nativeCreated = false
    private var bootFailed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            val uri = requireNotNull(intent.data) { "A cartridge URI is required" }
            val platform = NintendoPlatform.valueOf(
                requireNotNull(intent.getStringExtra(EngineFiles.EXTRA_PLATFORM))
            )
            require(platform in RomHeaders.platforms)
            val core =
                File(
                    applicationInfo.nativeLibraryDir,
                    "lib${RetroConfig.core(platform)}_libretro_android.so"
                )
            check(core.isFile) {
                "Bundled ${RetroConfig.core(platform)} core is missing from this installation"
            }
            val root = RetroConfig.directory(this, platform)
            val (rom, romTitle) = RetroContent.copy(this, uri, platform, File(root, "content"))
            val config = RetroConfig.file(this, platform, uri)
            // Leave ACTION_VIEW/data untouched: the session and scanner retain the original URI.
            intent.putExtra("ROM", rom.absolutePath)
                .putExtra("LIBRETRO", core.absolutePath)
                .putExtra("CONFIGFILE", config.absolutePath)
                .putExtra("DATADIR", root.absolutePath)
                .putExtra("EXTERNAL", root.absolutePath)
                .putExtra("SDCARD", root.absolutePath)
                .putExtra(
                    "IME",
                    Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                        ?: ""
                )
            val session = EngineSession.begin(
                this,
                platform,
                uri,
                intent.getStringExtra(Intent.EXTRA_TITLE) ?: romTitle,
                javaClass
            )
            token = session
            receiver = EngineSession.registerStopHandler(this, session) { stop() }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            nativeCreated = true
            super.onCreate(savedInstanceState)
            PictureFilters.attachNativeWindow(this)
        } catch (error: Exception) {
            if (nativeCreated) {
                bootFailed = true
                token?.let { EngineSession.update(this, it, "error") }
                stop()
                finish()
                return
            }
            receiver?.let { applicationContext.unregisterReceiver(it) }
            receiver = null
            token?.let { EngineSession.end(this, it) }
            token = null
            // Fail before boot instead of silently launching a dummy core or the wrong platform.
            throw IllegalStateException("Cannot launch classic-console emulation: ${error.message}", error)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // REORDER_TO_FRONT has no cartridge. A live core cannot be replaced without stop/join.
        if (intent.data != null &&
            (
                intent.data != getIntent().data ||
                    intent.getStringExtra(EngineFiles.EXTRA_PLATFORM) !=
                    getIntent().getStringExtra(EngineFiles.EXTRA_PLATFORM)
                )
        ) {
            android.widget.Toast.makeText(
                this,
                "Stop the active game before loading another cartridge",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!stopping) token?.let { EngineSession.update(this, it, "running") }
    }

    override fun onPause() {
        super.onPause()
        if (!stopping) token?.let { EngineSession.update(this, it, "paused") }
    }

    private external fun requestNativeStop()

    private fun stop() {
        if (stopping) return
        stopping = true
        if (!bootFailed) token?.let { EngineSession.update(this, it, "stopping") }
        if (nativeCreated) requestNativeStop()
        // APP_CMD_DESTROY also wakes the paused native event loop. Native onRetroArchExit
        // schedules finish only after main_exit has saved SRAM/state and deinitialized.
    }

    fun onRetroArchError() {
        runOnUiThread {
            bootFailed = true
            stopping = true
            token?.let { EngineSession.update(this, it, "error") }
            android.widget.Toast.makeText(
                this,
                "Classic-console emulation could not initialize this cartridge",
                android.widget.Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    override fun onRetroArchExit() {
        runOnUiThread {
            stopping = true
            if (!bootFailed) token?.let { EngineSession.update(this, it, "stopping") }
            finish()
        }
    }

    override fun onDestroy() {
        stopping = true
        if (!bootFailed) token?.let { EngineSession.update(this, it, "stopping") }
        // Patched upstream onDestroy joins rarch_main; only then may another backend start.
        super.onDestroy()
        nativeCreated = false
        receiver?.let { applicationContext.unregisterReceiver(it) }
        receiver = null
        val completedToken = token
        token = null
        // ActivityThread removes the finishing window after onDestroy returns.
        completedToken?.let { session ->
            Handler(Looper.getMainLooper()).post { EngineSession.end(applicationContext, session) }
        }
    }
}
