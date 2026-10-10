// Copyright 2026 Azahar Emulator Project
// Licensed under GPLv3 or any later version

package org.citra.citra_emu.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager

internal fun secondaryDisplayCandidates(context: Context): List<Display> {
    val manager = context.getSystemService(DisplayManager::class.java)
    val hostId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display.displayId
    } else {
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.displayId
    }
    val presentable = manager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
    return manager.displays.filter { display ->
        (display.displayId != Display.DEFAULT_DISPLAY || presentable.any { it.displayId == display.displayId }) &&
            display.displayId != hostId && display.name != "HiddenDisplay" &&
            display.state != Display.STATE_OFF && display.isValid
    }
}

internal fun selectSecondaryDisplay(context: Context, candidates: List<Display>, preferredId: Int = -1): Display? {
    candidates.firstOrNull { it.displayId == preferredId }?.let { return it }
    val defaultName = context.getSystemService(DisplayManager::class.java).displays
        .firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }?.name
    return candidates.firstOrNull { it.name != defaultName && !it.name.contains("Built", true) }
        ?: candidates.firstOrNull()
}
