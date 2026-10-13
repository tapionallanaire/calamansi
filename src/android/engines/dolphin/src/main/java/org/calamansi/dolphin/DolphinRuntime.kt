// SPDX-License-Identifier: GPL-2.0-or-later
package org.calamansi.dolphin

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.calamansi.engine.EngineFiles
import org.calamansi.engine.EngineId
import org.calamansi.engine.EngineSession
import org.calamansi.engine.NintendoPlatform
import org.dolphinemu.dolphinemu.DolphinApplication
import org.dolphinemu.dolphinemu.NativeLibrary
import org.dolphinemu.dolphinemu.utils.DirectoryInitialization

object DolphinRuntime {
    private val main = Handler(Looper.getMainLooper())
    private var activity = WeakReference<DolphinActivity>(null)
    private var token: String? = null
    private var sessionKind = EngineSession.KIND_GAME
    private var sessionContext: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var worker: Thread? = null
    private var stopper: Thread? = null
    private var stopping = false
    private var shutdownReady = false
    private var recreating = false
    private val activities = HashSet<Activity>()
    private val quiescence = DolphinQuiescence()
    private var backgroundDraining = false
    private var backgroundDrained = false
    private var lifecycleRegistered = false
    private var nativeInitialized = false
    private var shutdownFailed = false

    @Synchronized
    fun markLifecycleRegistered() {
        lifecycleRegistered = true
    }

    private fun trackActivity(host: Activity) {
        if (activities.add(host)) {
            quiescence.activityCreated(host.javaClass)
        }
    }

    @Synchronized
    fun onHostDestroyed(host: Activity) {
        if (!lifecycleRegistered) {
            main.post {
                synchronized(this) {
                    if (activities.remove(
                            host
                        )
                    ) {
                        quiescence.activityDestroyed(
                            host.javaClass,
                            host.isChangingConfigurations
                        )
                    }
                    token?.let { if (shutdownReady) finishOrPublish(host.applicationContext, it) }
                }
            }
        }
    }

