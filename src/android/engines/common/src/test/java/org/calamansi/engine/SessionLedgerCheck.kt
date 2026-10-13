package org.calamansi.engine

fun main() {
    val ledger = SessionLedger()
    val first = ledger.begin("NDS", "content://rom/one", "One", "NdsActivity", 100, "engine_nds")
    check(ledger.snapshot() == first)
    check(
        runCatching {
            ledger.begin(
                "GBA",
                "content://rom/two",
                "Two",
                "RetroArchActivity",
                200,
                "engine_retroarch"
            )
        }.isFailure
    )
    check(!ledger.end("stale"))
    check(ledger.update(first.token, "paused"))
    check(ledger.snapshot()?.state == "paused")
    val recovered = SessionLedger(ledger.snapshot())
    check(recovered.snapshot()?.token == first.token)
    check(
        runCatching {
            recovered.begin(
                "GBA",
                "content://rom/two",
                "Two",
                "RetroArchActivity",
                200,
                "engine_retroarch"
            )
        }.isFailure
    )
    check(recovered.update(first.token, "stopping"))
    check(recovered.update(first.token, "error"))
    check(recovered.snapshot()?.state == "error")
    check(!recovered.end("stale"))
    check(recovered.end(first.token))
    check(ledger.end(first.token))
    val second = ledger.begin(
        "GBA",
        "content://rom/two",
        "Two",
        "RetroArchActivity",
        200,
        "engine_retroarch"
    )
    check(!ledger.update(first.token, "paused"))
    check(!ledger.end(first.token))
    check(ledger.snapshot() == second)
    check(ledger.end(second.token))
    check(ledger.snapshot() == null)
    val metadata = ledger.begin(
        "WII_U",
        "content://rom/wiiu",
        "",
        "CemuMetadataService",
        300,
        "engine_cemu",
        kind = "metadata"
    )
    check(metadata.kind == "metadata")
    check(SessionLedger(metadata).snapshot()?.kind == "metadata")
    check(
        runCatching {
            ledger.begin("THREE_DS", "content://rom/3ds", "3DS", "EmulationActivity", 400, "main")
        }.isFailure
    )
    check(ledger.end(metadata.token))
    val editor = ledger.begin(
        "GAMECUBE",
        "",
        "Dolphin settings",
        "DolphinSettingsActivity",
        500,
        "engine_dolphin",
        kind = "settings"
    )
    check(editor.kind == "settings")
    check(!ledger.end(metadata.token))
    check(
        runCatching {
            ledger.begin("NDS", "content://rom/nds", "DS", "NdsActivity", 100, "engine_nds")
        }.isFailure
    )
    check(ledger.end(editor.token))
    check(
        runCatching {
            ledger.begin("NDS", "", "", "NdsActivity", 100, "engine_nds", kind = "unknown")
        }.isFailure
    )
    check(ledger.snapshot() == null)
    val durable = ledger.begin(
        "THREE_DS",
        "content://rom/3ds",
        "3DS",
        "EmulationActivity",
        400,
        "main"
    )
    check(
        runCatching {
            ledger.end(durable.token) { throw java.io.IOException("Ownership deletion failed") }
        }.isFailure
    )
    check(ledger.snapshot() == durable)
    check(
        runCatching {
            ledger.begin("NDS", "content://rom/nds", "DS", "NdsActivity", 100, "engine_nds")
        }.isFailure
    )
    check(ledger.end(durable.token))
    println("Active-session exclusivity and stale callbacks checked")
}
