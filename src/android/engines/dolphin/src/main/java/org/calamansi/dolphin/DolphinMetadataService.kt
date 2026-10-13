// SPDX-License-Identifier: GPL-2.0-or-later
package org.calamansi.dolphin

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import org.calamansi.engine.EngineMetadata
import org.calamansi.engine.EngineMetadataService
import org.calamansi.engine.NintendoPlatform
import org.dolphinemu.dolphinemu.model.GameFile

class DolphinMetadataService : EngineMetadataService() {
    @Volatile private var destroyed = false

    override fun onDestroy() {
        destroyed = true
        super.onDestroy()
    }

    override fun readMetadata(uri: Uri, platformHint: String?): Bundle? {
        if (destroyed) return null
        val hint = platformHint?.let(NintendoPlatform::valueOf) ?: NintendoPlatform.GAMECUBE
        require(hint == NintendoPlatform.GAMECUBE || hint == NintendoPlatform.WII)
        val ownsSession = DolphinRuntime.beginMetadata(this, hint)
        try {
            if (destroyed) return null
            DolphinRuntime.initialize(this)
            val game = GameFile.parse(DolphinActivity.nativePath(uri)) ?: return null
            val platform = when (game.getPlatform()) {
                0, 1 -> NintendoPlatform.GAMECUBE
                2, 3 -> NintendoPlatform.WII
                else -> return null
            }
            if (platformHint != null && platformHint != platform.name) return null
            return Bundle().apply {
                putBoolean(EngineMetadata.VALID, true)
                putString(EngineMetadata.PLATFORM, platform.name)
                putString(EngineMetadata.TITLE, game.getTitle())
                putString(EngineMetadata.COMPANY, game.getCompany())
                putString(
                    EngineMetadata.REGIONS,
                    when (game.getRegion()) {
                        0 -> "Japan"
                        1 -> "USA"
                        2 -> "Europe"
                        4 -> "Korea"
                        else -> ""
                    }
                )
                val width = game.getBannerWidth()
                val height = game.getBannerHeight()
                if (width > 0 && height > 0 && width.toLong() * height <= 1_048_576) {
                    val banner = game.getBanner()
                    if (banner.size == width * height) {
                        putParcelable(
                            EngineMetadata.ICON,
                            Bitmap.createBitmap(banner, width, height, Bitmap.Config.ARGB_8888)
                        )
                    }
                }
            }
        } finally {
            DolphinRuntime.endMetadata(ownsSession)
        }
    }
}
