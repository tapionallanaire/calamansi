package org.calamansi.retroarch

import org.calamansi.engine.NintendoPlatform

internal object RomHeaders {
    data class Metadata(
        val platform: NintendoPlatform,
        val title: String,
        val regions: String? = null
    )
    val platforms =
        setOf(
            NintendoPlatform.GB,
            NintendoPlatform.GBC,
            NintendoPlatform.GBA,
            NintendoPlatform.NES,
            NintendoPlatform.SNES
        )
    private val gbLogo =
        (
            "edce6666cc0d000b03730083000c000d0008111f8889000edcc" +
                "c6ee6ddddd999bbbb67636e0eecccdddc999fbbb9333e"
            )
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val snesHeaders = intArrayOf(0x7fc0, 0xffc0, 0x40ffc0, 0x81c0, 0x101c0, 0x4101c0)
    private val snesModes = setOf(0x20, 0x21, 0x22, 0x23, 0x25)

    fun extensionPlatform(name: String): NintendoPlatform? =
        when (name.substringAfterLast('.', "").lowercase()) {
            "gb" -> NintendoPlatform.GB
            "gbc" -> NintendoPlatform.GBC
            "gba" -> NintendoPlatform.GBA
            "nes" -> NintendoPlatform.NES
            "sfc", "smc" -> NintendoPlatform.SNES
            else -> null
        }

    fun prefixSize(platform: NintendoPlatform?): Int = when (platform) {
        NintendoPlatform.GB, NintendoPlatform.GBC -> 0x150
        NintendoPlatform.GBA -> 0xc0
        NintendoPlatform.NES -> 16
        else -> 0x410200
    }

    fun parse(
        bytes: ByteArray,
        count: Int,
        length: Long,
        filename: String,
        hint: NintendoPlatform?
    ): Metadata? {
        fun u(offset: Int) = bytes[offset].toInt() and 255
        fun text(offset: Int, size: Int): String {
            var end = offset
            while (end < offset + size && bytes[end] != 0.toByte()) end++
            return String(bytes, offset, end - offset, Charsets.US_ASCII).trim()
        }
        fun accepted(result: Metadata): Metadata? = if (hint == null ||
            hint == result.platform
        ) {
            result
        } else {
            null
        }
        val fallback = filename.substringAfterLast('/').substringBeforeLast('.', filename)
        if (count >= 0x150 && gbLogo.indices.all { bytes[0x104 + it] == gbLogo[it] }) {
            var checksum = 0
            for (i in 0x134..0x14c) checksum = (checksum - u(i) - 1) and 255
            if (checksum != u(0x14d)) return null
            val romSize = when (val code = u(0x148)) {
                in 0..8 -> 32768L shl code
                0x52 -> 72L * 16384
                0x53 -> 80L * 16384
                0x54 -> 96L * 16384
                else -> return null
            }
            if (length >= 0 && length < romSize) return null
            val color = u(0x143) == 0x80 || u(0x143) == 0xc0
            val manufacturer = color && u(0x14b) == 0x33 &&
                (0x13f..0x142).all { u(it) in 0x41..0x5a || u(it) in 0x30..0x39 }
            val title = text(
                0x134,
                if (!color) {
                    16
                } else if (manufacturer) {
                    11
                } else {
                    15
                }
            ).ifBlank { fallback }
            return accepted(
                Metadata(
                    if (color) NintendoPlatform.GBC else NintendoPlatform.GB,
                    title,
                    when (u(0x14a)) {
                        0 -> "Japan"
                        1 -> "International"
                        else -> null
                    }
                )
            )
        }
        if (count >= 0xc0 && u(3) == 0xea && u(0xb2) == 0x96) {
            var checksum = 0x19
            for (i in 0xa0..0xbc) checksum += u(i)
            if (((-checksum) and 255) == u(0xbd)) {
                val title = text(0xa0, 12).ifBlank { fallback }
                return accepted(Metadata(NintendoPlatform.GBA, title))
            }
        }
        if (count >= 16 && u(0) == 0x4e && u(1) == 0x45 && u(2) == 0x53 && u(3) == 0x1a) {
            val nes2 = u(7) and 0x0c == 0x08
            fun banks(low: Int, high: Int, bankSize: Long): Long = if (high == 15) {
                val exponent = low shr 2
                if (exponent >=
                    61
                ) {
                    Long.MAX_VALUE
                } else {
                    (1L shl exponent) * ((low and 3) * 2 + 1)
                }
            } else {
                (low + (high shl 8)) * bankSize
            }
            val prgSize = banks(u(4), if (nes2) u(9) and 15 else 0, 16384)
            val chrSize = banks(u(5), if (nes2) u(9) shr 4 else 0, 8192)
            if (prgSize == 0L || prgSize == Long.MAX_VALUE || chrSize == Long.MAX_VALUE) return null
            val headerSize = 16L + if (u(6) and 4 != 0) 512 else 0
            if (length >= 0 &&
                (prgSize > length - headerSize || chrSize > length - headerSize - prgSize)
            ) {
                return null
            }
            return accepted(Metadata(NintendoPlatform.NES, fallback))
        }
        // SAF providers and ZIP data descriptors may not report length: inspect both layouts.
        for (h in snesHeaders) {
            if (count < h + 64) continue
            val checksum = u(h + 30) or (u(h + 31) shl 8)
            val inverse = u(h + 28) or (u(h + 29) shl 8)
            val mode = u(h + 21) and 0xef
            if ((checksum xor inverse) != 0xffff || mode !in snesModes || u(h + 61) < 0x80) continue
            if ((h until h + 21).any { u(it) !in 0x20..0x7e }) continue
            return accepted(
                Metadata(
                    NintendoPlatform.SNES,
                    text(h, 21).ifBlank { fallback },
                    when (u(h + 25)) {
                        0 -> "Japan"
                        1 -> "North America"
                        2 -> "Europe"
                        else -> null
                    }
                )
            )
        }
        return null
    }
}
