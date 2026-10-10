// Copyright 2026 Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.model

internal fun resolveHomeSelection(visible: List<Game>, selectedPath: String?): Game? =
    visible.firstOrNull { it.path == selectedPath } ?: visible.firstOrNull()
