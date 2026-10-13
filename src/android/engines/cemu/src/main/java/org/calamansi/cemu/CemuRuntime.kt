// SPDX-License-Identifier: MPL-2.0
package org.calamansi.cemu

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Surface
import info.cemu.cemu.nativeinterface.NativeActiveSettings
import info.cemu.cemu.nativeinterface.NativeEmulation
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeSwkbd
import info.cemu.cemu.nativeinterface.toNativePath
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.calamansi.engine.EngineFiles
import org.calamansi.engine.EngineId

object CemuRuntime {
    lateinit var context: Context
        private set
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CemuBackend")
    }
    private val surfaceLock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val nativeError = AtomicReference<Exception?>()

    @Volatile var errorListener: ((Exception) -> Unit)? = null
    private var libraryLoaded = false
    private var pathsConfigured = false
    private var initializationAttempted = false
    private var initialized = false

    @Volatile private var rendererInitialized = false
    private var titleStarted = false

    @Volatile private var sessionToken: String? = null

    @Volatile private var sessionClosing = false

    fun bindSession(token: String) {
        sessionToken = token
        sessionClosing = false
    }

    fun requireSession(token: String) {
        check(sessionToken == token && !sessionClosing) {
            "Wii U session is stopping or no longer active"
        }
    }

    fun closeSession(token: String) {
        check(sessionToken == token) { "Wii U session changed" }
        sessionClosing = true
    }

    fun activeTitleId(): Long? = if (titleStarted) NativeEmulation.getForegroundTitleId() else null

    @JvmStatic
    fun requestRelaunch(titleId: Long) {
        val owner = sessionToken ?: return
        execute({
            // This is cancellation of a guest request, not a fake title launch.
            if (sessionToken == owner && !sessionClosing) {
                synchronized(surfaceLock) {
                    NativeEmulation.relaunchTitle(titleId)
                    throwNativeError()
                }
            }
        }) { result ->
            if (sessionToken == owner && !sessionClosing) {
                result.onFailure { reportError(IOException("Wii U title relaunch failed", it)) }
            }
        }
    }

    fun unsupportedReason(): String? = when {
        Build.VERSION.SDK_INT < 31 ->
            "Wii U requires Android 12 (API 31) or newer. " +
                "Other Calamansi engines remain available."

        Build.SUPPORTED_64_BIT_ABIS.firstOrNull() != "arm64-v8a" ||
            !android.os.Process.is64Bit() ->
            "This Wii U backend requires a native ARM64 process."

        else -> null
    }

    fun attach(context: Context) {
        this.context = context.applicationContext
    }

    fun root(): File = EngineFiles.root(context, EngineId.CEMU)

    fun requiredUserFiles(): List<String> {
        val root = root()
        if (File(root, "shareddata.bin").length() == 32L * 1024 * 1024) return emptyList()
        return listOf("CafeCn.ttf", "CafeKr.ttf", "CafeStd.ttf", "CafeTw.ttf").filterNot {
            File(root, "resources/sharedFonts/$it").length() > 0 ||
                File(root, "mlc01/sys/title/0005001b/10042400/content/$it").length() > 0
        }.map { File(root, "mlc01/sys/title/0005001b/10042400/content/$it").absolutePath }
    }

    fun nativePath(uri: Uri): String {
        if (uri.scheme == "file") return requireNotNull(uri.path)
        require(uri.scheme == "content") { "Wii U emulation accepts only file or content URIs" }
        val document = if (DocumentsContract.isTreeUri(uri) &&
            !DocumentsContract.isDocumentUri(context, uri)
        ) {
            DocumentsContract.buildDocumentUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri)
            )
        } else {
            uri
        }
        return document.toNativePath()
    }

    fun initialize() {
        check(unsupportedReason() == null) { unsupportedReason().orEmpty() }
        check(sessionToken != null && !sessionClosing) {
            "Wii U native initialization requires an active session"
        }
        if (initialized) return
        val root = root()
        if (!libraryLoaded) {
            System.loadLibrary("CemuAndroid")
            libraryLoaded = true
        }
        copyProfiles("cemu/gameProfiles", File(root, "gameProfiles"))
        NativeEmulation.setDPI(context.resources.displayMetrics.density)
        if (!pathsConfigured) {
            NativeActiveSettings.initializeActiveSettings(root.absolutePath, root.absolutePath)
            NativeActiveSettings.setNativeLibDir(context.applicationInfo.nativeLibraryDir)
            NativeActiveSettings.setInternalDir(root.absolutePath)
            pathsConfigured = true
        }
        initializationAttempted = true
        NativeEmulation.initializeEmulation()
        NativeSwkbd.initializeSwkbd()
        NativeInput.setControllerType(0, NativeInput.EMULATED_CONTROLLER_TYPE_VPAD)
        initialized = true
        throwNativeError()
    }

    private fun copyProfiles(assetPath: String, target: File) {
        val children =
            context.assets.list(assetPath) ?: throw IOException("Cannot list Wii U profile assets")
        if (children.isEmpty()) {
            if (!target.exists()) {
                target.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        } else {
            if (!target.isDirectory &&
                !target.mkdirs()
            ) {
                throw IOException("Cannot create ${target.absolutePath}")
            }
            for (child in children) copyProfiles("$assetPath/$child", File(target, child))
        }
    }

    fun start(
        uri: Uri,
        surface: Surface,
        width: Int,
        height: Int,
        padSurface: Surface?,
        padWidth: Int,
        padHeight: Int
    ) {
        check(!titleStarted) { "Wii U emulation is already running a title" }
        initialize()
        nativeError.set(null)
        NativeInput.releaseInputs()
        synchronized(surfaceLock) {
            check(surface.isValid) { "Wii U TV surface was destroyed before boot" }
            NativeEmulation.initializeRenderer(surface)
            rendererInitialized = true
            NativeEmulation.setSurfaceSize(width, height, true)
            NativeEmulation.setSurface(surface, true)
            if (padSurface != null && padSurface.isValid && padWidth > 0 && padHeight > 0) {
                NativeEmulation.setSurfaceSize(padWidth, padHeight, false)
                NativeEmulation.setSurface(padSurface, false)
            }
        }
        val result = NativeEmulation.startGame(nativePath(uri))
        if (result != NativeEmulation.START_GAME_SUCCESSFUL) {
            val message = when (result) {
                NativeEmulation.START_GAME_ERROR_GAME_BASE_FILES_NOT_FOUND ->
                    "The base Wii U title is missing."

                NativeEmulation.START_GAME_ERROR_NO_DISC_KEY ->
                    "Missing disc key. Supply your own legally dumped disc key in " +
                        File(root(), "keys.txt") + "."

                NativeEmulation.START_GAME_ERROR_NO_TITLE_TIK ->
                    "Missing title ticket. Supply the title's original dumped " +
                        "ticket/title.tik with its game files."

                else -> "Could not prepare this Wii U title (native result $result)."
            }
            throw IOException(message)
        }
        titleStarted = true
        throwNativeError()
    }

    fun surface(surface: Surface?, width: Int, height: Int, mainCanvas: Boolean) =
        synchronized(surfaceLock) {
            // No native surface ownership exists before renderer creation.
            if (!rendererInitialized) return@synchronized
            if (surface == null) {
                NativeEmulation.clearSurface(mainCanvas)
            } else {
                NativeEmulation.setSurfaceSize(width, height, mainCanvas)
                NativeEmulation.setSurface(surface, mainCanvas)
            }
            throwNativeError()
        }

    fun stop() = synchronized(surfaceLock) {
        if (initializationAttempted) NativeEmulation.safeStop()
        // Native safeStop has joined CPU/GPU/IOSU and closed/flushed save ownership.
        initialized = false
        initializationAttempted = false
        rendererInitialized = false
        titleStarted = false
        nativeError.set(null)
    }

    fun <T> execute(action: () -> T, completed: (Result<T>) -> Unit) {
        worker.execute {
            val result = try {
                Result.success(action())
            } catch (
                error: Exception
            ) {
                Result.failure(error)
            } catch (error: LinkageError) {
                Result.failure(error)
            }
            main.post { completed(result) }
        }
    }

    fun <T> call(action: () -> T): T = worker.submit(Callable { action() }).get()

    fun throwNativeError() {
        nativeError.getAndSet(null)?.let { throw it }
    }

    fun reportError(error: Exception) {
        nativeError.compareAndSet(null, error)
        val listener = errorListener
        main.post { listener?.invoke(error) }
    }

    @JvmStatic
    fun reportNativeError(message: String) = reportError(IOException(message))
}
