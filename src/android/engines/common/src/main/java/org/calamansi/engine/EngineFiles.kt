package org.calamansi.engine

import android.content.Context
import java.io.File
import java.io.IOException

object EngineFiles {
    const val EXTRA_PLATFORM = "calamansi.platform"

    fun root(context: Context, engine: EngineId): File {
        val root = File(context.filesDir, "engines/${engine.directory}")
        if (!root.isDirectory && !root.mkdirs()) {
            throw IOException("Cannot create console data directory")
        }
        return root
    }

    fun preferences(context: Context, engine: EngineId) =
        context.getSharedPreferences("engine_${engine.directory}", Context.MODE_PRIVATE)
}
