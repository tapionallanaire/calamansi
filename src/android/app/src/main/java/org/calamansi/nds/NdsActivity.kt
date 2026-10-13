package org.calamansi.nds

import android.app.AlertDialog
import android.app.Presentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import me.magnum.melonds.MelonDSAndroidInterface
import me.magnum.melonds.MelonEmulator
import me.magnum.melonds.common.UriFileHandler
import me.magnum.melonds.domain.model.Input
import me.magnum.melonds.domain.model.Rect
import me.magnum.melonds.impl.emulator.EmulatorEventType
import me.magnum.melonds.impl.emulator.EmulatorMessageQueue
import me.magnum.melonds.ui.emulator.DSRenderer
import me.magnum.melonds.ui.emulator.EmulatorSurfaceView
import me.magnum.melonds.ui.emulator.model.RuntimeRendererConfiguration
import me.magnum.melonds.ui.emulator.render.FrameRenderCoordinator
import org.calamansi.engine.EngineFiles
import org.calamansi.engine.EngineId
import org.calamansi.engine.EngineSession
import org.calamansi.engine.NintendoPlatform
import org.citra.citra_emu.R
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.ui.main.ConsoleHomeActivity
import org.citra.citra_emu.utils.GameIconUtils
import org.citra.citra_emu.utils.ThemeUtil

