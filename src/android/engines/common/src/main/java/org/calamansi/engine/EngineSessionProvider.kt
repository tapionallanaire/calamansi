package org.calamansi.engine

import android.app.ActivityManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import org.json.JSONObject

class EngineSessionProvider : ContentProvider() {
    private lateinit var ledger: SessionLedger
    private lateinit var store: AtomicFile
    private val persistOwner: () -> Unit = ::persist

    override fun onCreate(): Boolean {
        val directory = File(requireNotNull(context).filesDir, "engines")
        check(directory.mkdirs() || directory.isDirectory)
        store = AtomicFile(File(directory, "active-session.json"))
        val saved = try {
            store.openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }.let {
                EngineSessionRecord(
                    it.getString("token"),
                    it.getString("platform"),
                    it.getString("path"),
                    it.getString("title"),
                    it.getString("activity"),
                    it.getInt("pid"),
                    it.getString("process"),
                    it.getString("state"),
                    it.optString("kind", EngineSession.KIND_GAME)
                )
            }
        } catch (missing: FileNotFoundException) {
            if (store.baseFile.exists()) throw missing
            null
        }
        ledger = SessionLedger(saved)
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        check(Binder.getCallingUid() == Process.myUid()) { "Engine session caller is not this app" }
        val context = requireNotNull(context)
        return synchronized(ledger) {
            ledger.snapshot()?.let { session ->
                val processes = context.getSystemService(
                    ActivityManager::class.java
                ).runningAppProcesses
                if (processes != null && processes.none {
                        it.pid == session.pid && it.processName == session.processName
                    }
                ) {
                    ledger.end(session.token, persistOwner)
                    context.contentResolver.notifyChange(EngineSession.uri(context), null)
                }
            }
            when (method) {
                "snapshot" -> ledger.snapshot()?.toBundle()

                "begin" -> {
                    val data = requireNotNull(extras)
                    val platform = NintendoPlatform.valueOf(
                        requireNotNull(data.getString("platform"))
                    )
                    val kind = data.getString("kind") ?: EngineSession.KIND_GAME
                    require(
                        kind == EngineSession.KIND_GAME || kind == EngineSession.KIND_SETTINGS ||
                            kind == EngineSession.KIND_METADATA
                    )
                    val path = data.getString("path").orEmpty()
                    val engine = platform.engine
                    val process = requireNotNull(data.getString("process"))
                    val activity = requireNotNull(data.getString("activity"))
                    if (platform == NintendoPlatform.THREE_DS) {
                        require(kind == EngineSession.KIND_GAME)
                        require(process == context.packageName)
                        require(activity == "org.citra.citra_emu.activities.EmulationActivity")
                        require(
                            path.isNotBlank() && (
                                when (Uri.parse(path).scheme) {
                                    "content", "file", "fd", "articbase" -> true
                                    else -> path.startsWith("/") || path.startsWith("!")
                                }
                                )
                        )
                    } else {
                        requireNotNull(engine)
                        require(process == "${context.packageName}:engine_${engine.directory}")
                        if (kind == EngineSession.KIND_GAME || path.isNotEmpty()) {
                            val scheme = Uri.parse(path).scheme
                            require(scheme == "content" || scheme == "file")
                        }
                        val base = when (engine) {
                            EngineId.NDS -> "org.calamansi.nds.Nds"
                            EngineId.RETROARCH -> "org.calamansi.retroarch.RetroArch"
                            EngineId.DOLPHIN -> "org.calamansi.dolphin.Dolphin"
                            EngineId.CEMU -> "org.calamansi.cemu.Cemu"
                        }
                        val expected = when (kind) {
                            EngineSession.KIND_GAME -> "${base}Activity"
                            EngineSession.KIND_SETTINGS -> "${base}SettingsActivity"
                            else -> "${base}MetadataService"
                        }
                        require(
                            activity == expected || (
                                engine == EngineId.DOLPHIN && kind == EngineSession.KIND_SETTINGS &&
                                    activity ==
                                    "org.dolphinemu.dolphinemu.features.settings.ui.SettingsActivity"
                                )
                        )
                    }
                    val session = ledger.begin(
                        platform.name,
                        path,
                        requireNotNull(data.getString("title")),
                        activity,
                        Binder.getCallingPid(),
                        process,
                        kind
                    )
                    try {
                        persist()
                    } catch (failure: Exception) {
                        ledger.end(session.token)
                        throw failure
                    }
                    context.contentResolver.notifyChange(EngineSession.uri(context), null)
                    session.toBundle()
                }

                "update", "end" -> {
                    val token = requireNotNull(arg)
                    val changed = if (method == "end") {
                        ledger.end(token, persistOwner)
                    } else {
                        ledger.update(token, requireNotNull(extras?.getString("state")))
                    }
                    if (changed) {
                        if (method != "end") persist()
                        context.contentResolver.notifyChange(EngineSession.uri(context), null)
                    }
                    Bundle().apply { putBoolean("changed", changed) }
                }

                else -> throw IllegalArgumentException("Unknown engine session operation")
            }
        }
    }

    private fun persist() {
        val session = ledger.snapshot()
        if (session == null) {
            store.delete()
            check(!store.baseFile.exists()) { "Cannot clear engine ownership" }
            return
        }
        val values = JSONObject().apply {
            put("token", session.token)
            put("platform", session.platform)
            put("path", session.path)
            put("title", session.title)
            put("activity", session.activity)
            put("pid", session.pid)
            put("process", session.processName)
            put("state", session.state)
            put("kind", session.kind)
        }.toString().toByteArray(Charsets.UTF_8)
        val stream = store.startWrite()
        try {
            stream.write(values)
            store.finishWrite(stream)
        } catch (failure: Exception) {
            store.failWrite(stream)
            throw failure
        }
    }

    private fun EngineSessionRecord.toBundle() = Bundle().apply {
        putString("token", token)
        putString("platform", platform)
        putString("path", path)
        putString("title", title)
        putString("activity", activity)
        putInt("pid", pid)
        putString("process", processName)
        putString("state", state)
        putString("kind", kind)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor = throw UnsupportedOperationException("Use the engine session call API")

    override fun getType(uri: Uri): String =
        throw UnsupportedOperationException("Use the engine session call API")

    override fun insert(uri: Uri, values: ContentValues?): Uri =
        throw UnsupportedOperationException("Use the engine session call API")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Use the engine session call API")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("Use the engine session call API")
}
