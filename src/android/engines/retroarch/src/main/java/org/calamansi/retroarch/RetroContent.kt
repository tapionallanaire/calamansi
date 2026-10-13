package org.calamansi.retroarch

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.calamansi.engine.NintendoPlatform

internal object RetroContent {
    // ponytail: bounded 64 MiB legacy copies; use direct upstream SAF VFS if larger cartridges are needed.
    private const val MAX_ROM = 64L * 1024 * 1024
    fun key(uri: Uri): String =
        MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun name(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use {
                if (it.moveToFirst() &&
                    !it.isNull(0)
                ) {
                    return it.getString(0).substringAfterLast('/')
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "cartridge"
    }

    private fun <T> withRom(
        context: Context,
        uri: Uri,
        action: (InputStream, String, Long) -> T
    ): T {
        require(uri.scheme == "content" || uri.scheme == "file") { "Unsupported cartridge URI" }
        val filename = name(context, uri)
        val size =
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (size > MAX_ROM) throw IOException("Cartridge exceeds 64 MiB")
        val opened =
            context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cartridge is not readable")
        return withRom(opened, filename, size, action)
    }

    internal fun <T> withRom(
        opened: InputStream,
        filename: String,
        size: Long,
        action: (InputStream, String, Long) -> T
    ): T {
        val boundedSource = object : FilterInputStream(opened) {
            private var consumed = 0L
            override fun read(): Int {
                val value = opened.read()
                if (value >= 0 &&
                    ++consumed > MAX_ROM
                ) {
                    throw IOException("Cartridge or archive exceeds 64 MiB")
                }
                return value
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                val count = opened.read(buffer, offset, length)
                if (count > 0) {
                    consumed += count
                    if (consumed > MAX_ROM) throw IOException("Cartridge or archive exceeds 64 MiB")
                }
                return count
            }
        }
        return boundedSource.buffered().use { input ->
            input.mark(4)
            val magic = ByteArray(4)
            var n = 0
            while (n < magic.size) {
                val count = input.read(magic, n, magic.size - n)
                if (count < 0) break
                n += count
            }
            input.reset()
            val zip =
                n == 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4b.toByte() &&
                    magic[2] == 3.toByte() &&
                    magic[3] == 4.toByte()
            if (!zip) return@use action(input, filename, size)
            ZipInputStream(input).use { archive ->
                var result: T? = null
                var found = false
                var expanded = 0L
                val drain = ByteArray(8192)
                while (true) {
                    val entry = archive.nextEntry ?: break
                    if (entry.size > MAX_ROM) throw IOException("Archive entry exceeds 64 MiB")
                    val bounded = object : InputStream() {
                        override fun read(): Int {
                            val value = archive.read()
                            if (value >= 0 &&
                                ++expanded > MAX_ROM
                            ) {
                                throw IOException("Archive exceeds 64 MiB expanded")
                            }
                            return value
                        }
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            val count = archive.read(buffer, offset, length)
                            if (count > 0) {
                                expanded += count
                                if (expanded >
                                    MAX_ROM
                                ) {
                                    throw IOException("Archive exceeds 64 MiB expanded")
                                }
                            }
                            return count
                        }
                    }
                    if (!entry.isDirectory && RomHeaders.extensionPlatform(entry.name) != null) {
                        if (found) {
                            throw IOException(
                                "Select an archive containing exactly one cartridge"
                            )
                        }
                        found = true
                        result = action(bounded, entry.name.substringAfterLast('/'), entry.size)
                    }
                    while (bounded.read(drain) >=
                        0
                    ) { /* Bound even documentation and unused entries. */ }
                }
                if (!found) throw IOException("Archive contains no supported cartridge")
                @Suppress("UNCHECKED_CAST")
                result as T
            }
        }
    }

    private fun prefix(input: InputStream, platform: NintendoPlatform?): Pair<ByteArray, Int> {
        val bytes = ByteArray(RomHeaders.prefixSize(platform))
        var count = 0
        while (count < bytes.size) {
            val n = input.read(bytes, count, bytes.size - count)
            if (n < 0) break
            if (n == 0) continue
            count += n
        }
        return bytes to count
    }

    fun metadata(context: Context, uri: Uri, hint: NintendoPlatform?): RomHeaders.Metadata? =
        withRom(context, uri) { input, filename, _ ->
            val (bytes, count) = prefix(input, hint ?: RomHeaders.extensionPlatform(filename))
            // Providers and ZIP descriptors can omit size; validate the actual cartridge length.
            var total = count.toLong()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_ROM) throw IOException("Cartridge exceeds 64 MiB")
            }
            RomHeaders.parse(bytes, count, total, filename, hint)
        }

    fun copy(
        context: Context,
        uri: Uri,
        platform: NintendoPlatform,
        directory: File
    ): Pair<File, String> = withRom(context, uri) { input, filename, size ->
        val (bytes, count) = prefix(input, platform)
        requireNotNull(RomHeaders.parse(bytes, count, size, filename, platform)) {
            "Invalid ${platform.label} cartridge"
        }
        val extension = when (platform) {
            NintendoPlatform.GB -> "gb"
            NintendoPlatform.GBC -> "gbc"
            NintendoPlatform.GBA -> "gba"
            NintendoPlatform.NES -> "nes"
            NintendoPlatform.SNES -> "sfc"
            else -> error("Unsupported classic-console platform")
        }
        // The native save basename must survive a provider/display-name rename.
        val file = File(directory.apply { mkdirs() }, "${key(uri)}.$extension")
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(bytes, 0, count)
            var total = count.toLong()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_ROM) throw IOException("Cartridge exceeds 64 MiB")
                output.write(buffer, 0, n)
            }
            val metadata =
                requireNotNull(RomHeaders.parse(bytes, count, total, filename, platform)) {
                    "Truncated cartridge"
                }
            atomic.finishWrite(output)
            file to metadata.title
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
