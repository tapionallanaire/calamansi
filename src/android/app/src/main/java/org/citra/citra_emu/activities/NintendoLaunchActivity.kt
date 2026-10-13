package org.citra.citra_emu.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.citra.citra_emu.R
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.utils.FileUtil
import org.citra.citra_emu.utils.NintendoCatalog
import org.citra.citra_emu.utils.NintendoLauncher

class NintendoLaunchActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            FrameLayout(this).apply {
                addView(
                    ProgressBar(this@NintendoLaunchActivity).apply {
                        contentDescription = getString(R.string.engine_loading)
                    },
                    FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
                )
            }
        )
        lifecycleScope.launch {
            try {
                val uri = requireNotNull(intent.data) { getString(R.string.engine_invalid_rom) }
                require(uri.scheme == "content" || uri.scheme == "file") {
                    getString(R.string.engine_invalid_rom)
                }
                if (FileUtil.getExtension(uri).lowercase() in Game.extensions) {
                    forwardThreeDS(uri)
                    return@launch
                }
                val active = NintendoLauncher.activeGame(this@NintendoLaunchActivity)
                if (active?.path == uri.toString() &&
                    NintendoLauncher.resume(this@NintendoLaunchActivity, active)
                ) {
                    finish()
                    return@launch
                }
                val read = {
                    NintendoLauncher.stopForSwitch(this@NintendoLaunchActivity) {
                        readAndStart(uri)
                    }
                }
                if (active == null) {
                    read()
                } else {
                    MaterialAlertDialogBuilder(this@NintendoLaunchActivity)
                        .setTitle(R.string.emulation_close_game)
                        .setMessage(
                            getString(
                                R.string.home_switch_game,
                                active.title,
                                FileUtil.getFilename(uri)
                            )
                        )
                        .setPositiveButton(android.R.string.ok) { _, _ -> read() }
                        .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                        .setOnCancelListener { finish() }
                        .show()
                }
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    private fun readAndStart(uri: Uri) {
        lifecycleScope.launch {
            try {
                val game = withContext(Dispatchers.IO) {
                    NintendoCatalog(applicationContext).use { it.read(uri) }
                } ?: throw IllegalArgumentException(getString(R.string.engine_invalid_rom))
                start(game)
            } catch (error: Exception) {
                showError(error)
            }
        }
    }

    private fun showError(error: Exception) {
        if (error is CancellationException) throw error
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.engine_launch_error)
            .setMessage(error.message)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun forwardThreeDS(uri: Uri) {
        val forward = {
            NintendoLauncher.stopForSwitch(this) {
                startActivity(
                    Intent(this, EmulationActivity::class.java).apply {
                        data = uri
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
                finish()
            }
        }
        val active = NintendoLauncher.activeGame(this)
        if (active == null) {
            forward()
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.emulation_close_game)
                .setMessage(
                    getString(R.string.home_switch_game, active.title, FileUtil.getFilename(uri))
                )
                .setPositiveButton(android.R.string.ok) { _, _ -> forward() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        }
    }

    private fun start(game: Game) {
        NintendoLauncher.stopForSwitch(this) {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putLong(game.keyLastPlayedTime, System.currentTimeMillis()).apply()
            NintendoLauncher.startBackend(this, game)
            finish()
        }
    }
}
