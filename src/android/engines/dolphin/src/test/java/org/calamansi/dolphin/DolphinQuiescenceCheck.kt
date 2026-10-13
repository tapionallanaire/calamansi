package org.calamansi.dolphin

fun main() {
    val ownership = DolphinQuiescence()
    ownership.activityCreated("game")
    ownership.activityCreated("settings") // The native editor sits above emulation.
    ownership.workStarted() // A NAND conversion cannot be cancelled by finishing its dialog.
    check(!ownership.canRelease(false, false))
    ownership.activityDestroyed("game", false)
    check(!ownership.canRelease(true, false))
    ownership.activityDestroyed("settings", false)
    check(!ownership.canRelease(true, false))
    ownership.workFinished()
    check(!ownership.canRelease(true, true)) // Recreation gaps retain ownership.
    check(ownership.canRelease(true, false))
    ownership.activityCreated("settings")
    check(!ownership.canRelease(true, false))
    ownership.activityDestroyed("settings", true)
    ownership.activityCreated("picker")
    ownership.activityDestroyed("picker", false)
    check(!ownership.canRelease(true, false)) // A different Activity cannot consume recreation.
    ownership.activityCreated("settings")
    ownership.activityDestroyed("settings", false)
    check(ownership.canRelease(true, false))
}
