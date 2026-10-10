// Copyright 2026 Azahar Emulator Project
// Licensed under GPLv3 or any later version

package org.citra.citra_emu.ui.main

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.citra.citra_emu.NativeLibrary

/** A real HOME task, separate from the task holding the live emulation activity. */
class ConsoleHomeActivity : MainActivity() {
    override fun onResume() {
        NativeLibrary.sEmulationActivity.get()?.let { owner ->
            if (!owner.isFinishing && !owner.isDestroyed) {
                if (NativeLibrary.isRunning()) NativeLibrary.releaseInputForHome()
                owner.secondaryDisplayManager.setActive(false)
            }
        }
        super.onResume()
    }

    override fun onNewIntent(intent: Intent) {
        intent.putExtra(KEY_CONSOLE_HOME, true)
        super.onNewIntent(intent)
    }

    companion object {
        private const val PREVIOUS_LAUNCHER = "consoleHomePreviousLauncher"

        fun menuIntent(context: Context): Intent {
            val role = context.getSystemService(RoleManager::class.java)
            val ownsHomeRole = role.isRoleHeld(RoleManager.ROLE_HOME)
            val target = if (ownsHomeRole) {
                ConsoleHomeActivity::class.java
            } else {
                MainActivity::class.java
            }
            return Intent(context, target).apply {
                if (ownsHomeRole) {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_HOME)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra(KEY_CONSOLE_HOME, true)
            }
        }

        fun roleRequest(context: Context): Intent {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            resolved?.activityInfo?.takeIf { it.packageName != context.packageName }?.let {
                PreferenceManager.getDefaultSharedPreferences(context).edit {
                    putString(PREVIOUS_LAUNCHER, ComponentName(it.packageName, it.name).flattenToString())
                }
            }
            return context.getSystemService(RoleManager::class.java)
                .createRequestRoleIntent(RoleManager.ROLE_HOME)
        }

        fun openAndroidLauncher(context: Context) {
            val stored = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREVIOUS_LAUNCHER, null)
            val component = stored?.let(ComponentName::unflattenFromString)
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val available = component != null &&
                context.packageManager.resolveActivity(launcher, 0)?.activityInfo?.exported == true
            // The system chooser is the recovery path if the previous launcher was removed.
            context.startActivity(if (available) launcher else Intent(Settings.ACTION_HOME_SETTINGS))
        }
    }
}
