package org.calamansi.engine

import kotlinx.serialization.Serializable

enum class EngineId(val directory: String) {
    NDS("nds"),
    RETROARCH("retroarch"),
    DOLPHIN("dolphin"),
    CEMU("cemu")
}

@Serializable
enum class NintendoPlatform(val label: String, val engine: EngineId?) {
    THREE_DS("3DS", null),
    NDS("Nintendo DS", EngineId.NDS),
    GB("Game Boy", EngineId.RETROARCH),
    GBC("Game Boy Color", EngineId.RETROARCH),
    GBA("Game Boy Advance", EngineId.RETROARCH),
    NES("NES", EngineId.RETROARCH),
    SNES("SNES", EngineId.RETROARCH),
    GAMECUBE("GameCube", EngineId.DOLPHIN),
    WII("Wii", EngineId.DOLPHIN),
    WII_U("Wii U", EngineId.CEMU)
}
