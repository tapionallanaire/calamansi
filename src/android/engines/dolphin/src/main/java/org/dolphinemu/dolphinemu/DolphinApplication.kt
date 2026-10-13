// SPDX-License-Identifier: GPL-2.0-or-later
package org.dolphinemu.dolphinemu

import android.app.Activity
import android.app.Application
import android.content.Context
import android.hardware.usb.UsbManager
import org.dolphinemu.dolphinemu.utils.ActivityTracker
import org.dolphinemu.dolphinemu.utils.GCAdapter
import org.dolphinemu.dolphinemu.utils.WiimoteAdapter

/** Original JNI context namespace, backed by the real host Application and lifecycle. */
object DolphinApplication {
    private lateinit var application: Application
    private val tracker = ActivityTracker()
    private var attached = false

    @Synchronized
    fun attach(context: Context) {
        if (attached) return
        if (!::application.isInitialized) {
            application = context.applicationContext as Application
        }
        System.loadLibrary("calamansi_dolphin")
        application.registerActivityLifecycleCallbacks(tracker)
        application.registerActivityLifecycleCallbacks(
            org.calamansi.dolphin.DolphinRuntime.lifecycleCallbacks
        )
        org.calamansi.dolphin.DolphinRuntime.markLifecycleRegistered()
        val manager = application.getSystemService(Context.USB_SERVICE) as UsbManager
        GCAdapter.manager = manager
        WiimoteAdapter.manager = manager
        attached = true
    }

    @JvmStatic fun getAppContext(): Context = application

    @JvmStatic fun getAppActivity(): Activity? = tracker.currentActivity
}
