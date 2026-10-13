package org.calamansi.engine

import java.util.UUID

data class EngineSessionRecord(
    val token: String,
    val platform: String,
    val path: String,
    val title: String,
    val activity: String,
    val pid: Int,
    val processName: String,
    val state: String = "running",
    val kind: String = "game"
)

class SessionLedger(initial: EngineSessionRecord? = null) {
    private var active: EngineSessionRecord? = initial

    @Synchronized
    fun begin(
        platform: String,
        path: String,
        title: String,
        activity: String,
        pid: Int,
        processName: String,
        kind: String = "game"
    ): EngineSessionRecord {
        require(kind == "game" || kind == "settings" || kind == "metadata")
        check(active == null) { "Another native owner is still active" }
        return EngineSessionRecord(
            UUID.randomUUID().toString(),
            platform,
            path,
            title,
            activity,
            pid,
            processName,
            kind = kind
        ).also { active = it }
    }

    @Synchronized
    fun snapshot(): EngineSessionRecord? = active

    @Synchronized
    fun update(token: String, state: String): Boolean {
        require(state == "running" || state == "paused" || state == "stopping" || state == "error")
        val current = active ?: return false
        if (current.token != token) return false
        active = current.copy(state = state)
        return true
    }

    @Synchronized
    fun end(token: String, persist: (() -> Unit)? = null): Boolean {
        val previous = active ?: return false
        if (previous.token != token) return false
        active = null
        try {
            persist?.invoke()
        } catch (failure: Exception) {
            active = previous
            throw failure
        }
        return true
    }
}
