package org.calamansi.nds

import me.magnum.melonds.domain.model.Rect

fun ndsScreenRect(width: Int, height: Int, screens: Int, maximumScale: Int): Rect {
    require(width >= 4 && screens in 1..2 && height >= 3 * screens)
    val scale = minOf(maximumScale.coerceAtLeast(0), width / 256, height / (192 * screens))
    // Below native size, retain exact 4:3 bounds rather than stretch either axis.
    val unit = if (scale > 0) 64 * scale else minOf(width / 4, height / (3 * screens))
    val screenWidth = 4 * unit
    val screenHeight = 3 * unit
    return Rect(
        (width - screenWidth) / 2,
        (height - screenHeight * screens) / 2,
        screenWidth,
        screenHeight
    )
}
