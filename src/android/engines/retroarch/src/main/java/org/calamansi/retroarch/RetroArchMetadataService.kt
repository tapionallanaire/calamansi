package org.calamansi.retroarch

import android.net.Uri
import android.os.Bundle
import org.calamansi.engine.EngineMetadata
import org.calamansi.engine.EngineMetadataService
import org.calamansi.engine.NintendoPlatform

class RetroArchMetadataService : EngineMetadataService() {
    override fun readMetadata(uri: Uri, platformHint: String?): Bundle? {
        val hint = platformHint?.let { value ->
            NintendoPlatform.entries.firstOrNull {
                it.name ==
                    value
            }
        }
        if (platformHint != null && hint !in RomHeaders.platforms) return null
        val metadata = RetroContent.metadata(this, uri, hint) ?: return null
        return Bundle().apply {
            putBoolean(EngineMetadata.VALID, true)
            putString(EngineMetadata.PLATFORM, metadata.platform.name)
            putString(EngineMetadata.TITLE, metadata.title)
            metadata.regions?.let { putString(EngineMetadata.REGIONS, it) }
        }
    }
}
