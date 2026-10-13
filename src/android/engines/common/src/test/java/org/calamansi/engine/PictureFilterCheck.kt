package org.calamansi.engine

fun main() {
    for (x in -8..8) {
        for (y in -8..8) check(PictureFilter.OFF.argbAt(x, y) == 0)
    }
    val crt = PictureFilter.CRT
    check(crt.argbAt(0, 0) == 0)
    check(crt.argbAt(0, crt.height - 1).ushr(24) in 1..254)
    check(crt.argbAt(0, crt.height) == 0)
    check(crt.argbAt(-1, -1) == crt.argbAt(0, crt.height - 1))
    val lcd = PictureFilter.LCD
    check(lcd.argbAt(0, 0) == 0)
    check(lcd.argbAt(lcd.width - 1, 0).ushr(24) in 1..254)
    check(lcd.argbAt(0, lcd.height - 1).ushr(24) in 1..254)
    check(lcd.argbAt(lcd.width, lcd.height) == 0)
    check(lcd.argbAt(-1, -1) == lcd.argbAt(lcd.width - 1, lcd.height - 1))
    for (filter in PictureFilter.entries) {
        check(PictureFilter.fromId(filter.id) == filter)
        for (x in 0 until filter.width) {
            for (y in 0 until filter.height) {
                // Darken without tinting the picture.
                check(filter.argbAt(x, y) and 0x00ffffff == 0)
            }
        }
    }
    check(runCatching { PictureFilter.fromId(-1) }.isFailure)
    check(runCatching { PictureFilter.fromId(PictureFilter.entries.size) }.isFailure)
    println(
        "Off pass-through, CRT/LCD phase boundaries, hue preservation and invalid filter IDs checked"
    )
}
