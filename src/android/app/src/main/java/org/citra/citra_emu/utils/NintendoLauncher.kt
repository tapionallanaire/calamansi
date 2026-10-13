package org.citra.citra_emu.utils

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.calamansi.cemu.CemuActivity
import org.calamansi.cemu.CemuSettingsActivity
import org.calamansi.dolphin.DolphinActivity
import org.calamansi.dolphin.DolphinSettingsActivity
import org.calamansi.engine.EngineFiles
import org.calamansi.engine.EngineId
import org.calamansi.engine.EngineSession
import org.calamansi.engine.NintendoPlatform
import org.calamansi.nds.NdsActivity
import org.calamansi.nds.NdsSettingsActivity
import org.calamansi.retroarch.RetroArchActivity
import org.calamansi.retroarch.RetroArchSettingsActivity
import org.citra.citra_emu.R
import org.citra.citra_emu.activities.EmulationActivity
import org.citra.citra_emu.activities.NintendoLaunchActivity
import org.citra.citra_emu.features.settings.ui.SettingsActivity
import org.citra.citra_emu.features.settings.utils.SettingsFile
import org.citra.citra_emu.model.Game

object NintendoLauncher {
    private var switching = false

    fun activeGame(context: Context): Game? {
        EmulationActivity.activeGame?.let { return it }
        val session = EngineSession.snapshot(context) ?: return null
        if (session.getString("kind") != EngineSession.KIND_GAME) return null
        val platform = NintendoPlatform.valueOf(requireNotNull(session.getString("platform")))
        val path = requireNotNull(session.getString("path"))
        return GameHelper.cachedGameList.firstOrNull { it.platform == platform && it.path == path }
            ?: Game(
                valid = true,
                title = requireNotNull(session.getString("title")),
                path = path,
                filename = FileUtil.getFilename(path.toUri()),
                fileType = platform.label,
                platform = platform
            )
    }

    fun launchIntent(context: Context, game: Game): Intent =
        Intent(context, NintendoLaunchActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = game.path.toUri()
            putExtra(EngineFiles.EXTRA_PLATFORM, game.platform.name)
        }

    fun startBackend(context: Context, game: Game) {
        require(game.platform != NintendoPlatform.THREE_DS)
        context.startActivity(
            backendIntent(context, game.platform).apply {
                action = Intent.ACTION_VIEW
                data = game.path.toUri()
                putExtra(Intent.EXTRA_TITLE, game.title)
                putExtra(EngineFiles.EXTRA_PLATFORM, game.platform.name)
                if (game.platform == NintendoPlatform.NDS) putExtra("game", game)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        )
    }

    fun resume(context: Context, game: Game): Boolean {
        if (game.platform == NintendoPlatform.THREE_DS) return EmulationActivity.resumeGame(context)
        val session = EngineSession.snapshot(context) ?: return false
        if (session.getString("kind") != EngineSession.KIND_GAME) return false
        if (session.getString("platform") != game.platform.name ||
            session.getString("path") != game.path
        ) {
            return false
        }
        context.startActivity(
            backendIntent(context, game.platform).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
        )
        return true
    }

    fun stopForSwitch(activity: AppCompatActivity, onStopped: () -> Unit) {
        if (switching) return
        switching = true
        activity.lifecycleScope.launch {
            try {
                EmulationActivity.stopForEngineSwitch()
                EngineSession.snapshot(activity)?.getString("token")?.let { token ->
                    awaitStop(activity, token)
                }
                onStopped()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (!activity.isFinishing && !activity.isDestroyed) {
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.engine_launch_error)
                        .setMessage(error.message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            } finally {
                switching = false
            }
        }
    }

    private suspend fun awaitStop(context: Context, token: String) {
        EngineSession.requestStop(context, token)
        // Snapshot also prunes a process that died before it could acknowledge shutdown.
        while (true) {
            val session = EngineSession.snapshot(context)
            if (session?.getString("token") != token) return
            check(session.getString("state") != "error") {
                "The previous engine could not shut down safely. No replacement game was started."
            }
            delay(100)
        }
    }

    fun openSettings(context: Context, platform: NintendoPlatform, game: Game? = null) {
        if (platform == NintendoPlatform.THREE_DS) {
            SettingsActivity.launch(
                context,
                SettingsFile.FILE_NAME_CONFIG,
                game?.takeIf {
                    it.titleId != 0L
                }?.let { String.format("%016X", it.titleId) }.orEmpty()
            )
            return
        }
        val activity = when (platform) {
            NintendoPlatform.NDS -> NdsSettingsActivity::class.java

            NintendoPlatform.GB, NintendoPlatform.GBC, NintendoPlatform.GBA,
            NintendoPlatform.NES, NintendoPlatform.SNES -> RetroArchSettingsActivity::class.java

            NintendoPlatform.GAMECUBE, NintendoPlatform.WII -> DolphinSettingsActivity::class.java

            NintendoPlatform.WII_U -> CemuSettingsActivity::class.java

            else -> error("Engine is not enabled")
        }
        val settingsIntent =
            Intent(context, activity).apply {
                data = game?.path?.toUri()
                putExtra(Intent.EXTRA_TITLE, game?.title)
                if (game != null || platform.engine != EngineId.RETROARCH) {
                    putExtra(EngineFiles.EXTRA_PLATFORM, platform.name)
                }
            }
        val owner = EngineSession.snapshot(context)
        val needsStop = owner != null &&
            (platform.engine == EngineId.DOLPHIN || platform.engine == EngineId.CEMU) &&
            (
                owner.getString(
                    "platform"
                )?.let(NintendoPlatform::valueOf)?.engine != platform.engine ||
                    (
                        game != null && (
                            platform.engine == EngineId.DOLPHIN ||
                                owner.getString("path") != game.path
                            )
                        )
                )
        if (needsStop) {
            val host = context as? AppCompatActivity
                ?: error("Native settings require an Activity for safe shutdown")
            MaterialAlertDialogBuilder(host)
                .setTitle(R.string.emulation_close_game)
                .setMessage(R.string.engine_stop_for_settings)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    stopForSwitch(host) { host.startActivity(settingsIntent) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            context.startActivity(settingsIntent)
        }
    }

    private fun backendIntent(context: Context, platform: NintendoPlatform): Intent {
        val activity = when (platform) {
            NintendoPlatform.NDS -> NdsActivity::class.java

            NintendoPlatform.GB, NintendoPlatform.GBC, NintendoPlatform.GBA,
            NintendoPlatform.NES, NintendoPlatform.SNES -> RetroArchActivity::class.java

            NintendoPlatform.GAMECUBE, NintendoPlatform.WII -> DolphinActivity::class.java

            NintendoPlatform.WII_U -> CemuActivity::class.java

            else -> error("Use an enabled console launcher")
        }
        return Intent(context, activity)
    }
}
