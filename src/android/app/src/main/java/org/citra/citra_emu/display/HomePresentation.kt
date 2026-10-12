// Copyright 2026 Azahar Emulator Project
// Licensed under GPLv3 or any later version

package org.citra.citra_emu.display

import android.app.Activity
import android.app.Presentation
import android.content.res.Configuration
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.view.ContextThemeWrapper
import org.citra.citra_emu.R
import org.citra.citra_emu.databinding.ViewHomeLibraryBinding

internal class HomePresentation(
    private val activity: Activity,
    display: Display,
    private val onReady: (ViewHomeLibraryBinding) -> Unit,
    private val onKey: (KeyEvent) -> Boolean,
    private val onTouch: () -> Unit
) : Presentation(activity, display) {
    private var libraryRoot: View? = null
    var inputEnabled = true
        set(value) {
            field = value
            window?.setFlags(
                if (value) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            )
            libraryRoot?.importantForAccessibility =
                if (value) {
                    View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                } else {
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.attributes?.setTitle("Calamansi HOME")
        val displayContext = ContextThemeWrapper(context, R.style.Theme_Azahar_Home_Classic).apply {
            applyOverrideConfiguration(
                Configuration().apply {
                    uiMode = Configuration.UI_MODE_NIGHT_NO
                }
            )
        }
        val binding = ViewHomeLibraryBinding.inflate(LayoutInflater.from(displayContext))
        libraryRoot = binding.root
        if (!inputEnabled) {
            binding.root.importantForAccessibility =
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        setContentView(binding.root)
        onReady(binding)
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!inputEnabled) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onTouch()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        !inputEnabled || super.dispatchGenericMotionEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        !inputEnabled || onKey(event) || super.dispatchKeyEvent(event)
}
