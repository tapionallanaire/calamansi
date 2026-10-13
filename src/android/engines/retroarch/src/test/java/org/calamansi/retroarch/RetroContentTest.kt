package org.calamansi.retroarch

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.calamansi.engine.NintendoPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RetroContentTest {
    @Test fun unknownSizeZipUsesTheCartridgeHeaderAndRejectsAmbiguousArchives() {
        val cartridge = ByteArray(16400)
        byteArrayOf(0x4e, 0x45, 0x53, 0x1a, 1).copyInto(cartridge)
        fun archive(vararg names: String): ByteArray {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                for (name in names) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(cartridge)
                    zip.closeEntry()
                }
            }
            return output.toByteArray()
        }
        fun read(bytes: ByteArray) =
            RetroContent.withRom(bytes.inputStream(), "bundle.zip", -1) { input, name, _ ->
                val rom = input.readBytes()
                RomHeaders.parse(rom, rom.size, rom.size.toLong(), name, null)
            }
        assertEquals(NintendoPlatform.NES, read(archive("nested/game.nes"))?.platform)
        assertThrows(IOException::class.java) { read(archive("first.nes", "second.nes")) }
    }

    @Test fun zipDirectoryBodiesCannotBypassTheExpandedSizeLimit() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("directory/"))
            val zeros = ByteArray(8192)
            repeat(8192) { zip.write(zeros) }
            zip.write(0)
            zip.closeEntry()
        }
        assertThrows(IOException::class.java) {
            RetroContent.withRom(output.toByteArray().inputStream(), "bundle.zip", -1) { _, _, _ ->
                Unit
            }
        }
    }
}
