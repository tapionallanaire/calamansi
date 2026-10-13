// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv2 or any later version
// Refer to the misc/licenses/gplv2.txt file included.

package org.citra.citra_emu.viewmodel

import android.widget.Toast
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.model.resolveHomeSelection
import org.citra.citra_emu.utils.FileUtil
import org.citra.citra_emu.utils.GameHelper

class GamesViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    val games get() = _games.asStateFlow()
    private val _games = MutableStateFlow(emptyList<Game>())

    val searchedGames get() = _searchedGames.asStateFlow()
    private val _searchedGames = MutableStateFlow(emptyList<Game>())

    val isReloading get() = _isReloading.asStateFlow()
    private val _isReloading = MutableStateFlow(false)
    private var reloadPending = false
    private var directoryChangePending = false

    val shouldSwapData get() = _shouldSwapData.asStateFlow()
    private val _shouldSwapData = MutableStateFlow(false)

    val shouldScrollToTop get() = _shouldScrollToTop.asStateFlow()
    private val _shouldScrollToTop = MutableStateFlow(false)

    val searchFocused get() = _searchFocused.asStateFlow()
    private val _searchFocused = MutableStateFlow(false)

    private val _selectedHomeGame = MutableStateFlow<Game?>(null)
    val selectedHomeGame = _selectedHomeGame.asStateFlow()

    fun selectHomeGame(game: Game?) {
        savedStateHandle["home_selected_path"] = game?.path
        _selectedHomeGame.value = game
    }

    fun reconcileHomeGames(visible: List<Game>) {
        if (visible.isEmpty() && _isReloading.value) return
        selectHomeGame(resolveHomeSelection(visible, savedStateHandle["home_selected_path"]))
    }

    init {
        // Retrieve list of cached games
        val storedGames = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
            .getStringSet(GameHelper.KEY_GAMES, emptySet())
        if (storedGames!!.isNotEmpty()) {
            val deserializedGames = mutableSetOf<Game>()
            storedGames.forEach {
                val game: Game
                try {
                    game = Json.decodeFromString(it)
                } catch (ignored: Exception) {
                    return@forEach
                }

                if (game.isInstalled || FileUtil.exists(game.path)) {
                    deserializedGames.add(game)
                }
            }
            GameHelper.cachedGameList = GameHelper.distinctGames(deserializedGames).toMutableList()
            setGames(GameHelper.cachedGameList)
        }
        reloadGames(false)
    }

    fun setGames(games: List<Game>) {
        val sortedList = games.sortedWith(
            compareBy(
                { it.title.lowercase(Locale.getDefault()) },
                { it.path }
            )
        )
        val filteredList = sortedList.filter {
            if (it.isSystemTitle) {
                it.isVisibleSystemTitle
            } else {
                true
            }
        }

        _games.value = filteredList
    }

    fun setSearchedGames(games: List<Game>) {
        _searchedGames.value = games
    }

    fun setShouldSwapData(shouldSwap: Boolean) {
        _shouldSwapData.value = shouldSwap
    }

    fun setShouldScrollToTop(shouldScroll: Boolean) {
        _shouldScrollToTop.value = shouldScroll
    }

    fun setSearchFocused(searchFocused: Boolean) {
        _searchFocused.value = searchFocused
    }

    fun reloadGames(directoryChanged: Boolean) {
        reloadPending = true
        directoryChangePending = directoryChangePending || directoryChanged
        if (isReloading.value) return
        _isReloading.value = true

        viewModelScope.launch {
            try {
                do {
                    reloadPending = false
                    val scanned = withContext(Dispatchers.IO) { GameHelper.getGames() }
                    if (scanned == null) {
                        // ponytail: 4 deferred-scan checks/sec; use an observer if polling matters.
                        reloadPending = true
                        delay(250)
                    } else if (!reloadPending) {
                        setGames(scanned)
                    }
                } while (reloadPending)
                if (directoryChangePending) setShouldSwapData(true)
                directoryChangePending = false
            } catch (error: IOException) {
                Toast.makeText(
                    CitraApplication.appContext,
                    error.message,
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                _isReloading.value = false
            }
        }
    }
}
