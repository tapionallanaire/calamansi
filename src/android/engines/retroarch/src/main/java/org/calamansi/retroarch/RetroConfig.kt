package org.calamansi.retroarch

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import org.calamansi.engine.EngineFiles
import org.calamansi.engine.EngineId
import org.calamansi.engine.NintendoPlatform

object RetroConfig {
    fun core(platform: NintendoPlatform): String = when (platform) {
        NintendoPlatform.GB, NintendoPlatform.GBC -> "gambatte"
        NintendoPlatform.GBA -> "mgba"
        NintendoPlatform.NES -> "fceumm"
        NintendoPlatform.SNES -> "snes9x"
        else -> error("Unsupported classic-console platform: $platform")
    }
    fun directory(context: Context, platform: NintendoPlatform): File = File(
        EngineFiles.root(context, EngineId.RETROARCH),
        "${platform.name.lowercase()}/${core(platform)}"
    ).apply {
        mkdirs()
    }

    fun file(context: Context, platform: NintendoPlatform, uri: Uri?): File {
        val root = directory(context, platform)
        val global = File(root, "retroarch.cfg")
        if (!global.isFile) {
            val defaults = linkedMapOf(
                "video_driver" to "gl", "audio_driver" to "opensl", "input_driver" to "android",
                "input_joypad_driver" to "android", "menu_driver" to "rgui",
                "audio_enable" to "true", "video_vsync" to "true", "rewind_enable" to "false",
                "input_autodetect_enable" to "true", "autosave_interval" to "10",
                "config_save_on_exit" to "false", "savestate_auto_save" to "true",
                "savestate_auto_load" to "false", "sort_savefiles_enable" to "false",
                "sort_savestates_enable" to "false", "menu_show_core_updater" to "false",
                "menu_show_online_updater" to "false", "input_menu_toggle_gamepad_combo" to "4",
                "input_player1_joypad_index" to "0",
                "menu_show_load_core" to "false", "menu_show_load_content" to "false",
                "content_show_favorites" to "false", "history_list_enable" to "false",
                "quit_on_close_content" to "1"
            )
            for ((key, subdirectory) in mapOf(
                "savefile_directory" to "saves",
                "savestate_directory" to "states",
                "system_directory" to "system", "screenshot_directory" to "screenshots",
                "input_remapping_directory" to "remaps", "rgui_config_directory" to "overrides",
                "core_options_path" to "core-options.cfg", "content_history_path" to "history.lpl",
                "content_favorites_path" to "favorites.lpl", "log_dir" to "logs"
            )) {
                val location = File(root, subdirectory)
                if (!subdirectory.contains('.')) location.mkdirs()
                defaults[key] = location.absolutePath
            }
            defaults["libretro_directory"] = context.applicationInfo.nativeLibraryDir
            // The native Android driver consumes these upstream generic Android key/hat bindings.
            for ((button, code) in mapOf(
                "a" to "97", "b" to "96", "x" to "100", "y" to "99",
                "start" to "108", "select" to "109", "up" to "h0up", "down" to "h0down",
                "left" to "h0left", "right" to "h0right", "l" to "102", "r" to "103",
                "l2" to "104", "r2" to "105", "l3" to "106", "r3" to "107"
            )) {
                defaults["input_player1_${button}_btn"] = code
            }
            write(global, defaults)
        }
        if (uri == null) return global
        val perRom = File(root, "overrides/${RetroContent.key(uri)}.cfg")
        if (!perRom.isFile) write(perRom, emptyMap())
        return perRom
    }

    fun read(file: File, inherit: Boolean = true): LinkedHashMap<String, String> {
        val values = if (inherit && file.parentFile?.name == "overrides") {
            read(File(file.parentFile!!.parentFile, "retroarch.cfg"))
        } else {
            linkedMapOf()
        }
        if (file.isFile) {
            file.forEachLine { line ->
                val split = line.indexOf('=')
                if (split > 0 && !line.trimStart().startsWith('#')) {
                    val key = line.substring(0, split).trim()
                    var value = line.substring(split + 1).trim()
                    if (value.startsWith('"') && value.endsWith('"') &&
                        value.length >= 2
                    ) {
                        value = value.substring(1, value.length - 1)
                    }
                    values[key] = value
                }
            }
        }
        return values
    }

    fun set(file: File, key: String, value: String) {
        val values = read(file, inherit = false)
        values[key] = value
        write(file, values)
    }

    private fun write(file: File, values: Map<String, String>) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            for ((key, value) in values) {
                require(!value.contains('"') && !value.contains('\n') && !value.contains('\r')) {
                    "Invalid classic-console config value"
                }
                stream.write("$key = \"$value\"\n".toByteArray())
            }
            // RetroArch gives earlier entries precedence over included defaults.
            if (file.parentFile?.name == "overrides") {
                val global = File(file.parentFile!!.parentFile, "retroarch.cfg")
                stream.write("#include \"${global.absolutePath}\"\n".toByteArray())
            }
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