class NdsActivity :
    AppCompatActivity(),
    Choreographer.FrameCallback,
    DisplayManager.DisplayListener {
    private val main = Handler(android.os.Looper.getMainLooper())
    private val thread = HandlerThread("CalamansiNdsSession").apply { start() }
    private val worker = Handler(thread.looper)
    private val screenshot = ByteBuffer.allocateDirect(256 * 384 * 4).order(ByteOrder.nativeOrder())
    private lateinit var uri: Uri
    private lateinit var preferences: NdsPreferences
    private lateinit var shell: NdsShellView
    private lateinit var drawer: DrawerLayout
    private lateinit var menu: NavigationView

    @Volatile private var userPaused = false
    private lateinit var screen: Screen
    private lateinit var displayManager: DisplayManager
    private var coordinator: FrameRenderCoordinator? = null
    private var queue: EmulatorMessageQueue? = null
    private var presentation: Presentation? = null
    private var secondaryScreen: Screen? = null
    private var secondaryShell: NdsShellView? = null
    private var microphoneButton: ImageButton? = null
    private var stopReceiver: BroadcastReceiver? = null
    private var token: String? = null
    private var bootstrap = false
    private var setup = false

    @Volatile private var nativeRunning = false
    private var foreground = false

    @Volatile private var stopping = false
    private var teardownComplete = false
    private var finishWhenStopped = false
    private var frame = 0
    private val axisPressed = mutableSetOf<Input>()
    private val pressed = mutableMapOf<Input, MutableSet<String>>()
    private var stylusPointer = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtil.setTheme(this)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        fullscreen(window)
        val requested = intent.data
        if (requested == null ||
            intent.getStringExtra(EngineFiles.EXTRA_PLATFORM)?.let {
                it != NintendoPlatform.NDS.name
            } ==
            true
        ) {
            error("A Nintendo DS ROM URI is required")
            return
        }
        uri = requested
        preferences = NdsPreferences(this, uri)
        screen = Screen(this, ::layoutScreens)
        setContentView(R.layout.activity_nds)
        drawer = findViewById(R.id.drawer_layout)
        menu = findViewById(R.id.in_game_menu)
        val primaryLayout = findViewById<FrameLayout>(R.id.nds_content)
        configureDrawer()
        primaryLayout.addView(screen.view, FrameLayout.LayoutParams(-1, -1))
        shell = NdsShellView(this)
        primaryLayout.addView(shell, FrameLayout.LayoutParams(-1, -1))
        screen.view.setOnTouchListener { _, event -> touch(event) }
        displayManager = getSystemService(DisplayManager::class.java)
        try {
            val session = EngineSession.begin(
                this,
                NintendoPlatform.NDS,
                uri,
                intent.getStringExtra(Intent.EXTRA_TITLE).orEmpty(),
                NdsActivity::class.java
            )
            token = session
            stopReceiver =
                EngineSession.registerStopHandler(this, session) { main.post { requestStop() } }
        } catch (
            failure: Exception
        ) {
            error(failure.message ?: "Another emulator session is active")
            return
        }
        worker.post {
            try {
                contentResolver.openInputStream(uri)?.use { NdsHeader.validate(it) }
                    ?: throw IllegalStateException("Cannot read DS ROM")
                val saves = File(EngineFiles.root(this, EngineId.NDS), "saves").apply {
                    check(mkdirs() || isDirectory)
                }
                val save = File(saves, "${NdsPreferences.identity(uri)}.sav")
                check(save.exists() || save.createNewFile()) { "Cannot create DS SRAM" }
                check(save.canRead() && save.canWrite()) { "DS SRAM is not writable" }
                System.loadLibrary("melonDS-android-frontend")
                MelonDSAndroidInterface.setup(UriFileHandler(applicationContext))
                bootstrap = true
                queue = EmulatorMessageQueue { event, data ->
                    when (event) {
                        EmulatorEventType.EventEmulatorStop -> main.post { requestStop() }

                        EmulatorEventType.EventRuntimeError -> {
                            val message = ByteArray(data.remaining()).also { data.get(it) }
                                .toString(Charsets.UTF_8)
                            main.post { requestStop(message, false) }
                        }

                        else -> Unit
                    }
                }.also { it.start() }
                setup = true
                MelonEmulator.setupEmulator(preferences.configuration(), null, screenshot)
                MelonEmulator.setMicrophoneEnabled(false)
                val result = MelonEmulator.loadRom(
                    uri,
                    Uri.fromFile(save),
                    MelonEmulator.GbaSlotType.NONE,
                    null,
                    null
                )
                check(!result.isTerminal) { "Nintendo DS emulation rejected the ROM: $result" }
                MelonEmulator.setupCheats(emptyArray())
                coordinator = FrameRenderCoordinator { failure ->
                    main.post { requestStop("DS presentation failed: ${failure.message}", false) }
                }.also { it.addSurface(screen.view) }
                MelonEmulator.startEmulation()
                nativeRunning = !stopping
                main.post {
                    if (!stopping) {
                        refreshDisplays()
                        if (foreground) resume() else pause()
                    }
                }
            } catch (failure: Throwable) {
                main.post { requestStop(failure.message ?: failure.javaClass.simpleName, false) }
            }
        }
    }

    private fun configureDrawer() {
        val game = requireNotNull(
            intent.extras?.let { BundleCompat.getParcelable(it, "game", Game::class.java) }
        ) { "Nintendo DS cartridge metadata is required" }
        menu.getHeaderView(0).apply {
            findViewById<TextView>(R.id.text_game_title).text = game.title
            GameIconUtils.loadGameIcon(game, findViewById<ImageView>(R.id.game_icon))
        }
        for (index in 0 until menu.menu.size()) {
            val item = menu.menu.getItem(index)
            item.isVisible = when (item.itemId) {
                R.id.menu_console_home, R.id.menu_emulation_pause, R.id.menu_overlay_options,
                R.id.menu_lock_drawer, R.id.menu_settings, R.id.menu_exit -> true

                else -> false
            }
        }
        updateDrawerLock()
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                releaseInputs()
                drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
                menu.requestFocus()
            }
            override fun onDrawerClosed(drawerView: View) = updateDrawerLock()
            override fun onDrawerStateChanged(newState: Int) {
                if (newState != DrawerLayout.STATE_IDLE) releaseInputs()
            }
        })
        menu.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_console_home -> {
                    drawer.closeDrawer(GravityCompat.START)
                    startActivity(ConsoleHomeActivity.menuIntent(this))
                }

                R.id.menu_emulation_pause -> {
                    userPaused = !userPaused
                    releaseInputs()
                    if (userPaused) pause() else resume()
                    item.setTitle(
                        if (userPaused) R.string.resume_emulation else R.string.pause_emulation
                    )
                    item.setIcon(if (userPaused) R.drawable.ic_play else R.drawable.ic_pause)
                }

                R.id.menu_overlay_options -> {
                    val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    repeat(2) { index ->
                        val microphone = index == 1
                        actions.addView(
                            Button(this).apply {
                                text = if (microphone) {
                                    "Hold to blow microphone"
                                } else {
                                    "Hold to fast forward"
                                }
                                isEnabled = nativeRunning && !userPaused
                                setOnTouchListener { _, event ->
                                    if (nativeRunning && !userPaused &&
                                        (
                                            event.actionMasked == MotionEvent.ACTION_DOWN ||
                                                event.actionMasked == MotionEvent.ACTION_UP ||
                                                event.actionMasked == MotionEvent.ACTION_CANCEL
                                            )
                                    ) {
                                        val down = event.actionMasked == MotionEvent.ACTION_DOWN
                                        if (microphone) {
                                            MelonEmulator.setMicrophoneEnabled(down)
                                        } else {
                                            MelonEmulator.setFastForwardEnabled(down)
                                        }
                                    }
                                    true
                                }
                            }
                        )
                    }
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.emulation_overlay_options)
                        .setView(actions)
                        .setPositiveButton(android.R.string.ok, null)
                        .setOnDismissListener { releaseInputs() }
                        .show()
                }

                R.id.menu_lock_drawer -> {
                    preferences.editable.edit().putBoolean(
                        "drawer_locked",
                        !preferences.bool("drawer_locked", false)
                    ).apply()
                    updateDrawerLock()
                }

                R.id.menu_settings -> {
                    drawer.closeDrawer(GravityCompat.START)
                    openSettings()
                }

                R.id.menu_exit -> MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.emulation_close_game)
                    .setMessage("Stop Nintendo DS and flush SRAM?")
                    .setPositiveButton(android.R.string.ok) { _, _ -> requestStop() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            true
        }
        onBackPressedDispatcher.addCallback(this) {
            if (drawer.isDrawerOpen(GravityCompat.START)) {
                drawer.closeDrawer(GravityCompat.START)
            } else {
                drawer.openDrawer(GravityCompat.START)
            }
        }
    }

    private fun updateDrawerLock() {
        val locked = preferences.bool("drawer_locked", false)
        menu.menu.findItem(R.id.menu_lock_drawer)
            .setTitle(if (locked) R.string.unlock_drawer else R.string.lock_drawer)
            .setIcon(if (locked) R.drawable.ic_lock else R.drawable.ic_unlocked)
        if (!drawer.isDrawerVisible(GravityCompat.START)) {
            drawer.setDrawerLockMode(
                if (locked) {
                    DrawerLayout.LOCK_MODE_LOCKED_CLOSED
                } else {
                    DrawerLayout.LOCK_MODE_UNLOCKED
                }
            )
        }
    }

    private fun openSettings() {
        startActivity(
            Intent(this, NdsSettingsActivity::class.java).setData(uri)
                .putExtra(Intent.EXTRA_TITLE, intent.getStringExtra(Intent.EXTRA_TITLE))
        )
    }

    private fun drawerOwnsInput(): Boolean =
        userPaused || (::drawer.isInitialized && drawer.isDrawerVisible(GravityCompat.START))

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::uri.isInitialized && intent.data != null && intent.data != uri) {
            Toast.makeText(
                this,
                "Stop the current DS game before starting another",
                Toast.LENGTH_LONG
            ).show()
        }
        // REORDER_TO_FRONT and the same URI resume the existing instance, never boot again.
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        fullscreen(window)
        if (::displayManager.isInitialized) {
            displayManager.registerDisplayListener(this, main)
            refreshDisplays()
        }
        if (nativeRunning && !stopping) resume()
    }

    private fun resume() {
        microphoneButton?.isEnabled = nativeRunning && !userPaused
        val session = token ?: return
        preferences = NdsPreferences(this, uri)
        screen.configure(preferences)
        secondaryScreen?.configure(preferences)
        layoutScreens()
        worker.post {
            if (nativeRunning) {
                try {
                    MelonEmulator.updateEmulatorConfiguration(preferences.configuration())
                    val paused = userPaused
                    if (paused) MelonEmulator.pauseEmulation() else MelonEmulator.resumeEmulation()
                    EngineSession.update(this, session, if (paused) "paused" else "running")
                } catch (failure: Throwable) {
                    main.post { requestStop(failure.message, false) }
                }
            }
        }
        Choreographer.getInstance().removeFrameCallback(this)
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onPause() {
        foreground = false
        Choreographer.getInstance().removeFrameCallback(this)
        releaseInputs()
        if (::displayManager.isInitialized) displayManager.unregisterDisplayListener(this)
        dismissPresentation()
        if (nativeRunning && !stopping) pause()
        super.onPause()
    }

    private fun pause() {
        microphoneButton?.isEnabled = false
        val session = token ?: return
        worker.post {
            if (nativeRunning) {
                MelonEmulator.pauseEmulation()
                EngineSession.update(this, session, "paused")
            }
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!foreground || stopping || !nativeRunning) return
        if (frame++ % (preferences.int("frame_skip", 0).coerceIn(0, 3) + 1) ==
            0
        ) {
            coordinator?.renderFrame(frameTimeNanos + 16_666_666L)
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun requestStop(message: String? = null, finishAfter: Boolean = true) {
        if (stopping) {
            if (finishAfter) {
                finishWhenStopped = true
                if (teardownComplete) finish()
            }
            return
        }
        stopping = true
        finishWhenStopped = finishAfter
        Choreographer.getInstance().removeFrameCallback(this)
        releaseInputs()
        nativeRunning = false
        val session = token
        if (session != null) EngineSession.update(this, session, "stopping")
        worker.post {
            try {
                nativeRunning = false
                // Drain outstanding presentation work without dropping shared render resources.
                coordinator?.quiesce()
                if (setup) {
                    MelonEmulator.stopEmulation()
                    setup = false
                }
                // Native stop joins the emulation thread and SaveManager's SRAM writer first.
                coordinator?.stop()
                coordinator = null
                queue?.cleanup()
                queue = null
                if (bootstrap) {
                    MelonDSAndroidInterface.cleanup()
                    bootstrap = false
                }
                main.post {
                    teardownComplete = true
                    dismissPresentation()
                    if (isDestroyed) {
                        releaseSession()
                    } else if (finishWhenStopped) {
                        finish()
                    } else if (message != null) {
                        if (session != null) EngineSession.update(this, session, "error")
                        error(message)
                    }
                }
            } catch (failure: Throwable) {
                main.post {
                    if (session != null) EngineSession.update(this, session, "error")
                    error("DS shutdown failed; session remains active: ${failure.message}")
                }
            } finally {
                thread.quitSafely()
            }
        }
    }

    override fun onDestroy() {
        if (token != null && !stopping) {
            requestStop()
        } else if (token == null) {
            thread.quitSafely()
        }
        super.onDestroy()
        if (teardownComplete) releaseSession()
    }

    private fun releaseSession() {
        stopReceiver?.let { applicationContext.unregisterReceiver(it) }
        stopReceiver = null
        token?.let { EngineSession.end(applicationContext, it) }
        token = null
    }

    private fun error(message: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(
            this
        ).setTitle("Nintendo DS error").setMessage(message).setPositiveButton("Close") {
                _,
                _
            ->
            finish()
        }.show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (drawerOwnsInput()) return super.dispatchKeyEvent(event)
        if (nativeRunning && ::preferences.isInitialized) {
            val mapped = Input.SYSTEM_BUTTONS.firstOrNull { preferences.key(it) == event.keyCode }
            if (mapped != null &&
                (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP)
            ) {
                sendInput(
                    mapped,
                    "key:${event.deviceId}:${event.keyCode}",
                    event.action == KeyEvent.ACTION_DOWN
                )
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (drawerOwnsInput()) return super.onGenericMotionEvent(event)
        if (nativeRunning && event.isFromSource(InputDevice.SOURCE_JOYSTICK) &&
            preferences.bool("joystick", true)
        ) {
            val dead = preferences.int("dead_zone", 25).coerceIn(0, 75) / 100f
            val x = event.getAxisValue(MotionEvent.AXIS_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y)
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            axis(Input.LEFT, x < -dead || hx < -0.5f)
            axis(Input.RIGHT, x > dead || hx > 0.5f)
            axis(Input.UP, y < -dead || hy < -0.5f)
            axis(Input.DOWN, y > dead || hy > 0.5f)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun axis(input: Input, down: Boolean) {
        if (down && axisPressed.add(input)) sendInput(input, "axis", true)
        if (!down && axisPressed.remove(input)) sendInput(input, "axis", false)
    }

    private fun sendInput(input: Input, source: String, down: Boolean) {
        if (!nativeRunning || drawerOwnsInput()) return
        val sources = pressed.getOrPut(input) { mutableSetOf() }
        val wasDown = sources.isNotEmpty()
        if (down) sources.add(source) else sources.remove(source)
        if (!wasDown && sources.isNotEmpty()) MelonEmulator.onInputDown(input)
        if (wasDown && sources.isEmpty()) MelonEmulator.onInputUp(input)
    }

    private fun releaseInputs() {
        microphoneButton?.isPressed = false
        if (nativeRunning) {
            pressed.filterValues { it.isNotEmpty() }.keys.forEach { MelonEmulator.onInputUp(it) }
            MelonEmulator.onScreenRelease()
            MelonEmulator.setFastForwardEnabled(false)
            MelonEmulator.setMicrophoneEnabled(false)
        }
        pressed.clear()
        axisPressed.clear()
        stylusPointer = -1
    }

    private fun touch(event: MotionEvent, touchedScreen: Screen = screen): Boolean {
        if (!nativeRunning || drawerOwnsInput()) return false
        val rect = touchedScreen.bottom ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) stylusPointer = event.getPointerId(0)
        val index = event.findPointerIndex(stylusPointer)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL ||
            (
                event.actionMasked == MotionEvent.ACTION_POINTER_UP &&
                    event.getPointerId(event.actionIndex) == stylusPointer
                )
        ) {
            MelonEmulator.onScreenRelease()
            stylusPointer = -1
        } else if (index >= 0) {
            val x = event.getX(index)
            val y = event.getY(index)
            if (x >= rect.x && x < rect.right && y >= rect.y && y < rect.bottom) {
                MelonEmulator.onScreenTouch(
                    ((x - rect.x) * 256 / rect.width).toInt().coerceIn(0, 255),
                    (
                        (
                            y -
                                rect.y
                            ) *
                            192 /
                            rect.height
                        ).toInt().coerceIn(0, 191)
                )
            } else {
                MelonEmulator.onScreenRelease()
            }
        }
        return true
    }

    override fun onDisplayAdded(displayId: Int) = refreshDisplays()
    override fun onDisplayRemoved(displayId: Int) = refreshDisplays()
    override fun onDisplayChanged(displayId: Int) = refreshDisplays()

    private fun refreshDisplays() {
        if (!foreground || !nativeRunning || stopping) return
        val target = displayManager.getDisplays(
            DisplayManager.DISPLAY_CATEGORY_PRESENTATION
        ).firstOrNull {
            it.displayId !=
                windowManager.defaultDisplay.displayId &&
                it.name != "HiddenDisplay" &&
                it.name != "WebRTC_ScreenCapture"
        }
        if (presentation?.display?.displayId == target?.displayId) return
        dismissPresentation()
        if (target != null) {
            val external = Screen(this, ::layoutScreens).apply {
                bottomOnly = true
                configure(preferences)
            }
            external.view.setOnTouchListener { _, event -> touch(event, external) }
            val next = object : Presentation(this, target) {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    window?.let { fullscreen(it) }
                    setContentView(
                        FrameLayout(context).apply {
                            addView(external.view, FrameLayout.LayoutParams(-1, -1))
                            secondaryShell = NdsShellView(context, lowerHalf = true)
                            addView(secondaryShell, FrameLayout.LayoutParams(-1, -1))
                            val size = (48 * resources.displayMetrics.density).toInt()
                            microphoneButton = ImageButton(
                                context,
                                null,
                                android.R.attr.borderlessButtonStyle
                            ).apply {
                                setImageDrawable(
                                    ContextCompat.getDrawable(
                                        this@NdsActivity,
                                        R.drawable.ic_microphone
                                    )
                                )
                                imageTintList = ColorStateList.valueOf(Color.DKGRAY)
                                contentDescription = getString(R.string.microphone)
                                isEnabled = nativeRunning && !userPaused
                                setOnTouchListener { _, event ->
                                    if (nativeRunning && !userPaused &&
                                        (
                                            event.actionMasked == MotionEvent.ACTION_DOWN ||
                                                event.actionMasked == MotionEvent.ACTION_UP ||
                                                event.actionMasked == MotionEvent.ACTION_CANCEL
                                            )
                                    ) {
                                        MelonEmulator.setMicrophoneEnabled(
                                            event.actionMasked == MotionEvent.ACTION_DOWN
                                        )
                                    }
                                    false
                                }
                            }
                            addView(
                                microphoneButton,
                                FrameLayout.LayoutParams(
                                    size,
                                    size,
                                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                                )
                            )
                        }
                    )
                }
                override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                    this@NdsActivity.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
                override fun onGenericMotionEvent(event: MotionEvent): Boolean =
                    this@NdsActivity.onGenericMotionEvent(event) ||
                        super.onGenericMotionEvent(event)
            }
            try {
                next.show()
                presentation = next
                secondaryScreen = external
                coordinator?.addSurface(external.view)
                screen.topOnly = true
                layoutScreens()
            } catch (_: WindowManager.InvalidDisplayException) {
                next.dismiss()
                secondaryShell = null
                microphoneButton = null
            }
        }
    }

    private fun dismissPresentation() {
        secondaryScreen?.let { coordinator?.removeSurface(it.view) }
        secondaryScreen = null
        secondaryShell = null
        microphoneButton = null
        presentation?.dismiss()
        presentation = null
        if (::screen.isInitialized) {
            screen.topOnly = false
            screen.bottomOnly = false
            layoutScreens()
        }
    }

    private fun fullscreen(target: Window) {
        WindowCompat.setDecorFitsSystemWindows(target, false)
        WindowInsetsControllerCompat(target, target.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun layoutScreens() {
        if (!::screen.isInitialized) return
        val other = secondaryScreen
        val shared = if (other == null) {
            Int.MAX_VALUE
        } else {
            minOf(
                screen.view.width / 256,
                screen.view.height / 192,
                other.view.width / 256,
                other.view.height / 192
            )
        }
        screen.layout(shared)
        other?.layout(shared)
        shell.frame(if (screen.topOnly) screen.top else null)
        secondaryShell?.frame(other?.bottom)
        other?.bottom?.let { picture ->
            microphoneButton?.let { button ->
                val params = button.layoutParams as FrameLayout.LayoutParams
                val margin = other.view.height - picture.bottom
                button.visibility = if (margin >= params.height) View.VISIBLE else View.INVISIBLE
                val bottom = ((margin - params.height) / 2).coerceAtLeast(0)
                if (params.bottomMargin != bottom) {
                    params.bottomMargin = bottom
                    button.layoutParams = params
                }
            }
        }
    }

    private class Screen(context: Context, dimensionsChanged: () -> Unit) {
        val view = EmulatorSurfaceView(context)
        private val renderer = DSRenderer(context)
        var topOnly = false
        var bottomOnly = false
        var top: Rect? = null
        var bottom: Rect? = null
        init {
            view.setRenderer(renderer)
            org.calamansi.engine.PictureFilters.attach(view)
            view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> dimensionsChanged() }
        }
        fun configure(settings: NdsPreferences) {
            val config = settings.configuration().rendererConfiguration
            renderer.updateRendererConfiguration(
                RuntimeRendererConfiguration(config.videoFiltering, config.resolutionScaling)
            )
        }
        fun layout(scale: Int = Int.MAX_VALUE) {
            val count = if (topOnly || bottomOnly) 1 else 2
            if (view.width < 4 || view.height < 3 * count) return
            val rect = ndsScreenRect(view.width, view.height, count, scale)
            top = if (bottomOnly) null else rect
            bottom = when {
                topOnly -> null
                bottomOnly -> rect
                else -> Rect(rect.x, rect.bottom, rect.width, rect.height)
            }
            renderer.updateScreenAreas(top, bottom, 1f, 1f, false, false)
        }
    }
}
