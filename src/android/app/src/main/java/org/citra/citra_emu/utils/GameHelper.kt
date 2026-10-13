// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.utils

import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.preference.PreferenceManager
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.calamansi.engine.EngineSession
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.model.CheapDocument
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.model.GameInfo

object GameHelper {
    const val KEY_GAME_PATH = "game_path"
    const val KEY_GAMES = "Games"
    const val KEY_EXTRA_GAME_PATHS = "nintendo_library_roots"

    // Since the game val in GetGame is tied to the JNI we need to cache the game list in order to use it elsewhere
    var cachedGameList = mutableListOf<Game>()
    private lateinit var preferences: SharedPreferences

    fun addGameFolder(uri: Uri) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
        val path = uri.toString()
        val roots = preferences.getStringSet(
            KEY_EXTRA_GAME_PATHS,
            emptySet()
        ).orEmpty().toMutableSet()
        preferences.getString(KEY_GAME_PATH, null)?.takeIf {
            it.isNotEmpty() && it != path
        }?.let(roots::add)
        roots.remove(path)
        preferences.edit().putString(KEY_GAME_PATH, path)
            .putStringSet(KEY_EXTRA_GAME_PATHS, roots).apply()
    }

    private fun documentKey(uri: Uri): String =
        if (DocumentsContract.isDocumentUri(CitraApplication.appContext, uri)) {
            "${uri.authority}:${DocumentsContract.getDocumentId(uri)}"
        } else {
            uri.toString()
        }

    fun distinctGames(games: Iterable<Game>): List<Game> =
        games.distinctBy { documentKey(Uri.parse(it.path)) }

    fun getGames(): List<Game>? {
        val context = CitraApplication.appContext
        preferences = PreferenceManager.getDefaultSharedPreferences(context)
        // Native metadata must not initialize another backend during gameplay/settings.
        if (EngineSession.snapshot(context) != null) return null
        val games = mutableListOf<Game>()
        val seen = mutableSetOf<String>()
        val gamesDir = preferences.getString(KEY_GAME_PATH, "")
        val gamesUri = Uri.parse(gamesDir)

        try {
            NintendoCatalog(context).use { catalog ->
                addGamesRecursive(games, FileUtil.listFiles(gamesUri), 3, catalog, seen)
                preferences.getStringSet(
                    KEY_EXTRA_GAME_PATHS,
                    emptySet()
                ).orEmpty().forEach { path ->
                    if (path != gamesDir) {
                        addGamesRecursive(
                            games,
                            FileUtil.listFiles(Uri.parse(path)),
                            3,
                            catalog,
                            seen
                        )
                    }
                }
            }
        } catch (error: IOException) {
            if (EngineSession.snapshot(context) == null) throw error
            Log.warning("Library scan deferred while a native session is active: ${error.message}")
            return null
        }
        if (EngineSession.snapshot(context) != null) return null
        NativeLibrary.getInstalledGamePaths().forEach {
            games.add(
                getGame(Uri.parse(it.path), isInstalled = true, addedToLibrary = true, it.mediaType)
            )
        }
        val uniqueGames = distinctGames(games)
        uniqueGames.forEach { game ->
            if (preferences.getLong(game.keyAddedToLibraryTime, 0L) == 0L) {
                preferences.edit().putLong(
                    game.keyAddedToLibraryTime,
                    System.currentTimeMillis()
                ).apply()
            }
        }

        // Cache list of games found on disk
        val serializedGames = mutableSetOf<String>()
        uniqueGames.forEach {
            serializedGames.add(Json.encodeToString(it))
        }
        preferences.edit()
            .remove(KEY_GAMES)
            .putStringSet(KEY_GAMES, serializedGames)
            .apply()

        cachedGameList = uniqueGames.toMutableList()
        return uniqueGames
    }

    private fun addGamesRecursive(
        games: MutableList<Game>,
        files: Array<CheapDocument>,
        depth: Int,
        catalog: NintendoCatalog,
        seen: MutableSet<String>
    ) {
        if (depth <= 0) {
            return
        }

        files.forEach {
            if (it.isDirectory) {
                addGamesRecursive(games, FileUtil.listFiles(it.uri), depth - 1, catalog, seen)
            } else {
                if (!seen.add(documentKey(it.uri))) return@forEach
                if (Game.extensions.contains(FileUtil.getExtension(it.uri))) {
                    games.add(
                        getGame(
                            it.uri,
                            isInstalled = false,
                            addedToLibrary = true,
                            Game.MediaType.GAME_CARD
                        )
                    )
                } else if (catalog.accepts(it.uri)) {
                    try {
                        catalog.read(it.uri)?.let(games::add)
                    } catch (error: IOException) {
                        if (EngineSession.snapshot(CitraApplication.appContext) != null) throw error
                        Log.warning("Cannot read ROM metadata for ${it.uri}: ${error.message}")
                    }
                }
            }
        }
    }

    fun getGame(
        uri: Uri,
        isInstalled: Boolean,
        addedToLibrary: Boolean,
        mediaType: Game.MediaType
    ): Game {
        val filePath = uri.toString()
        var nativePath: String? = null
        var gameInfo: GameInfo?
        if (BuildUtil.isGooglePlayBuild || FileUtil.isNativePath(filePath) ||
            filePath.startsWith("!")
        ) {
            gameInfo = GameInfo(filePath)
        } else {
            nativePath = if (uri.scheme == "fd") {
                uri.toString()
            } else {
                "!" + NativeLibrary.getNativePath(uri)
            }
            gameInfo = GameInfo(nativePath)
        }

        val valid = gameInfo.isValid()
        val isEncrypted = gameInfo.isEncrypted()
        if (!valid) {
            gameInfo = null
        }

        val newGame = Game(
            valid,
            (
                gameInfo?.getTitle() ?: FileUtil.getFilename(
                    uri
                )
                ).replace("[\\t\\n\\r]+".toRegex(), " "),
            filePath.replace("\n", " "),
            // TODO: This next line can be deduplicated but I don't want to right now -OS
            if (BuildUtil.isGooglePlayBuild || FileUtil.isNativePath(filePath) ||
                filePath.startsWith("!")
            ) {
                filePath
            } else {
                nativePath!!
            },
            gameInfo?.getTitleID() ?: 0,
            mediaType,
            gameInfo?.getCompany() ?: "",
            if (isEncrypted) {
                CitraApplication.appContext.getString(R.string.unsupported_encrypted)
            } else {
                gameInfo?.getRegions()
                    ?: ""
            },
            isInstalled,
            gameInfo?.isSystemTitle() ?: false,
            gameInfo?.getIsVisibleSystemTitle() ?: false,
            gameInfo?.getIsInsertable() ?: false,
            gameInfo?.getIcon(),
            gameInfo?.getFileType() ?: "",
            gameInfo?.getFileType()?.contains("(Z)") ?: false,
            if (FileUtil.isNativePath(filePath)) {
                CitraApplication.documentsTree.getFilename(filePath)
            } else {
                FileUtil.getFilename(Uri.parse(filePath))
            }
        )

        if (addedToLibrary) {
            val addedTime = preferences.getLong(newGame.keyAddedToLibraryTime, 0L)
            if (addedTime == 0L) {
                preferences.edit()
                    .putLong(newGame.keyAddedToLibraryTime, System.currentTimeMillis())
                    .apply()
            }
        }

        return newGame
    }
}
