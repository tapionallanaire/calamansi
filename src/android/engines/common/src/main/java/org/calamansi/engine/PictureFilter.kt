package org.calamansi.engine

// ponytail: display masks only; native post-processing hooks if convolution/upscaling is needed.
/** Stable app-wide display masks; renderer sampling and internal resolution remain engine-owned. */
enum class PictureFilter(val id: Int, val width: Int, val height: Int) {
    OFF(0, 1, 1),
    CRT(1, 1, 4),
    LCD(2, 3, 3);

    fun argbAt(x: Int, y: Int): Int {
        val column = Math.floorMod(x, width)
        val row = Math.floorMod(y, height)
        return when (this) {
            OFF -> 0

            CRT -> if (row == height - 1) 0x44000000 else 0

            LCD -> when {
                column == width - 1 && row == height - 1 -> 0x3c000000
                column == width - 1 || row == height - 1 -> 0x28000000
                else -> 0
            }
        }
    }

    companion object {
        fun fromId(id: Int): PictureFilter = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown picture filter: $id")
    }
}
