// Copyright 2026 Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.model

// Standalone regression: compile with HomeSelection.kt and the actual compiled Game model.
fun main() {
    val a = Game(path = "a.cci", title = "Same", titleId = 1L, filename = "Same.cci")
    val b = Game(path = "b.cci", title = "Same", titleId = 1L, filename = "Same.cci")
    val refreshed = Game(path = "b.cci", title = "Updated", titleId = 1L, filename = "Same.cci")
    check(resolveHomeSelection(listOf(a, b), "b.cci") === b)
    check(resolveHomeSelection(listOf(a, refreshed), "b.cci") === refreshed)
    check(resolveHomeSelection(listOf(a), "b.cci") === a)
    check(resolveHomeSelection(emptyList(), "b.cci") == null)
    check(resolveHomeSelection(listOf(a, b), null) === a)
    println("HOME path identity, refresh and removal checks passed")
}
