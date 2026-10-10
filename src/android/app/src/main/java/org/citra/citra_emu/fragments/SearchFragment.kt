// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv3 or any later version
// Refer to the LICENSE.txt file included.

package org.citra.citra_emu.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import info.debatty.java.stringsimilarity.Jaccard
import info.debatty.java.stringsimilarity.JaroWinkler
import java.time.temporal.ChronoField
import java.util.Locale
import kotlinx.coroutines.launch
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.R
import org.citra.citra_emu.adapters.GameAdapter
import org.citra.citra_emu.databinding.FragmentSearchBinding
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.viewmodel.GamesViewModel
import org.citra.citra_emu.ui.main.MainActivity
import org.citra.citra_emu.viewmodel.HomeViewModel

class SearchFragment : Fragment() {
    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private val gamesViewModel: GamesViewModel by activityViewModels()
    private val homeViewModel: HomeViewModel by activityViewModels()
    private lateinit var gameAdapter: GameAdapter

    private val openImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        gameAdapter.handleShortcutImageResult(uri)
    }

    private var shouldCompress: Boolean = true
    private var pendingCompressInvocation: String? = null
    private val onCompressDecompressLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        GamesFragment.doCompression(
            this,
            gamesViewModel,
            pendingCompressInvocation,
            uri,
            shouldCompress
        )
        pendingCompressInvocation = null
    }

    private lateinit var preferences: SharedPreferences

    companion object {
        private const val SEARCH_TEXT = "SearchText"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchBinding.inflate(layoutInflater)
        return binding.root
    }

    // This is using the correct scope, lint is just acting up
    @SuppressLint("UnsafeRepeatOnLifecycleDetector")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        homeViewModel.setNavigationVisibility(visible = true, animated = true)
        homeViewModel.setStatusBarShadeVisibility(visible = true)

        preferences = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)

        if (savedInstanceState != null) {
            binding.searchText.setText(savedInstanceState.getString(SEARCH_TEXT))
        }

        val inflater = LayoutInflater.from(requireContext())

        gameAdapter = GameAdapter(
            requireActivity() as AppCompatActivity,
            inflater,
            openImageLauncher,
            navController = findNavController(),
            onBeforeNavigate = {
                (requireActivity() as MainActivity).homeFragment()?.handoffHome()
            },
            onBeforeModal = {
                (requireActivity() as MainActivity).homeFragment()?.suspendHomeForModal()
            },
            onRequestCompressOrDecompress = { inputPath, suggestedName, shouldCompress ->
                pendingCompressInvocation = inputPath
                onCompressDecompressLauncher.launch(suggestedName)
                this.shouldCompress = shouldCompress
            }

        )

        binding.gridGamesSearch.apply {
            val grid = GridLayoutManager(context, 1)
            layoutManager = grid
            addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val width = view.width - view.paddingLeft - view.paddingRight
                val columns = (width / resources.getDimensionPixelSize(R.dimen.home_tile_width)).coerceAtLeast(1)
                if (grid.spanCount != columns) grid.spanCount = columns
            }
            adapter = this@SearchFragment.gameAdapter
        }

        binding.chipGroup.setOnCheckedStateChangeListener { _, _ -> filterAndSearch() }

        binding.searchText.doOnTextChanged { text: CharSequence?, _: Int, _: Int, _: Int ->
            if (text.toString().isNotEmpty()) {
                binding.clearButton.visibility = View.VISIBLE
            } else {
                binding.clearButton.visibility = View.INVISIBLE
            }
            filterAndSearch()
        }

        viewLifecycleOwner.lifecycleScope.apply {
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    gamesViewModel.searchFocused.collect {
                        if (it) {
                            focusSearch()
                            gamesViewModel.setSearchFocused(false)
                        }
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    gamesViewModel.games.collect { filterAndSearch() }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    gamesViewModel.searchedGames.collect {
                        (binding.gridGamesSearch.adapter as GameAdapter).submitList(it)
                        if (it.isEmpty()) {
                            binding.noResultsView.visibility = View.VISIBLE
                        } else {
                            binding.noResultsView.visibility = View.GONE
                        }
                    }
                }
            }
        }

        binding.clearButton.setOnClickListener { binding.searchText.setText("") }

        binding.searchBackground.setOnClickListener { focusSearch() }

        setInsets()
        filterAndSearch()
    }

    override fun onResume() {
        super.onResume()
        homeViewModel.setNavigationVisibility(visible = true, animated = true)
        homeViewModel.setStatusBarShadeVisibility(visible = true)
    }

    private inner class ScoredGame(val score: Double, val item: Game)

    private fun filterAndSearch() {
        if (binding.searchText.text.toString().isEmpty() &&
            binding.chipGroup.checkedChipId == View.NO_ID
        ) {
            gamesViewModel.setSearchedGames(emptyList())
            return
        }

        val baseList = gamesViewModel.games.value
        val filteredList: List<Game> = when (binding.chipGroup.checkedChipId) {
            R.id.chip_recently_played -> {
                baseList.filter {
                    val lastPlayedTime = preferences.getLong(it.keyLastPlayedTime, 0L)
                    lastPlayedTime >
                        (System.currentTimeMillis() - ChronoField.MILLI_OF_DAY.range().maximum)
                }
            }

            R.id.chip_recently_added -> {
                baseList.filter {
                    val addedTime = preferences.getLong(it.keyAddedToLibraryTime, 0L)
                    addedTime >
                        (System.currentTimeMillis() - ChronoField.MILLI_OF_DAY.range().maximum)
                }
            }

            R.id.chip_installed -> baseList.filter { it.isInstalled }

            else -> baseList
        }

        if (binding.searchText.text.toString().isEmpty() &&
            binding.chipGroup.checkedChipId != View.NO_ID
        ) {
            gamesViewModel.setSearchedGames(filteredList)
            return
        }

        val searchTerm = binding.searchText.text.toString().lowercase(Locale.getDefault())
        val searchAlgorithm = if (searchTerm.length > 1) Jaccard(2) else JaroWinkler()
        val sortedList: List<Game> = filteredList.mapNotNull { game ->
            val title = game.title.lowercase(Locale.getDefault())
            val score = searchAlgorithm.similarity(searchTerm, title)
            if (score > 0.03) {
                ScoredGame(score, game)
            } else {
                null
            }
        }.sortedByDescending { it.score }.map { it.item }
        gamesViewModel.setSearchedGames(sortedList)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (_binding != null) {
            outState.putString(SEARCH_TEXT, binding.searchText.text.toString())
        }
    }

    private fun focusSearch() {
        if (_binding != null) {
            binding.searchText.requestFocus()
            val imm = requireActivity()
                .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
            imm?.showSoftInput(binding.searchText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setInsets() = ViewCompat.setOnApplyWindowInsetsListener(
        binding.root
    ) { view: View, windowInsets: WindowInsetsCompat ->
        val barInsets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val extraListSpacing = resources.getDimensionPixelSize(R.dimen.spacing_med)
        val dual = (requireActivity() as MainActivity).isDualHome
        val spacingNavigation = if (dual) 0 else
            resources.getDimensionPixelSize(R.dimen.spacing_navigation)
        val spacingNavigationRail = if (dual) 0 else
            resources.getDimensionPixelSize(R.dimen.spacing_navigation_rail)
        val chipSpacing = resources.getDimensionPixelSize(R.dimen.spacing_chip)

        binding.constraintSearch.updatePadding(
            left = barInsets.left,
            top = barInsets.top,
            right = barInsets.right
        )

        binding.gridGamesSearch.updatePadding(
            top = extraListSpacing,
            bottom = barInsets.bottom + spacingNavigation + extraListSpacing
        )
        binding.noResultsView.updatePadding(bottom = spacingNavigation + barInsets.bottom)

        val mlpDivider = binding.divider.layoutParams as ViewGroup.MarginLayoutParams
        if (view.layoutDirection == View.LAYOUT_DIRECTION_LTR) {
            binding.frameSearch.updatePadding(left = spacingNavigationRail, right = 0)
            binding.gridGamesSearch.updatePadding(left = spacingNavigationRail, right = 0)
            binding.noResultsView.updatePadding(left = spacingNavigationRail, right = 0)
            binding.chipGroup.updatePadding(
                left = chipSpacing + spacingNavigationRail,
                right = chipSpacing
            )
            mlpDivider.leftMargin = chipSpacing + spacingNavigationRail
            mlpDivider.rightMargin = chipSpacing
        } else {
            binding.frameSearch.updatePadding(left = 0, right = spacingNavigationRail)
            binding.gridGamesSearch.updatePadding(left = 0, right = spacingNavigationRail)
            binding.noResultsView.updatePadding(left = 0, right = spacingNavigationRail)
            binding.chipGroup.updatePadding(
                left = chipSpacing,
                right = chipSpacing + spacingNavigationRail
            )
            mlpDivider.leftMargin = chipSpacing
            mlpDivider.rightMargin = chipSpacing + spacingNavigationRail
        }
        binding.divider.layoutParams = mlpDivider

        windowInsets
    }
}
