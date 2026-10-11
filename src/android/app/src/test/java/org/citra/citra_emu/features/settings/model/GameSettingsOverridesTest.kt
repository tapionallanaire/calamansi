// Copyright 2026 Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.features.settings.model

private data class Value(
    override val section: String,
    override val key: String,
    var value: String
) : AbstractSetting {
    override val valueAsString get() = value
    override val defaultValue = value
    override val isRuntimeEditable = true
}

// Standalone regression: compile with AbstractSetting.kt and SettingSection.kt, then run the jar.
fun main() {
    val global: Map<Pair<String?, String?>, String> = mapOf(
        ("Renderer" to "resolution_factor") to "4",
        ("Renderer" to "pp_shader_name") to "None (builtin)",
        ("Audio" to "volume") to "1.0"
    )
    val resolution = Value("Renderer", "resolution_factor", "4")
    val shader = Value("Renderer", "pp_shader_name", "None (builtin)")
    val volume = Value("Audio", "volume", "1.0")
    val titleA = listOf(resolution, shader, volume)
    check(gameOverrides(global, titleA).isEmpty())

    shader.value = "FXAA (builtin)"
    check(gameOverrides(global, titleA) == listOf(shader))
    val titleB = listOf(Value("Renderer", "pp_shader_name", "None (builtin)"))
    check(gameOverrides(global, titleB).isEmpty())
    check(global["Renderer" to "pp_shader_name"] == "None (builtin)")

    shader.value = "None (builtin)"
    check(gameOverrides(global, titleA).isEmpty())
    val nextGlobal = global + (("Renderer" to "resolution_factor") to "2")
    resolution.value = "2"
    check(gameOverrides(nextGlobal, titleA).isEmpty())

    // Values absent from the global INI still differ from the supplied model-default snapshot.
    check(gameOverrides(emptyMap(), listOf(shader)) == listOf(shader))
    check(
        gameOverrides(global, listOf(Value("Audio", "pp_shader_name", "None (builtin)"))).size == 1
    )
    println("Per-game override inheritance checks passed")
}
