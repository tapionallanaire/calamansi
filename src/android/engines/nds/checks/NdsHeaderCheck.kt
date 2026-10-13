package org.calamansi.nds

import java.io.ByteArrayInputStream

// Run with the production NdsHeader.kt in the same kotlinc invocation.
fun main() {
    fun valid(): ByteArray = ByteArray(0x160).apply {
        fun u32(offset: Int, value: Int) {
            repeat(4) {
                this[offset + it] =
                    (value ushr (it * 8)).toByte()
            }
        }
        u32(0x20, 0x200)
        u32(0x2c, 0x100)
        u32(0x30, 0x400)
        u32(0x3c, 0x100)
        u32(0x68, 0x600)
    }
    check(NdsHeader.validate(ByteArrayInputStream(valid())).size == 0x160)
    val bannerless = valid().apply { repeat(4) { this[0x68 + it] = 0 } }
    check(NdsHeader.validate(ByteArrayInputStream(bannerless)).size == 0x160)
    check(
        runCatching {
            NdsHeader.validate(ByteArrayInputStream(bannerless), requireBanner = true)
        }.isFailure
    )
    val partialReads = object : ByteArrayInputStream(valid()) {
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            super.read(bytes, offset, minOf(length, 7))
    }
    check(NdsHeader.validate(partialReads).size == 0x160)
    fun rejected(bytes: ByteArray) =
        check(runCatching { NdsHeader.validate(ByteArrayInputStream(bytes)) }.isFailure)
    rejected(ByteArray(2))
    rejected(valid().apply { this[0x12] = 3 })
    rejected(valid().apply { repeat(4) { this[0x2c + it] = 0xff.toByte() } })
    rejected(
        valid().apply {
            this[0x30] = 0
            this[0x31] = 2
        }
    )
    rejected(valid().apply { repeat(4) { this[0x68 + it] = 0xff.toByte() } })
    println("NDS header bounded-allocation/overlap/partial-read checks passed")
}
