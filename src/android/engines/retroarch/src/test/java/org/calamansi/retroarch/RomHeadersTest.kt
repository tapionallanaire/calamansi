package org.calamansi.retroarch

import org.calamansi.engine.NintendoPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RomHeadersTest {
    @Test fun headersRejectCorruptionAndRouteAllFivePlatforms() {
        val gb = ByteArray(0x8000)
        val logo =
            (
                "edce6666cc0d000b03730083000c000d0008111f8889000edcc" +
                    "c6ee6ddddd999bbbb67636e0eecccdddc999fbbb9333e"
                )
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        logo.copyInto(gb, 0x104)
        "CARTRIDGE".toByteArray().copyInto(gb, 0x134)
        fun gbChecksum() {
            var sum = 0
            for (i in 0x134..0x14c) sum = (sum - (gb[i].toInt() and 255) - 1) and 255
            gb[0x14d] = sum.toByte()
        }
        gbChecksum()
        assertEquals(
            NintendoPlatform.GB,
            RomHeaders.parse(gb, gb.size, gb.size.toLong(), "game.gb", null)?.platform
        )
        assertEquals(
            "CARTRIDGE",
            RomHeaders.parse(gb, gb.size, gb.size.toLong(), "game.gb", null)?.title
        )
        assertNull(RomHeaders.parse(gb, 0x150, 0x150, "truncated.gb", null))
        gb[0x148] = 1
        gbChecksum()
        assertNull(RomHeaders.parse(gb, 0x150, 0x8000, "truncated.gb", null))
        gb[0x148] = 0
        gbChecksum()
        gb[0x143] = 0x80.toByte()
        gbChecksum()
        assertEquals(
            NintendoPlatform.GBC,
            RomHeaders.parse(gb, gb.size, gb.size.toLong(), "game.gbc", null)?.platform
        )
        "FIFTEEN LETTERS".toByteArray().copyInto(gb, 0x134)
        gbChecksum()
        assertEquals(
            "FIFTEEN LETTERS",
            RomHeaders.parse(gb, gb.size, gb.size.toLong(), "game.gbc", null)?.title
        )
        assertNull(
            RomHeaders.parse(gb, gb.size, gb.size.toLong(), "game.gbc", NintendoPlatform.GBA)
        )
        gb[0x14d] = (gb[0x14d].toInt() xor 1).toByte()
        assertNull(RomHeaders.parse(gb, gb.size, gb.size.toLong(), "game.gb", null))

        val gba = ByteArray(0x200)
        gba[3] = 0xea.toByte()
        gba[0xb2] = 0x96.toByte()
        "ADVANCE".toByteArray().copyInto(gba, 0xa0)
        var checksum = 0x19
        for (i in 0xa0..0xbc) checksum += gba[i].toInt() and 255
        gba[0xbd] = (-checksum).toByte()
        assertEquals(
            NintendoPlatform.GBA,
            RomHeaders.parse(gba, gba.size, gba.size.toLong(), "game.gba", null)?.platform
        )
        gba[0xbd] = (gba[0xbd].toInt() xor 1).toByte()
        assertNull(RomHeaders.parse(gba, gba.size, gba.size.toLong(), "game.gba", null))

        val nes = byteArrayOf(0x4e, 0x45, 0x53, 0x1a, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        assertEquals(
            "Licensed cartridge",
            RomHeaders.parse(nes, 16, 16400, "Licensed cartridge.nes", null)?.title
        )
        assertNull(RomHeaders.parse(nes, 16, 100, "game.nes", null))
        nes[7] = 8
        assertNull(RomHeaders.parse(nes, 16, 100, "truncated.nes", null))
        assertEquals(
            NintendoPlatform.NES,
            RomHeaders.parse(nes, 16, 16400, "game.nes", null)?.platform
        )
        nes[4] = 56 // NES 2.0: exponent 14, multiplier 1 = 16 KiB PRG.
        nes[9] = 15
        assertNull(RomHeaders.parse(nes, 16, 16399, "truncated.nes", null))
        assertEquals(
            NintendoPlatform.NES,
            RomHeaders.parse(nes, 16, 16400, "game.nes", null)?.platform
        )
        nes[4] = 0xfc.toByte() // Exponent 63 cannot fit in a cartridge or a signed Long.
        assertNull(RomHeaders.parse(nes, 16, 16400, "oversized.nes", null))

        val snes = ByteArray(0x8000 + 512)
        val h = 0x7fc0 + 512
        "SUPER CARTRIDGE".padEnd(21).toByteArray().copyInto(snes, h)
        snes[h + 21] = 0x20
        snes[h + 28] = 0xff.toByte()
        snes[h + 29] = 0xff.toByte()
        snes[h + 61] = 0x80.toByte()
        assertEquals(
            NintendoPlatform.SNES,
            RomHeaders.parse(snes, snes.size, snes.size.toLong(), "game.smc", null)?.platform
        )
        assertEquals(
            NintendoPlatform.SNES,
            RomHeaders.parse(snes, snes.size, -1, "game.smc", NintendoPlatform.SNES)?.platform
        )
        snes[h + 28] = 0
        assertNull(RomHeaders.parse(snes, snes.size, snes.size.toLong(), "game.smc", null))
        assertNull(RomHeaders.parse(ByteArray(512), 512, 512, "not-a-cartridge.gba", null))
    }
}
