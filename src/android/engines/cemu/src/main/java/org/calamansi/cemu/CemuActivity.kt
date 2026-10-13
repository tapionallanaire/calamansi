// SPDX-License-Identifier: MPL-2.0
package org.calamansi.cemu

import android.app.Activity
import android.app.AlertDialog
import android.app.Presentation
import android.content.BroadcastReceiver
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import info.cemu.cemu.input.InputManager
import info.cemu.cemu.input.SensorManager
import info.cemu.cemu.nativeinterface.NativeEmulation
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeSwkbd
import java.lang.ref.WeakReference
import org.calamansi.engine.EngineFiles
import org.calamansi.engine.EngineSession
import org.calamansi.engine.NintendoPlatform
import org.calamansi.engine.PictureFilters

class CemuActivity :
    Activity(),
    DisplayManager.DisplayListener {
    @Volatile private var token: String? = null
    private var stopReceiver: BroadcastReceiver? = null
    private lateinit var primary: SurfaceView
    private lateinit var status: TextView
    private lateinit var displayManager: DisplayManager
    private var pad: SurfaceView? = null
    private var presentation: Presentation? = null
    private var initialized = false
    private var booting = false
    private var started = false

    @Volatile private var stopping = false
    private var unsafeStop = false
    private var nativeStopped = false
    private var activityDestroyed = false
    private var swapped = false
    private var resumed = false
    private var keyboard: AlertDialog? = null
    private var pendingKeyboardText: String? = null
    private var pendingKeyboardLength = -1
    private val input = InputManager()
    private var sensors: SensorManager? = null
    private val configuredDevices = HashSet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        CemuRuntime.attach(this)
        val unsupported = CemuRuntime.unsupportedReason()
        if (unsupported != null) {
            showFatal(unsupported)
            return
        }
        val missing = CemuRuntime.requiredUserFiles()
        if (missing.isNotEmpty()) {
            showFatal(
                "Wii U emulation needs your own Wii U shared-font dump. Missing:\n${missing.joinToString(
                    "\n"
                )}\nAlternatively provide a valid shareddata.bin in ${CemuRuntime.root()}. Calamansi does not redistribute Nintendo fonts."
            )
            return
        }
        val uri = intent.data
        if (uri == null) {
            showFatal("No Wii U title was supplied.")
            return
        }
        try {
            token = EngineSession.begin(
                this,
                NintendoPlatform.WII_U,
                uri,
                intent.getStringExtra(Intent.EXTRA_TITLE).orEmpty(),
                CemuActivity::class.java
            )
            CemuRuntime.bindSession(requireNotNull(token))
            stopReceiver =
                EngineSession.registerStopHandler(applicationContext, requireNotNull(token), ::stop)
        } catch (
            error: Exception
        ) {
            showFatal(error.message ?: "Another engine is still running.")
            return
        }
        CemuRuntime.errorListener = { error -> fail(error) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply {
            text = "Starting Wii U…"
            setPadding(12, 8, 12, 8)
        }
        layout.addView(status)
        primary = screen(this, true)
        layout.addView(primary, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this)
        actions.addView(
            Button(this).apply {
                text = "TV / GamePad"
                setOnClickListener {
                    if (started && !stopping && !unsafeStop) {
                        swapped = !swapped
                        NativeEmulation.setReplaceTVWithPadView(swapped)
                        status.text =
                            if (swapped) "GamePad on primary display" else "TV on primary display"
                    }
                }
            }
        )
        actions.addView(
            Button(this).apply {
                text = "Settings"
                setOnClickListener {
                    startActivity(
                        Intent().setClassName(
                            this@CemuActivity,
                            "org.calamansi.cemu.CemuSettingsActivity"
                        ).apply {
                            data = intent.data
                            putExtra(EngineFiles.EXTRA_PLATFORM, NintendoPlatform.WII_U.name)
                            putExtra(Intent.EXTRA_TITLE, intent.getStringExtra(Intent.EXTRA_TITLE))
                        }
                    )
                }
            }
        )
        actions.addView(
            Button(this).apply {
                text = "Stop"
                setOnClickListener { confirmStop() }
            }
        )
        layout.addView(actions)
        setContentView(layout)
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(this, null)
        updatePadDisplay()
        CemuRuntime.execute({ CemuRuntime.initialize() }) { result ->
            result.onSuccess {
                if (stopping || activityDestroyed) return@onSuccess
                initialized = true
                sensors = SensorManager(this)
                tryStart()
            }.onFailure { if (!stopping && !activityDestroyed) fail(it) }
        }
    }

    private fun screen(context: android.content.Context, mainCanvas: Boolean): SurfaceView =
        SurfaceView(context).apply {
            contentDescription = if (mainCanvas) "Wii U TV screen" else "Wii U GamePad touchscreen"
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) =
                    Unit // surfaceChanged supplies its physical size.
                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int
                ) {
                    if (mainCanvas && !started && !booting) {
                        tryStart()
                    } else if ((started || booting) && !stopping && !unsafeStop) {
                        try {
                            CemuRuntime.surface(holder.surface, width, height, mainCanvas)
                        } catch (error: Exception) {
                            fail(error)
                        }
                    }
                }
                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    // Android requires all native use to finish before returning this callback.
                    if (initialized) {
                        try {
                            CemuRuntime.surface(null, 0, 0, mainCanvas)
                        } catch (error: Exception) {
                            fail(error)
                        }
                    }
                }
            })
            setOnTouchListener { view, event ->
                if (!started || stopping || unsafeStop ||
                    (mainCanvas && !swapped)
                ) {
                    return@setOnTouchListener false
                }
                val x = event.x.toInt().coerceIn(0, view.width.coerceAtLeast(1) - 1)
                val y = event.y.toInt().coerceIn(0, view.height.coerceAtLeast(1) - 1)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> NativeInput.onTouchDown(x, y, mainCanvas)

                    MotionEvent.ACTION_MOVE -> NativeInput.onTouchMove(x, y, mainCanvas)

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> NativeInput.onTouchUp(
                        x,
                        y,
                        mainCanvas
                    )
                }
                true
            }
            PictureFilters.attach(this)
        }

    private fun tryStart() {
        if (!initialized || booting || started || stopping || unsafeStop ||
            !primary.holder.surface.isValid ||
            primary.width == 0 ||
            primary.height == 0
        ) {
            return
        }
        booting = true
        val uri = intent.data ?: return
        val session = token ?: return
        val surface = primary.holder.surface
        val width = primary.width
        val height = primary.height
        val padSurface = pad?.holder?.surface
        val padWidth = pad?.width ?: 0
        val padHeight = pad?.height ?: 0
        CemuRuntime.execute({
            CemuRuntime.start(uri, surface, width, height, padSurface, padWidth, padHeight)
            if (!stopping && token == session) EngineSession.update(this, session, "running")
        }) { result ->
            booting = false
            result.onSuccess {
                if (token != session || stopping) return@onSuccess
                started = true
                status.text =
                    "Running — Wii U emulation continues in background; Stop before switching engines."
                if (resumed) startSensors()
            }.onFailure(::fail)
        }
    }

    private fun updatePadDisplay() {
        if (!::displayManager.isInitialized || stopping) return
        val display = displayManager.getDisplays(
            DisplayManager.DISPLAY_CATEGORY_PRESENTATION
        ).firstOrNull()
        if (display?.displayId == presentation?.display?.displayId) return
        presentation?.dismiss()
        presentation = null
        pad = null
        if (display == null) {
            if (::status.isInitialized) {
                status.text =
                    "Single screen: use TV / GamePad to swap. Wii U emulation keeps running in background."
            }
            return
        }
        val next = object : Presentation(this, display) {
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                pad = screen(context, false)
                setContentView(requireNotNull(pad))
            }
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                this@CemuActivity.dispatchKeyEvent(event)
            override fun onGenericMotionEvent(event: MotionEvent): Boolean =
                this@CemuActivity.onGenericMotionEvent(event)
        }
        try {
            next.show()
            presentation = next
        } catch (
            error: WindowManager.InvalidDisplayException
        ) {
            status.text = "GamePad display unavailable: ${error.message}. Use TV / GamePad to swap."
        }
    }

    override fun onDisplayAdded(displayId: Int) = updatePadDisplay()
    override fun onDisplayRemoved(displayId: Int) = updatePadDisplay()
    override fun onDisplayChanged(displayId: Int) = updatePadDisplay()

    private fun configureController(event: InputEvent) {
        val device = event.device ?: return
        if (!configuredDevices.add(device.descriptor)) return
        if (NativeInput.getControllerMappings(0).isNotEmpty()) return
        val buttons = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to NativeInput.VPAD_BUTTON_A,
            KeyEvent.KEYCODE_BUTTON_B to NativeInput.VPAD_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X to NativeInput.VPAD_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_Y to NativeInput.VPAD_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1 to NativeInput.VPAD_BUTTON_L,
            KeyEvent.KEYCODE_BUTTON_R1 to NativeInput.VPAD_BUTTON_R,
            KeyEvent.KEYCODE_BUTTON_L2 to NativeInput.VPAD_BUTTON_ZL,
            KeyEvent.KEYCODE_BUTTON_R2 to NativeInput.VPAD_BUTTON_ZR,
            KeyEvent.KEYCODE_BUTTON_START to NativeInput.VPAD_BUTTON_PLUS,
            KeyEvent.KEYCODE_BUTTON_SELECT to NativeInput.VPAD_BUTTON_MINUS,
            KeyEvent.KEYCODE_DPAD_UP to NativeInput.VPAD_BUTTON_UP,
            KeyEvent.KEYCODE_DPAD_DOWN to NativeInput.VPAD_BUTTON_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT to NativeInput.VPAD_BUTTON_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT to NativeInput.VPAD_BUTTON_RIGHT,
            KeyEvent.KEYCODE_BUTTON_THUMBL to NativeInput.VPAD_BUTTON_STICKL,
            KeyEvent.KEYCODE_BUTTON_THUMBR to NativeInput.VPAD_BUTTON_STICKR,
            NativeInput.AXIS_X_POS to NativeInput.VPAD_BUTTON_STICKL_RIGHT,
            NativeInput.AXIS_X_NEG to NativeInput.VPAD_BUTTON_STICKL_LEFT,
            NativeInput.AXIS_Y_POS to NativeInput.VPAD_BUTTON_STICKL_DOWN,
            NativeInput.AXIS_Y_NEG to NativeInput.VPAD_BUTTON_STICKL_UP,
            NativeInput.ROTATION_X_POS to NativeInput.VPAD_BUTTON_STICKR_RIGHT,
            NativeInput.ROTATION_X_NEG to NativeInput.VPAD_BUTTON_STICKR_LEFT,
            NativeInput.ROTATION_Y_POS to NativeInput.VPAD_BUTTON_STICKR_DOWN,
            NativeInput.ROTATION_Y_NEG to NativeInput.VPAD_BUTTON_STICKR_UP
        )
        for ((key, mapping) in buttons) {
            NativeInput.setControllerMapping(device.descriptor, device.name, 0, mapping, key)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (started && !stopping && !unsafeStop && input.onKeyEvent(event)) {
            try {
                configureController(event)
            } catch (error: Exception) {
                fail(error)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (started && !stopping && !unsafeStop &&
            input.isMotionEventFromJoystickOrGamepad(event)
        ) {
            try {
                configureController(event)
            } catch (error: Exception) {
                fail(error)
                return true
            }
            val device = event.device
            if (device.getMotionRange(MotionEvent.AXIS_HAT_X) != null) {
                val x = event.getAxisValue(MotionEvent.AXIS_HAT_X)
                NativeInput.onNativeKey(
                    device.descriptor,
                    device.name,
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    x < -0.5f
                )
                NativeInput.onNativeKey(
                    device.descriptor,
                    device.name,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    x > 0.5f
                )
            }
            if (device.getMotionRange(MotionEvent.AXIS_HAT_Y) != null) {
                val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
                NativeInput.onNativeKey(
                    device.descriptor,
                    device.name,
                    KeyEvent.KEYCODE_DPAD_UP,
                    y < -0.5f
                )
                NativeInput.onNativeKey(
                    device.descriptor,
                    device.name,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    y > 0.5f
                )
            }
            if (device.getMotionRange(MotionEvent.AXIS_LTRIGGER) != null) {
                NativeInput.onNativeKey(
                    device.descriptor,
                    device.name,
                    KeyEvent.KEYCODE_BUTTON_L2,
                    event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.5f
                )
            }
            if (device.getMotionRange(MotionEvent.AXIS_RTRIGGER) != null) {
                NativeInput.onNativeKey(
                    device.descriptor,
                    device.name,
                    KeyEvent.KEYCODE_BUTTON_R2,
                    event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.5f
                )
            }
            if (input.onMotionEvent(event)) return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun startSensors() {
        sensors?.setIsLandscape(
            resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
        )
        sensors?.startListening()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (started && !stopping && !unsafeStop) startSensors()
        if (pendingKeyboardLength >= 0) {
            val length = pendingKeyboardLength
            pendingKeyboardLength = -1
            showKeyboard(pendingKeyboardText, length)
            pendingKeyboardText = null
        }
    }

    override fun onPause() {
        resumed = false
        sensors?.pauseListening()
        if (started && !stopping) {
            try {
                NativeInput.releaseInputs()
            } catch (error: Exception) {
                fail(error)
            }
        }
        // ponytail: upstream has no whole-core pause; report running while backgrounded.
        // Upgrade only with a CPU/GPU/audio/IOSU coordinated pause primitive, never guest suspend.
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data != null && intent.data != this.intent.data) {
            AlertDialog.Builder(
                this
            ).setMessage("Stop the current Wii U title before loading another.")
                .setPositiveButton(android.R.string.ok, null).show()
        }
        // REORDER_TO_FRONT with no URI resumes the existing title without reloading it.
    }

    @Deprecated("Android Activity callback")
    override fun onBackPressed() = confirmStop()

    private fun confirmStop() {
        AlertDialog.Builder(this).setTitle("Stop Wii U emulation?")
            .setMessage(
                "In-game save data will be flushed before exit. Unsaved game progress is lost; Wii U emulation cannot pause or create a save state here."
            )
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton("Save and stop") {
                    _,
                    _
                ->
                stop()
            }.show()
    }

    private fun stop() {
        val session = token ?: return
        if (stopping) return
        stopping = true
        CemuRuntime.closeSession(session)
        sensors?.pauseListening()
        if (::status.isInitialized) status.text = "Stopping — flushing Wii U saves…"
        EngineSession.update(this, session, "stopping")
        CemuRuntime.execute({ CemuRuntime.stop() }) { result ->
            result.onSuccess {
                if (token != session) return@onSuccess
                nativeStopped = true
                initialized = false
                started = false
                presentation?.dismiss()
                presentation = null
                if (activityDestroyed) releaseSession() else finish()
            }.onFailure { error ->
                if (token != session) return@onFailure
                unsafeStop = true
                EngineSession.update(this, session, "error")
                if (::status.isInitialized) {
                    status.text =
                        "Stop failed — saves/session remain owned. Resolve the error and retry Stop."
                }
                stopping = false
                showError(
                    "Cannot safely end Wii U emulation. The session remains active; do not switch engines. ${error.message}"
                )
            }
        }
    }

    private fun releaseSession() {
        if (!nativeStopped || !activityDestroyed) return
        val session = token ?: return
        EngineSession.end(this, session)
        token = null
        stopReceiver?.let { applicationContext.unregisterReceiver(it) }
        stopReceiver = null
    }

    private fun fail(error: Throwable) {
        if (::status.isInitialized) status.text = "Wii U error: ${error.message}"
        showError(error.message ?: error.javaClass.simpleName)
        stop()
    }

    private fun showError(message: String) {
        if (!isFinishing && !isDestroyed) {
            AlertDialog.Builder(
                this
            ).setTitle(
                "Wii U"
            ).setMessage(message).setPositiveButton(android.R.string.ok, null).show()
        }
    }

    private fun showFatal(message: String) {
        AlertDialog.Builder(this).setTitle("Wii U unavailable").setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                finish()
            }.setOnCancelListener { finish() }.show()
    }

    fun showKeyboard(initialText: String?, maxLength: Int) {
        runOnUiThread {
            if (stopping || unsafeStop || (!started && !booting)) return@runOnUiThread
            if (!resumed || isDestroyed) {
                pendingKeyboardText = initialText
                pendingKeyboardLength = maxLength.coerceAtLeast(0)
                return@runOnUiThread
            }
            keyboard?.dismiss()
            val edit = EditText(this).apply {
                filters = arrayOf(InputFilter.LengthFilter(maxLength.coerceAtLeast(0)))
                setText(initialText.orEmpty())
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(
                        s: CharSequence?,
                        start: Int,
                        count: Int,
                        after: Int
                    ) = Unit
                    override fun onTextChanged(
                        s: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int
                    ) {
                        if ((started || booting) && !stopping &&
                            !unsafeStop
                        ) {
                            NativeSwkbd.onTextChanged(s?.toString())
                        }
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
            keyboard = AlertDialog.Builder(this).setTitle("Wii U text input").setView(edit)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if ((started || booting) && !stopping && !unsafeStop) {
                        NativeSwkbd.setCurrentInputText(edit.text.toString())
                        NativeSwkbd.onFinishedInputEdit()
                    }
                }.setOnCancelListener {
                    if ((started || booting) && !stopping &&
                        !unsafeStop
                    ) {
                        NativeSwkbd.onFinishedInputEdit()
                    }
                }.create()
            keyboard?.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )
            keyboard?.show()
            edit.requestFocus()
        }
    }

    fun hideKeyboard() = runOnUiThread {
        pendingKeyboardText = null
        pendingKeyboardLength = -1
        keyboard?.dismiss()
        keyboard = null
    }

    override fun onDestroy() {
        sensors?.pauseListening()
        if (::displayManager.isInitialized) displayManager.unregisterDisplayListener(this)
        presentation?.dismiss()
        keyboard?.dismiss()
        if (current.get() === this) current.clear()
        CemuRuntime.errorListener = null
        if (token != null && !stopping) stop()
        super.onDestroy()
        activityDestroyed = true
        // Post past the final lifecycle callback; singleTask must be gone before
        // another engine (or a fresh Cemu Activity) can acquire ownership.
        android.os.Handler(mainLooper).post { releaseSession() }
    }

    companion object {
        internal var current = WeakReference<CemuActivity>(null)
    }
}
