package org.calamansi.nds

import java.io.DataInputStream
import java.io.InputStream

object NdsHeader {
    private const val MAX_ROM_BYTES = 512L * 1024 * 1024
    private const val MAX_BOOT_BYTES = 32L * 1024 * 1024

    fun validate(stream: InputStream, requireBanner: Boolean = false): ByteArray {
        val header = ByteArray(0x160)
        DataInputStream(stream).readFully(header)
        require(header[0x12].toInt() == 0 || header[0x12].toInt() == 2) {
            "Not a DS cartridge (DSi-only titles require NAND/BIOS dumps)"
        }
        val sections = listOf(0x20 to 0x2c, 0x30 to 0x3c).map { (offset, size) ->
            val start = u32(header, offset)
            val length = u32(header, size)
            require(
                start >= header.size && length in 1..MAX_BOOT_BYTES &&
                    start + length <= MAX_ROM_BYTES
            ) { "Invalid DS boot code range" }
            start until start + length
        }
        val banner = u32(header, 0x68)
        require(!requireBanner || banner != 0L) { "DS cartridge has no banner metadata" }
        require(banner == 0L || (banner >= header.size && banner + 0xa00 <= MAX_ROM_BYTES)) {
            "Invalid DS banner offset"
        }
        val ranges = (
            sections +
                if (banner ==
                    0L
                ) {
                    emptyList()
                } else {
                    listOf(banner until banner + 0xa00)
                }
            ).sortedBy { it.first }
        require(
            ranges.zipWithNext().all { (a, b) ->
                a.last < b.first
            }
        ) { "Overlapping DS sections" }
        return header
    }

    private fun u32(bytes: ByteArray, offset: Int): Long = (0..3).fold(0L) { result, index ->
        result or ((bytes[offset + index].toLong() and 255) shl (index * 8))
    }
}
