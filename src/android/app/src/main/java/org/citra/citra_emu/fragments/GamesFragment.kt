// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv3 or any later version
// Refer to the LICENSE.txt file included.

package org.citra.citra_emu.fragments

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.PowerManager
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.ui.onNavDestinationSelected
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialFadeThrough
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.citra.citra_emu.BuildConfig
import org.citra.citra_emu.CitraApplication
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.activities.EmulationActivity
import org.citra.citra_emu.adapters.GameAdapter
import org.citra.citra_emu.databinding.FragmentGamesBinding
import org.citra.citra_emu.databinding.ViewHomeLibraryBinding
import org.citra.citra_emu.databinding.ViewHomePreviewBinding
import org.citra.citra_emu.display.HomePresentation
import org.citra.citra_emu.display.secondaryDisplayCandidates
import org.citra.citra_emu.display.selectSecondaryDisplay
import org.citra.citra_emu.features.settings.model.BooleanSetting
import org.citra.citra_emu.features.settings.model.IntSetting
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.ui.SettingsActivity
import org.citra.citra_emu.features.settings.utils.SettingsFile
import org.citra.citra_emu.features.updatechecker.UpdateChecker
import org.citra.citra_emu.model.Game
import org.citra.citra_emu.ui.main.MainActivity
import org.citra.citra_emu.utils.BuildUtil
import org.citra.citra_emu.utils.GameIconUtils
import org.citra.citra_emu.viewmodel.CompressProgressDialogViewModel
import org.citra.citra_emu.viewmodel.GamesViewModel
import org.citra.citra_emu.viewmodel.HomeViewModel

class GamesFragment : Fragment() {
    private var _binding: FragmentGamesBinding? = null
    private val binding get() = _binding!!

