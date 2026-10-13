// SPDX-License-Identifier: GPL-2.0-or-later
package org.calamansi.dolphin

/** Counts teardown and uncancellable native work, guarded by DolphinRuntime's monitor. */
internal class DolphinQuiescence {
    private var activities = 0
    private val pendingRecreations = HashMap<Any, Int>()
    var workers = 0
        private set

    fun activityCreated(type: Any) {
        when (val pending = pendingRecreations[type] ?: 0) {
            0 -> activities++
            1 -> pendingRecreations.remove(type)
            else -> pendingRecreations[type] = pending - 1
        }
    }
    fun activityDestroyed(type: Any, recreating: Boolean) {
        check(activities > 0)
        if (recreating) {
            pendingRecreations[type] = (pendingRecreations[type] ?: 0) + 1
        } else {
            activities--
        }
    }
    fun workStarted() {
        workers++
    }
    fun workFinished() {
        check(workers > 0)
        workers--
    }
    fun canRelease(nativeStopped: Boolean, recreating: Boolean): Boolean =
        nativeStopped && !recreating && activities == 0 && workers == 0
}
