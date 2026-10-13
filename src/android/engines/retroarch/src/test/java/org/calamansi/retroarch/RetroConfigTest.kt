package org.calamansi.retroarch

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class RetroConfigTest {
    @Test fun platformSettingsRemainLiveUnlessTheCartridgeOverridesThem() {
        val root = Files.createTempDirectory("retro-config").toFile()
        try {
            val global = File(root, "retroarch.cfg")
            global.writeText("audio_enable = \"true\"\nvideo_vsync = \"true\"\n")
            val cartridge = File(root, "overrides/game.cfg")
            cartridge.parentFile!!.mkdirs()
            cartridge.writeText("video_vsync = \"false\"\n#include \"${global.absolutePath}\"\n")
            assertEquals("true", RetroConfig.read(cartridge)["audio_enable"])
            assertEquals("false", RetroConfig.read(cartridge)["video_vsync"])
            global.writeText("audio_enable = \"false\"\nvideo_vsync = \"true\"\n")
            assertEquals("false", RetroConfig.read(cartridge)["audio_enable"])
            assertEquals("false", RetroConfig.read(cartridge)["video_vsync"])
        } finally {
            root.deleteRecursively()
        }
    }
}
