package org.calamansi.nds

import me.magnum.melonds.domain.model.Rect

fun main() {
    val sharedScale = minOf(1920 / 256, 1080 / 192, 1240 / 256, 1080 / 192)
    val upper = ndsScreenRect(1920, 1080, 1, sharedScale)
    val lower = ndsScreenRect(1240, 1080, 1, sharedScale)
    check(upper == Rect(448, 156, 1024, 768))
    check(lower == Rect(108, 156, 1024, 768))
    check(upper.y == lower.y && upper.width == lower.width && upper.height == lower.height)
    // Display fit is not capped by the renderer's maximum internal resolution.
    check(ndsScreenRect(4096, 3072, 1, 16) == Rect(0, 0, 4096, 3072))
    val stacked = ndsScreenRect(1920, 1080, 2, 3)
    check(stacked == Rect(704, 156, 512, 384))
    check(stacked.y + 2 * stacked.height <= 1080)
    val odd = ndsScreenRect(1241, 1081, 1, sharedScale)
    check(odd.x == 108 && odd.y == 156 && odd.width == 1024 && odd.height == 768)
    val small = ndsScreenRect(200, 150, 1, 3)
    check(small.width <= 200 && small.height <= 150)
    check(small.width * 192 == small.height * 256)
    check(runCatching { ndsScreenRect(0, 1080, 1, 3) }.isFailure)
    check(runCatching { ndsScreenRect(1240, 1080, 3, 3) }.isFailure)
    println("NDS maximum dual-panel integer fit and stacked bounds checked")
}
