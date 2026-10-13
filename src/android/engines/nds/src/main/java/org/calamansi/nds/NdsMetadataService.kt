package org.calamansi.nds

import android.net.Uri
import android.os.Bundle
import java.io.DataInputStream
import java.io.FilterInputStream
import java.io.InputStream
import me.magnum.melonds.utils.RomProcessor
import org.calamansi.engine.EngineMetadata
import org.calamansi.engine.EngineMetadataService
import org.calamansi.engine.NintendoPlatform

class NdsMetadataService : EngineMetadataService() {
    override fun readMetadata(uri: Uri, platformHint: String?): Bundle? {
        if (platformHint != null && platformHint != NintendoPlatform.NDS.name) return null
        return runCatching {
            contentResolver.openInputStream(uri)?.use {
                NdsHeader.validate(it, requireBanner = true)
            }
                ?: return null
            val metadata =
                contentResolver.openInputStream(uri)?.use {
                    RomProcessor.getRomMetadata(ExactReads(it))
                }
                    ?: return null
            if (metadata.isDSiWareTitle) return null
            val icon =
                contentResolver.openInputStream(uri)?.use {
                    RomProcessor.getRomIcon(ExactReads(it))
                }
                    ?: return null
            Bundle().apply {
                putBoolean(EngineMetadata.VALID, true)
                putString(EngineMetadata.PLATFORM, NintendoPlatform.NDS.name)
                metadata.romTitle.takeIf(String::isNotBlank)?.let {
                    putString(EngineMetadata.TITLE, it)
                }
                metadata.developerName.takeIf(String::isNotBlank)?.let {
                    putString(EngineMetadata.COMPANY, it)
                }
                putParcelable(EngineMetadata.ICON, icon)
            }
        }.getOrNull()
    }

    // Upstream's parser assumes whole reads. Enforce that on document-provider streams too.
    private class ExactReads(stream: InputStream) : FilterInputStream(stream) {
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            DataInputStream(`in`).readFully(bytes, offset, length)
            return length
        }
        override fun read(bytes: ByteArray): Int = read(bytes, 0, bytes.size)
    }
}