    private val gamesViewModel: GamesViewModel by activityViewModels()
    private val homeViewModel: HomeViewModel by activityViewModels()
    private lateinit var gameAdapter: GameAdapter
    private var activePanel: ViewHomeLibraryBinding? = null
    private var homePresentation: HomePresentation? = null
    internal val hasSecondaryHome: Boolean get() = homePresentation != null
    private var gridState: Parcelable? = null
    private var homeResumed = false
    private var modalSuspended = false
    private var handingOff = false
    private var listening = false
    private var confirmingKey: Int? = null
    private var previousLightStatusBars = false
    private var previousLightNavigationBars = false
    private val displayManager get() = requireActivity().getSystemService(
        DisplayManager::class.java
    )
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateHomeDisplay()
        override fun onDisplayRemoved(displayId: Int) = updateHomeDisplay()
        override fun onDisplayChanged(displayId: Int) =
            updateHomeDisplay(recreate = homePresentation?.display?.displayId == displayId)
    }
    private val modalListener = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
            if (f is DialogFragment) suspendHomeForModal()
        }
    }

    private val openImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        gameAdapter.handleShortcutImageResult(uri)
    }

    private var shouldCompress: Boolean = true
    private var pendingCompressInvocation: String? = null

    companion object {
        fun doCompression(
            fragment: Fragment,
            gamesViewModel: GamesViewModel,
            inputPath: String?,
            outputUri: Uri?,
            shouldCompress: Boolean
        ) {
            if (outputUri != null) {
                val outputPath: String =
                    if (!BuildUtil.isGooglePlayBuild) {
                        "!" + NativeLibrary.getNativePath(outputUri)
                    } else {
                        outputUri.toString()
                    }
                CompressProgressDialogViewModel.reset()
                val dialog = CompressProgressDialogFragment.newInstance(shouldCompress, outputPath)
                dialog.showNow(
                    fragment.requireActivity().supportFragmentManager,
                    CompressProgressDialogFragment.TAG
                )

                fragment.lifecycleScope.launch(Dispatchers.IO) {
                    val status = if (shouldCompress) {
                        NativeLibrary.compressFile(inputPath, outputPath)
                    } else {
                        NativeLibrary.decompressFile(inputPath, outputPath)
                    }

                    fragment.requireActivity().runOnUiThread {
                        dialog.dismiss()
                        @Suppress("ktlint:standard:max-line-length")
                        val resId = when (status) {
                            NativeLibrary.CompressStatus.SUCCESS -> if (shouldCompress) R.string.compress_success else R.string.decompress_success
                            NativeLibrary.CompressStatus.COMPRESS_UNSUPPORTED -> R.string.compress_unsupported
                            NativeLibrary.CompressStatus.COMPRESS_ALREADY_COMPRESSED -> R.string.compress_already
                            NativeLibrary.CompressStatus.COMPRESS_FAILED -> R.string.compress_failed
                            NativeLibrary.CompressStatus.DECOMPRESS_UNSUPPORTED -> R.string.decompress_unsupported
                            NativeLibrary.CompressStatus.DECOMPRESS_NOT_COMPRESSED -> R.string.decompress_not_compressed
                            NativeLibrary.CompressStatus.DECOMPRESS_FAILED -> R.string.decompress_failed
                            NativeLibrary.CompressStatus.INSTALLED_APPLICATION -> R.string.compress_decompress_installed_app
                        }

                        MaterialAlertDialogBuilder(fragment.requireContext())
                            .setMessage(fragment.getString(resId))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()

                        gamesViewModel.reloadGames(false)
                    }
                }
            }
        }
    }

    private val onCompressDecompressLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        doCompression(this, gamesViewModel, pendingCompressInvocation, uri, shouldCompress)
        pendingCompressInvocation = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGamesBinding.inflate(
            inflater.cloneInContext(
                ContextThemeWrapper(requireContext(), R.style.Theme_Azahar_Home_Classic)
            )
        )
        return binding.root
    }

    // This is using the correct scope, lint is just acting up
    @SuppressLint("UnsafeRepeatOnLifecycleDetector")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        homeViewModel.setNavigationVisibility(visible = true, animated = true)
        homeViewModel.setStatusBarShadeVisibility(visible = true)

        val inflater = LayoutInflater.from(requireContext())

        gameAdapter = GameAdapter(
            requireActivity() as AppCompatActivity,
            inflater,
            openImageLauncher,
            navController = (requireActivity() as MainActivity).homeNavController,
            onRequestCompressOrDecompress = { inputPath, suggestedName, shouldCompress ->
                pendingCompressInvocation = inputPath
                onCompressDecompressLauncher.launch(suggestedName)
                this.shouldCompress = shouldCompress
            },
            onHomeTileClick = { game ->
                if (gamesViewModel.selectedHomeGame.value?.path == game.path ||
                    EmulationActivity.activeGame?.path == game.path
                ) {
                    gameAdapter.launchGame(game)
                } else {
                    gamesViewModel.selectHomeGame(game)
                }
            },
            onHomeTileFocus = gamesViewModel::selectHomeGame,
            onBeforeNavigate = ::handoffHome,
            onBeforeModal = ::suspendHomeForModal
        )

        attachPanel(binding.homeLibrary, secondary = false)
        requireActivity().supportFragmentManager.registerFragmentLifecycleCallbacks(
            modalListener,
            true
        )

        viewLifecycleOwner.lifecycleScope.apply {
            launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    gamesViewModel.isReloading.collect { isReloading ->
                        refreshPanel()
                        if (!isReloading && gamesViewModel.games.value.isEmpty()) {
                            gamesViewModel.reconcileHomeGames(gameAdapter.currentList)
                        }
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    gamesViewModel.games.collectLatest { setAdapter(it) }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    gamesViewModel.shouldSwapData.collect {
                        if (it) {
                            setAdapter(gamesViewModel.games.value)
                            gamesViewModel.setShouldSwapData(false)
                        }
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    gamesViewModel.shouldScrollToTop.collect {
                        if (it) {
                            scrollToTop()
                            gamesViewModel.setShouldScrollToTop(false)
                        }
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    gamesViewModel.selectedHomeGame.collect { updateSelection(it) }
                }
            }
        }

        setInsets()
    }

    private fun getMajorVersion(version: String): Int? = version.split('.')[0].toIntOrNull()

    private fun isPrereleaseBuild(): Boolean {
        val version = BuildConfig.GIT_VERSION
        return (
            version.contains("alpha") ||
                version.contains("beta") ||
                version.contains("rc") ||
                version.contains("test")
            )
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as MainActivity).setClassicHomeChrome(true)
        val window = requireActivity().window
        WindowCompat.getInsetsController(window, window.decorView).apply {
            previousLightStatusBars = isAppearanceLightStatusBars
            previousLightNavigationBars = isAppearanceLightNavigationBars
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        homeResumed = true
        handingOff = false
        modalSuspended = modalSuspended || requireActivity().supportFragmentManager.fragments.any {
            it is DialogFragment && it.dialog?.isShowing == true
        }
        updateHomeDisplay()
        onHomeOwnerFocusChanged(requireActivity().hasWindowFocus())
        updateActiveGame()

        // Perform update check
        @Suppress("SimplifyBooleanWithConstants", "RedundantSuppression")
        if (!BuildConfig.DEBUG &&
            !BuildUtil.isGooglePlayBuild &&
            BooleanSetting.CHECK_FOR_UPDATES.boolean &&
            !homeViewModel.updatePromptShown
        ) {
            Thread({
                val checkForPrereleaseUpdates =
                    isPrereleaseBuild() || (IntSetting.UPDATE_CHECK_CHANNEL.int == 1)
                val latestReleaseTag = UpdateChecker.getLatestRelease(checkForPrereleaseUpdates)

                if (!latestReleaseTag.isNullOrEmpty() &&
                    latestReleaseTag != BuildConfig.GIT_VERSION
                ) {
                    val latestMajorVersion = getMajorVersion(latestReleaseTag)
                    val currentMajorVersion = getMajorVersion(BuildConfig.GIT_VERSION)
                    if (latestMajorVersion != null &&
                        currentMajorVersion != null &&
                        currentMajorVersion <= latestMajorVersion
                    ) {
                        UpdateAvailableNotificationFragment.newInstance(
                            latestReleaseTag,
                            checkForPrereleaseUpdates
                        )
                            .show(
                                requireActivity().supportFragmentManager,
                                UpdateAvailableNotificationFragment.TAG
                            )
                    }
                }
            }).start()
            homeViewModel.updatePromptShown = true
        }
    }

    override fun onPause() {
        homeResumed = false
        stopHomeDisplay()
        (requireActivity() as MainActivity).setClassicHomeChrome(false)
        val window = requireActivity().window
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = previousLightStatusBars
            isAppearanceLightNavigationBars = previousLightNavigationBars
        }
        super.onPause()
    }

    override fun onDestroyView() {
        homeResumed = false
        stopHomeDisplay()
        requireActivity().supportFragmentManager.unregisterFragmentLifecycleCallbacks(modalListener)
        gameAdapter.dismissProperties()
        activePanel?.gridGames?.adapter = null
        activePanel = null
        _binding = null
        super.onDestroyView()
    }

    private fun setAdapter(games: List<Game>) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(CitraApplication.appContext)
        val visible = if (preferences.getBoolean(Settings.PREF_SHOW_HOME_APPS, false)) {
            games
        } else {
            games.filter { !it.isSystemTitle }
        }
        gameAdapter.submitList(visible) {
            if (_binding != null) {
                gamesViewModel.reconcileHomeGames(gameAdapter.currentList)
                refreshPanel()
                updateSelection(selectedGame())
            }
        }
    }

    private fun scrollToTop() {
        if (_binding != null) {
            activePanel?.gridGames?.smoothScrollToPosition(0)
        }
    }

    private fun setInsets() = ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val dual = homePresentation != null
        val rail = if (!dual && !resources.getBoolean(R.bool.small_layout)) {
            resources.getDimensionPixelSize(R.dimen.spacing_navigation_rail)
        } else {
            0
        }
        val bottom = if (!dual && resources.getBoolean(R.bool.small_layout)) {
            resources.getDimensionPixelSize(R.dimen.spacing_navigation)
        } else {
            0
        }
        val rtl = binding.root.layoutDirection == View.LAYOUT_DIRECTION_RTL
        binding.root.updatePadding(
            left = bars.left + if (rtl) 0 else rail,
            top = bars.top,
            right = bars.right + if (rtl) rail else 0,
            bottom = bars.bottom + bottom
        )
        insets
    }

    private fun selectedGame(): Game? {
        val path = gamesViewModel.selectedHomeGame.value?.path ?: return null
        return gameAdapter.currentList.firstOrNull { it.path == path }
    }

    private fun attachPanel(panel: ViewHomeLibraryBinding, secondary: Boolean) {
        gridState = activePanel?.gridGames?.layoutManager?.onSaveInstanceState() ?: gridState
        activePanel?.gridGames?.adapter = null
        activePanel = panel
        panel.gridGames.apply {
            val manager =
                (layoutManager as? GridLayoutManager)
                    ?: GridLayoutManager(context, 1).also { grid ->
                        layoutManager = grid
                        addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                            val width = view.width - view.paddingLeft - view.paddingRight
                            val columns = (
                                width / resources.getDimensionPixelSize(
                                    R.dimen.home_tile_width
                                )
                                ).coerceAtLeast(1)
                            if (grid.spanCount != columns) grid.spanCount = columns
                        }
                    }
            adapter = gameAdapter
            manager.onRestoreInstanceState(gridState)
        }
        panel.swipeRefresh.apply {
            setOnRefreshListener { gamesViewModel.reloadGames(false) }
            setProgressBackgroundColorSchemeColor(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
            )
            setColorSchemeColors(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)
            )
        }
        panel.homeOpen.setOnClickListener { selectedGame()?.let(gameAdapter::launchGame) }
        panel.homeProperties.setOnClickListener {
            selectedGame()?.let(gameAdapter::showGameProperties)
        }
        panel.homeResumeActive.setOnClickListener {
            EmulationActivity.activeGame?.let(gameAdapter::launchGame)
        }
        panel.homeActiveSettings.setOnClickListener {
            val game = EmulationActivity.activeGame ?: return@setOnClickListener
            if (game.titleId == 0L) return@setOnClickListener
            suspendHomeForModal()
            SettingsActivity.launch(
                requireActivity(),
                SettingsFile.FILE_NAME_CONFIG,
                String.format("%016X", game.titleId)
            )
        }
        panel.homeNavigation.visibility = if (secondary) View.VISIBLE else View.GONE
        panel.homeNavigation.menu.findItem(
            (requireActivity() as MainActivity).homeNavController.currentDestination?.id
                ?: R.id.gamesFragment
        )?.isChecked = true
        panel.homeNavigation.setOnItemSelectedListener {
            if (it.itemId == R.id.gamesFragment &&
                (requireActivity() as MainActivity).homeNavController.currentDestination?.id ==
                R.id.gamesFragment
            ) {
                scrollToTop()
                true
            } else {
                it.onNavDestinationSelected((requireActivity() as MainActivity).homeNavController)
            }
        }
        if (secondary) {
            ViewCompat.setOnApplyWindowInsetsListener(panel.root) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            ViewCompat.requestApplyInsets(panel.root)
        }
        refreshPanel()
        updateSelection(selectedGame())
    }

    private fun refreshPanel() {
        val panel = activePanel ?: return
        val loading = gamesViewModel.isReloading.value
        panel.swipeRefresh.isRefreshing = loading
        panel.noticeText.visibility = if (gameAdapter.currentList.isEmpty() && !loading) {
            View.VISIBLE
        } else {
            View.GONE
        }
        panel.noticeText.setText(R.string.home_empty_games)
    }

    internal fun bindPreview(preview: ViewHomePreviewBinding, game: Game?) {
        preview.homePreviewTitle.text = game?.title ?: getString(R.string.home_select_game)
        preview.homePreviewMetadata.text = game?.let {
            listOf(it.company, gameAdapter.translateRegions(it.regions))
                .filter(String::isNotBlank).joinToString(" · ")
        } ?: ""
        if (game == null) {
            preview.homePreviewIcon.setImageResource(R.drawable.no_icon)
        } else {
            GameIconUtils.loadGameIcon(game, preview.homePreviewIcon)
        }
    }

    internal fun onUpperDestinationChanged(id: Int) {
        activePanel?.homeNavigation?.menu?.findItem(id)?.isChecked = true
        updateHomeDisplay()
    }

    private fun updateSelection(game: Game?) {
        if (_binding == null) return
        gameAdapter.setSelectedHomePath(game?.path)
        bindPreview(binding.homePreview, game)
        activePanel?.apply {
            homeSelectedTitle.text = game?.title ?: getString(R.string.home_select_game)
            homeOpen.isEnabled = game != null
            homeProperties.isEnabled = game != null
            homeOpen.contentDescription = game?.let { getString(R.string.home_open_game, it.title) }
            homeProperties.contentDescription =
                game?.let { getString(R.string.home_game_properties, it.title) }
        }
        updateActiveGame()
    }

    private fun updateActiveGame() {
        val game = EmulationActivity.activeGame
        gameAdapter.setActiveHomePath(game?.path)
        activePanel?.apply {
            homeActiveSession.visibility = if (game == null) View.GONE else View.VISIBLE
            if (game != null) {
                homeActiveTitle.text = game.title
                GameIconUtils.loadGameIcon(game, homeResumeActive)
                homeResumeActive.contentDescription =
                    getString(R.string.home_resume_game, game.title)
                homeActiveSettings.contentDescription =
                    getString(R.string.home_active_settings, game.title)
                homeActiveSettings.isEnabled = game.titleId != 0L
            }
        }
    }

    private fun canShowHome(): Boolean = _binding != null && homeResumed &&
        !handingOff && !requireActivity().isFinishing &&
        !requireActivity().isDestroyed &&
        (requireActivity() as MainActivity).homeNavController.currentDestination?.id !=
        R.id.firstTimeSetupFragment &&
        requireActivity().getSystemService(PowerManager::class.java).isInteractive

    internal val homeInputEnabled: Boolean
        get() = !modalSuspended && canShowHome()

    private fun updateHomeDisplay(recreate: Boolean = false) {
        if (!canShowHome()) {
            stopHomeDisplay()
            return
        }
        if (!listening) {
            displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
            listening = true
        }
        val display =
            selectSecondaryDisplay(requireActivity(), secondaryDisplayCandidates(requireActivity()))
        if (!recreate && homePresentation?.display?.displayId == display?.displayId) return
        dismissHomePresentation()
        if (display == null) return
        val activity = requireActivity()
        val presentation = HomePresentation(
            activity,
            display,
            { attachPanel(it, secondary = true) },
            ::handleHomeKeyEvent
        )
        homePresentation = presentation
        presentation.inputEnabled = !modalSuspended
        presentation.setOnDismissListener {
            if (homePresentation === presentation) {
                homePresentation = null
                restorePrimaryPanel()
            }
        }
        try {
            presentation.show()
            binding.homeLibrary.root.visibility = View.GONE
            homeViewModel.setNavigationVisibility(visible = false, animated = false)
            ViewCompat.requestApplyInsets(binding.root)
            (requireActivity() as MainActivity).reconcileHomeLayout()
        } catch (_: WindowManager.InvalidDisplayException) {
            dismissHomePresentation()
        } catch (_: WindowManager.BadTokenException) {
            dismissHomePresentation()
        }
    }

    private fun restorePrimaryPanel() {
        if (_binding == null) return
        attachPanel(binding.homeLibrary, secondary = false)
        binding.homeLibrary.root.visibility = View.VISIBLE
        homeViewModel.setNavigationVisibility(visible = true, animated = false)
        ViewCompat.requestApplyInsets(binding.root)
        (requireActivity() as MainActivity).reconcileHomeLayout()
    }

    private fun dismissHomePresentation() {
        val presentation = homePresentation ?: return
        homePresentation = null
        presentation.setOnDismissListener(null)
        presentation.dismiss()
        restorePrimaryPanel()
    }

    private fun stopHomeDisplay() {
        confirmingKey = null
        if (listening) {
            displayManager.unregisterDisplayListener(displayListener)
            listening = false
        }
        dismissHomePresentation()
    }

    internal fun suspendHomeForModal() {
        modalSuspended = true
        confirmingKey = null
        homePresentation?.inputEnabled = false
    }

    internal fun handoffHome() {
        handingOff = true
        stopHomeDisplay()
    }

    fun onHomeOwnerFocusChanged(focused: Boolean) {
        if (!focused || !modalSuspended || _binding == null) return
        binding.root.post {
            if (_binding != null && requireActivity().hasWindowFocus() &&
                !gameAdapter.hasPropertiesWindow() &&
                requireActivity().supportFragmentManager.fragments.none {
                    it is DialogFragment && it.dialog?.isShowing == true
                }
            ) {
                modalSuspended = false
                homePresentation?.inputEnabled = true
                updateHomeDisplay()
            }
        }
    }

    fun handleHomeKeyEvent(event: KeyEvent): Boolean {
        if (!homeInputEnabled) return false
        if ((requireActivity() as MainActivity).handleHomeNavigationKey(event)) return true
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> null
        }
        if (direction != null && homePresentation != null) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val panel = activePanel ?: return false
                val focused = panel.root.findFocus()
                if (focused == null) {
                    panel.gridGames.restoreDefaultFocus()
                } else {
                    focused.focusSearch(direction)?.requestFocus()
                }
            }
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (homePresentation == null && activePanel?.root?.hasFocus() != true &&
                    requireActivity().currentFocus?.isClickable == true
                ) {
                    return false
                }
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    confirmingKey = event.keyCode
                } else if (event.action == KeyEvent.ACTION_UP && confirmingKey == event.keyCode) {
                    confirmingKey = null
                    if (!event.isCanceled) {
                        val focused = activePanel?.root?.findFocus()
                        if (focused?.isClickable == true) {
                            focused.performClick()
                        } else {
                            selectedGame()?.let(gameAdapter::launchGame)
                        }
                    }
                }
                return true
            }

            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
                return true
            }
        }
        return false
    }
}