    val lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(host: Activity, state: Bundle?) {
            synchronized(this@DolphinRuntime) {
                trackActivity(host)
                if (stopping && shutdownReady &&
                    quiescence.workers == 0
                ) {
                    main.post { host.finish() }
                }
            }
        }
        override fun onActivityPostDestroyed(host: Activity) {
            synchronized(this@DolphinRuntime) {
                if (activities.remove(host)) {
                    quiescence.activityDestroyed(host.javaClass, host.isChangingConfigurations)
                }
                if (sessionKind == EngineSession.KIND_SETTINGS &&
                    host is org.dolphinemu.dolphinemu.features.settings.ui.SettingsActivity &&
                    !host.isChangingConfigurations
                ) {
                    stop()
                }
                token?.let { if (shutdownReady) finishOrPublish(host.applicationContext, it) }
            }
        }
        override fun onActivityStarted(host: Activity) {}
        override fun onActivityResumed(host: Activity) {}
        override fun onActivityPaused(host: Activity) {}
        override fun onActivityStopped(host: Activity) {}
        override fun onActivitySaveInstanceState(host: Activity, state: Bundle) {}
        override fun onActivityDestroyed(host: Activity) {}
    }

    @Synchronized
    fun beginWork(): Boolean {
        if (stopping) return false
        quiescence.workStarted()
        return true
    }

    @Synchronized
    fun endWork() {
        quiescence.workFinished()
        token?.let { current ->
            if (shutdownReady) {
                main.post {
                    finishOrPublish(DolphinApplication.getAppContext(), current)
                }
            }
        }
    }

    fun launchWork(block: suspend () -> Unit) {
        if (!beginWork()) return
        CoroutineScope(Dispatchers.IO).launch { block() }.invokeOnCompletion { endWork() }
    }

    fun finishWorkThread() {
        val owner = Thread.currentThread()
        Thread({
            owner.join()
            main.post { endWork() }
        }, "DolphinWorkJoin").start()
    }

    fun preferences(context: Context): SharedPreferences =
        EngineFiles.preferences(context, EngineId.DOLPHIN)

    @Synchronized
    fun initialize(context: Context) {
        require(token != null && !stopping) {
            "GameCube/Wii initialization requires active engine ownership"
        }
        val state = EngineSession.snapshot(context)?.getString("state")
        require(state == "running" || state == "paused") {
            "GameCube/Wii ownership is stopping or unavailable"
        }
        try {
            DolphinApplication.attach(context)
            nativeInitialized = true
            backgroundDrained = false
            DirectoryInitialization.ensureReady(context)
            if (sessionKind != EngineSession.KIND_METADATA) {
                org.dolphinemu.dolphinemu.features.settings.model.AchievementModel.init()
            }
        } catch (error: LinkageError) {
            throw IOException("GameCube/Wii native library could not be loaded", error)
        } catch (error: IllegalStateException) {
            throw IOException("GameCube/Wii initialization failed: ${error.message}", error)
        }
    }

    @Synchronized
    fun attach(host: DolphinActivity, uri: Uri?, platform: NintendoPlatform, title: String) {
        trackActivity(host)
        activity = WeakReference(host)
        recreating = false
        if (token != null) {
            require(sessionKind == EngineSession.KIND_GAME && !stopping) {
                "GameCube/Wii emulation is shutting down or editing settings"
            }
            if (shutdownReady) main.post { host.finishAfterShutdown() }
            return
        }
        requireNotNull(uri) { "A GameCube/Wii launch requires a ROM URI" }
        val context = host.applicationContext
        val current = EngineSession.begin(
            context,
            platform,
            uri,
            title,
            DolphinActivity::class.java
        )
        token = current
        sessionKind = EngineSession.KIND_GAME
        sessionContext = context
        stopping = false
        shutdownReady = false
        backgroundDraining = false
        backgroundDrained = true
        receiver = EngineSession.registerStopHandler(context, current, ::stop)
    }

    @Synchronized
    fun attachSettings(host: Activity, platform: NintendoPlatform) {
        trackActivity(host)
        if (token != null) {
            require(
                !stopping &&
                    (
                        sessionKind == EngineSession.KIND_GAME ||
                            sessionKind == EngineSession.KIND_SETTINGS
                        )
            ) {
                "GameCube/Wii emulation is shutting down or reading metadata"
            }
            val state = EngineSession.snapshot(host)?.getString("state")
            require(state == "running" || state == "paused") {
                "GameCube/Wii ownership is not available"
            }
            return
        }
        val context = host.applicationContext
        val current = EngineSession.begin(
            context,
            platform,
            null,
            "GameCube/Wii settings",
            host.javaClass,
            EngineSession.KIND_SETTINGS
        )
        token = current
        sessionKind = EngineSession.KIND_SETTINGS
        sessionContext = context
        receiver = EngineSession.registerStopHandler(context, current, ::stop)
        stopping = false
        shutdownReady = false
        backgroundDraining = false
        backgroundDrained = true
    }

    @Synchronized
    fun stopSettingsAfterFailure() {
        if (sessionKind == EngineSession.KIND_SETTINGS) stop()
    }

    @Synchronized
    fun beginMetadata(context: Context, platform: NintendoPlatform): Boolean {
        if (token != null) {
            require(!stopping && sessionKind == EngineSession.KIND_GAME) {
                "GameCube/Wii ownership is not available"
            }
            require(beginWork()) { "GameCube/Wii emulation is shutting down" }
            return false
        }
        val current = EngineSession.begin(
            context,
            platform,
            null,
            "GameCube/Wii metadata",
            DolphinMetadataService::class.java,
            EngineSession.KIND_METADATA
        )
        token = current
        sessionKind = EngineSession.KIND_METADATA
        sessionContext = context.applicationContext
        receiver = EngineSession.registerStopHandler(context, current, ::stop)
        stopping = false
        shutdownReady = false
        backgroundDraining = false
        backgroundDrained = true
        check(beginWork())
        return true
    }

    fun endMetadata(ownsSession: Boolean) {
        if (!ownsSession) {
            endWork()
            return
        }
        val current = synchronized(this) {
            stopping = true
            shutdownReady = true
            requireNotNull(token)
        }
        val context = requireNotNull(sessionContext)
        try {
            // Metadata worker stays outside the runtime monitor so UI stop remains responsive.
            drainNative()
            synchronized(this) { backgroundDrained = true }
        } catch (error: Throwable) {
            synchronized(this) { shutdownFailed = true }
            EngineSession.update(context, current, "error")
        } finally {
            endWork()
        }
        synchronized(this) {
            if (shutdownFailed) return
            if (quiescence.canRelease(true, recreating)) {
                publishEnded(context, current)
            } else {
                main.post { finishOrPublish(context, current) }
            }
        }
    }

    private fun drainNative() {
        if (!nativeInitialized) return
        org.dolphinemu.dolphinemu.features.input.model.ControllerInterface.stopCallbacks()
        org.dolphinemu.dolphinemu.features.settings.model.AchievementModel.shutdown()
        DirectoryInitialization.shutdownNative()
        nativeInitialized = false
    }

    @Synchronized
    fun startRun(run: () -> Unit) {
        if (stopping || worker != null || token == null) return
        val context = DolphinApplication.getAppContext()
        val state = EngineSession.snapshot(context)?.getString("state")
        if (state != "running" && state != "paused") {
            stop()
            return
        }
        val current = requireNotNull(token)
        val thread = Thread({
            try {
                run()
            } catch (error: Exception) {
                main.post { activity.get()?.showBootError(error) }
            } catch (error: LinkageError) {
                main.post {
                    activity.get()?.showBootError(
                        IOException("GameCube/Wii JNI entry point is unavailable", error)
                    )
                }
            } finally {
                // Run performs Core::Shutdown. Join its Java owner too before publishing ended.
                val owner = Thread.currentThread()
                Thread({
                    owner.join()
                    val cancellation = synchronized(this) {
                        stopping = true
                        stopper
                    }
                    cancellation?.join()
                    complete(context, current)
                }, "DolphinShutdownJoin").start()
            }
        }, "DolphinNativeEmulation")
        worker = thread
        // Reset native cancellation only after this session's stop handler is installed.
        NativeLibrary.SetIsBooting()
        thread.start()
    }

    @Synchronized
    fun stop() {
        if (token == null || stopping) return
        stopping = true
        val context = requireNotNull(sessionContext)
        EngineSession.update(context, requireNotNull(token), "stopping")
        if (worker == null) {
            complete(context, requireNotNull(token))
        } else {
            // Patched native pending-stop flag survives Starting and a missing surface.
            stopper = Thread({ NativeLibrary.StopEmulation() }, "DolphinStop").also { it.start() }
        }
    }

    @Synchronized
    private fun complete(context: Context, current: String) {
        if (token != current) return
        stopping = true
        EngineSession.update(context, current, "stopping")
        worker = null
        stopper = null
        shutdownReady = true
        main.post { finishOrPublish(context, current) }
    }

    @Synchronized
    private fun finishOrPublish(context: Context, current: String) {
        if (token != current) return
        if (quiescence.workers != 0) return
        val host = activity.get()
        // Settings/conversion activities can still save native INIs during onStop/onDestroy.
        activities.toList().filter { it !== host && !it.isDestroyed }.forEach { it.finish() }
        if (host != null && !host.isDestroyed) {
            host.finishAfterShutdown()
        } else if (quiescence.canRelease(shutdownReady, recreating)) {
            publishEnded(context, current)
        }
    }

    @Synchronized
    fun onDestroyed(host: DolphinActivity) {
        onHostDestroyed(host)
        if (activity.get() !== host) return
        activity.clear()
        recreating = host.isChangingConfigurations
        if (recreating) return
        val current = token ?: return
        if (shutdownReady) {
            main.post {
                finishOrPublish(host.applicationContext, current)
            }
        } else {
            stop()
        }
    }

    @Synchronized
    private fun publishEnded(context: Context, current: String) {
        if (token != current || shutdownFailed) return
        // Finish and fragment teardown precede ended, so singleTask cannot reuse the old ROM.
        if (!quiescence.canRelease(shutdownReady, recreating)) return
        if (!backgroundDrained) {
            if (backgroundDraining) return
            backgroundDraining = true
            val drain = Thread({
                try {
                    drainNative()
                    val owner = Thread.currentThread()
                    Thread({
                        owner.join()
                        main.post {
                            synchronized(this) {
                                if (token == current) {
                                    backgroundDrained = true
                                    publishEnded(context, current)
                                }
                            }
                        }
                    }, "DolphinBackgroundJoin").start()
                } catch (error: Throwable) {
                    // An uncertain native teardown must retain the durable owner.
                    synchronized(this) { shutdownFailed = true }
                    EngineSession.update(context, current, "error")
                }
            }, "DolphinBackgroundShutdown")
            drain.start()
            return
        }
        receiver?.let(context.applicationContext::unregisterReceiver)
        receiver = null
        EngineSession.end(context, current)
        token = null
        sessionContext = null
        shutdownReady = false
        stopping = false
    }

    @Synchronized
    fun update(paused: Boolean) {
        if (!stopping) {
            token?.let {
                EngineSession.update(
                    DolphinApplication.getAppContext(),
                    it,
                    if (paused) "paused" else "running"
                )
            }
        }
    }
}
