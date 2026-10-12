// Copyright 2023-2026 Citra Emulator Project / Azahar Emulator Project
// Licensed under GPLv3 or any later version
// Refer to the LICENSE.txt file included.

package org.citra.citra_emu.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.preference.PreferenceManager
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.google.android.material.color.MaterialColors
import com.google.android.material.navigation.NavigationBarView
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.launch
import org.citra.citra_emu.BuildConfig
import org.citra.citra_emu.NativeLibrary
import org.citra.citra_emu.R
import org.citra.citra_emu.activities.EmulationActivity
import org.citra.citra_emu.contracts.OpenFileResultContract
import org.citra.citra_emu.databinding.ActivityMainBinding
import org.citra.citra_emu.dialogs.NetPlayDialog
import org.citra.citra_emu.features.settings.model.Settings
import org.citra.citra_emu.features.settings.model.SettingsViewModel
import org.citra.citra_emu.features.settings.ui.SettingsActivity
import org.citra.citra_emu.features.settings.utils.SettingsFile
import org.citra.citra_emu.fragments.AppsFragment
import org.citra.citra_emu.fragments.GamesFragment
import org.citra.citra_emu.fragments.GrantMissingFilesystemPermissionFragment
import org.citra.citra_emu.fragments.SelectUserDirectoryDialogFragment
import org.citra.citra_emu.fragments.UpdateUserDirectoryDialogFragment
import org.citra.citra_emu.utils.BuildUtil
import org.citra.citra_emu.utils.CiaInstallWorker
import org.citra.citra_emu.utils.CitraDirectoryHelper
import org.citra.citra_emu.utils.CitraDirectoryUtils
import org.citra.citra_emu.utils.DirectoryInitialization
import org.citra.citra_emu.utils.FileBrowserHelper
import org.citra.citra_emu.utils.InsetsHelper
import org.citra.citra_emu.utils.PermissionsHandler
import org.citra.citra_emu.utils.RefreshRateUtil
import org.citra.citra_emu.utils.ThemeUtil
import org.citra.citra_emu.viewmodel.GamesViewModel
import org.citra.citra_emu.viewmodel.HomeViewModel

open class MainActivity :
    AppCompatActivity(),
    ThemeProvider {
    private lateinit var binding: ActivityMainBinding
    private var navigationBackgroundTint: ColorStateList? = null
    private var navigationIconTint: ColorStateList? = null
    private var navigationTextTint: ColorStateList? = null
    private var navigationIndicatorTint: ColorStateList? = null
    private var controllerInHomeGrid = true
        set(value) {
            field = value
            updateHomeControllerFocus(focus = false)
        }

    private val homeViewModel: HomeViewModel by viewModels()
    private val gamesViewModel: GamesViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    override var themeId: Int = 0

    companion object {
        const val KEY_SETUP_CURRENT_PAGE = "SetupCurrentPage"
        const val KEY_CONSOLE_HOME = "ConsoleHome"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        RefreshRateUtil.enforceRefreshRate(this)

        val splashScreen = installSplashScreen()
        CitraDirectoryUtils.attemptAutomaticUpdateDirectory()
        splashScreen.setKeepOnScreenCondition {
            !DirectoryInitialization.areCitraDirectoriesReady() &&
                PermissionsHandler.hasWriteAccess(this) &&
                !CitraDirectoryUtils.needToUpdateManually()
        }

        if (PermissionsHandler.hasWriteAccess(applicationContext) &&
            DirectoryInitialization.areCitraDirectoriesReady() &&
            !CitraDirectoryUtils.needToUpdateManually() && !EmulationActivity.isRunning()
        ) {
            settingsViewModel.settings.loadSettings()
        }

        setTheme(R.style.Theme_Azahar_Home_Classic)
        super.onCreate(savedInstanceState)
        if (!EmulationActivity.isRunning()) NativeLibrary.initMultiplayer()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        (binding.navigationView as NavigationBarView).apply {
            navigationBackgroundTint = backgroundTintList
            navigationIconTint = itemIconTintList
            navigationTextTint = itemTextColor
            navigationIndicatorTint = itemActiveIndicatorColor
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // ^- These are set to transparent automatically on Vanilla Ice Cream and up.
            window.statusBarColor =
                ContextCompat.getColor(applicationContext, android.R.color.transparent)
            window.navigationBarColor =
                ContextCompat.getColor(applicationContext, android.R.color.transparent)
        }

        binding.statusBarShade.setBackgroundColor(
            ThemeUtil.getColorWithOpacity(
                MaterialColors.getColor(
                    binding.root,
                    com.google.android.material.R.attr.colorSurface
                ),
                ThemeUtil.SYSTEM_BAR_ALPHA
            )
        )
        if (InsetsHelper.getSystemGestureType(applicationContext) !=
            InsetsHelper.GESTURE_NAVIGATION
        ) {
            binding.navigationBarShade.setBackgroundColor(
                ThemeUtil.getColorWithOpacity(
                    MaterialColors.getColor(
                        binding.root,
                        com.google.android.material.R.attr.colorSurface
                    ),
                    ThemeUtil.SYSTEM_BAR_ALPHA
                )
            )
        }

        var applicationsClickTimestamp = TimeSource.Monotonic.markNow()

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        setUpNavigation(savedInstanceState, navHostFragment.navController)
        homeNavController.addOnDestinationChangedListener { _, destination, _ ->
            controllerInHomeGrid = destination.id != R.id.appsFragment
            homeFragment()?.onUpperDestinationChanged(destination.id)
            reconcileHomeLayout()
            updateHomeControllerFocus()
        }
        (binding.navigationView as NavigationBarView).setOnItemReselectedListener {
            when (it.itemId) {
                R.id.gamesFragment -> {
                    if (applicationsClickTimestamp.elapsedNow() < 300.milliseconds) {
                        Toast.makeText(this, BuildConfig.VERSION_NAME, Toast.LENGTH_LONG)
                            .show()
                    }
                    applicationsClickTimestamp = TimeSource.Monotonic.markNow()

                    gamesViewModel.setShouldScrollToTop(true)
                }

                R.id.searchFragment -> gamesViewModel.setSearchFocused(true)

                R.id.homeSettingsFragment -> SettingsActivity.launch(
                    this,
                    SettingsFile.FILE_NAME_CONFIG,
                    ""
                )
            }
        }

        // Prevents navigation from being drawn for a short time on recreation if set to hidden
        if (!homeViewModel.navigationVisible.value.first) {
            binding.navigationView.visibility = View.INVISIBLE
            binding.statusBarShade.visibility = View.INVISIBLE
        }

        lifecycleScope.apply {
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    homeViewModel.navigationVisible.collect {
                        showNavigation(it.first, it.second)
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    homeViewModel.statusBarShadeVisible.collect {
                        showStatusBarShade(it)
                    }
                }
            }
            launch {
                repeatOnLifecycle(Lifecycle.State.CREATED) {
                    homeViewModel.isPickingUserDir.collect { checkUserPermissions() }
                }
            }
        }

        setInsets()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!intent.getBooleanExtra(KEY_CONSOLE_HOME, false)) return
        if (PreferenceManager.getDefaultSharedPreferences(applicationContext)
                .getBoolean(Settings.PREF_FIRST_APP_LAUNCH, true)
        ) {
            return
        }
        val controller = (
            supportFragmentManager.findFragmentById(
                R.id.fragment_container
            ) as NavHostFragment
            )
            .navController
        if (controller.currentDestination?.id != R.id.gamesFragment &&
            !controller.popBackStack(R.id.gamesFragment, false)
        ) {
            controller.navigate(R.id.gamesFragment)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        // Save the user's current game state.
        outState.putInt(KEY_SETUP_CURRENT_PAGE, homeViewModel.setupCurrentPage)

        // Always call the superclass so it can save the view hierarchy state.
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        checkUserPermissions()
        // ponytail: charge snapshot on menu resume; a receiver is only needed for live updates.
        val charge = getSystemService(BatteryManager::class.java)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        binding.homeStatus.homeStatusBattery.apply {
            visibility = if (charge in 0..100) View.VISIBLE else View.INVISIBLE
            if (charge in 0..100) {
                drawable.level = ((charge + 24) / 25) * 2500
                contentDescription = getString(R.string.home_battery_level, charge)
            }
        }
        WindowCompat.getInsetsController(window, window.decorView)
            .hide(WindowInsetsCompat.Type.statusBars())

        super.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    internal val homeNavController: NavController
        get() = (
            supportFragmentManager.findFragmentById(
                R.id.fragment_container
            ) as NavHostFragment
            )
            .navController

    internal fun homeFragment(): GamesFragment? =
        supportFragmentManager.findFragmentById(R.id.home_library_container) as? GamesFragment

    internal val isDualHome: Boolean
        get() = homeFragment()?.hasSecondaryHome == true

    internal fun reconcileHomeLayout() {
        if (!::binding.isInitialized) return
        val games = homeNavController.currentDestination?.id == R.id.gamesFragment
        binding.homeLibraryContainer.visibility =
            if (games && !isDualHome) View.VISIBLE else View.GONE
        binding.fragmentContainer.visibility = if (!games || isDualHome) View.VISIBLE else View.GONE
        showNavigation(homeViewModel.navigationVisible.value.first, animated = false)
        (supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment)
            ?.childFragmentManager?.primaryNavigationFragment?.view?.let(
                ViewCompat::requestApplyInsets
            )
    }

    internal fun handleHomeNavigationKey(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BUTTON_L1 &&
            event.keyCode != KeyEvent.KEYCODE_BUTTON_R1
        ) {
            return false
        }
        if (homeFragment()?.homeInputEnabled != true) return false
        val navigation = binding.navigationView as NavigationBarView
        val menu = navigation.menu
        val count = menu.size()
        val destination = homeNavController.currentDestination?.id
        for (index in 0 until count) {
            if (menu.getItem(index).itemId != destination) continue
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                val direction = if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L1) -1 else 1
                navigation.selectedItemId = menu.getItem((index + direction + count) % count).itemId
            }
            return true
        }
        return false
    }

    private fun upperApps(): AppsFragment? =
        (supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment)
            ?.childFragmentManager?.primaryNavigationFragment as? AppsFragment

    internal fun updateHomeControllerFocus(focus: Boolean = true) {
        if (!::binding.isInitialized) return
        val apps = upperApps()
        if (controllerInHomeGrid) {
            apps?.setControllerFocus(false)
            homeFragment()?.setControllerFocus(true, focus)
        } else {
            homeFragment()?.setControllerFocus(false)
            apps?.setControllerFocus(true, focus)
        }
    }

    internal fun handoffControllerToApps(): Boolean {
        if (homeNavController.currentDestination?.id != R.id.appsFragment) return false
        controllerInHomeGrid = false
        upperApps()?.setControllerFocus(true)
        return true
    }

    internal fun onHomeGridTouched() {
        controllerInHomeGrid = true
    }

    internal fun handoffControllerToHome(): Boolean {
        controllerInHomeGrid = true
        if (homeFragment()?.focusHomeGrid() != null) return true
        controllerInHomeGrid = false
        return false
    }

    internal fun handleHomeControllerKey(event: KeyEvent): Boolean {
        if (!isDualHome || homeFragment()?.homeInputEnabled != true ||
            (
                !event.isFromSource(InputDevice.SOURCE_GAMEPAD) &&
                    event.device?.supportsSource(InputDevice.SOURCE_GAMEPAD) != true
                )
        ) {
            return false
        }
        if (!controllerInHomeGrid &&
            homeNavController.currentDestination?.id == R.id.appsFragment
        ) {
            // Apps owns these inputs even while its list is loading.
            upperApps()?.handleHomeControllerKey(event)
            return true
        }
        return homeFragment()?.handleHomeKeyEvent(event) == true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            controllerInHomeGrid = homeNavController.currentDestination?.id != R.id.appsFragment
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        handleHomeNavigationKey(event) || handleHomeControllerKey(event) ||
            (
                (
                    (controllerInHomeGrid && !binding.fragmentContainer.hasFocus()) ||
                        homeNavController.currentDestination?.id == R.id.gamesFragment
                    ) &&
                    homeFragment()?.handleHomeKeyEvent(event) == true
                ) || super.dispatchKeyEvent(event)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowCompat.getInsetsController(window, window.decorView)
                .hide(WindowInsetsCompat.Type.statusBars())
        }
        homeFragment()?.onHomeOwnerFocusChanged(hasFocus)
    }
    fun setClassicHomeChrome(classic: Boolean) {
        val surface = if (classic) {
            ContextCompat.getColor(this, R.color.home_silver)
        } else {
            MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorSurface)
        }
        (binding.navigationView as NavigationBarView).apply {
            backgroundTintList = if (classic) {
                ContextCompat.getColorStateList(
                    this@MainActivity,
                    R.color.home_silver
                )
            } else {
                navigationBackgroundTint
            }
            val text = ContextCompat.getColorStateList(
                this@MainActivity,
                R.color.home_navigation_tint
            )
            itemIconTintList = if (classic) text else navigationIconTint
            itemTextColor = if (classic) text else navigationTextTint
            itemActiveIndicatorColor = if (classic) {
                ContextCompat.getColorStateList(
                    this@MainActivity,
                    R.color.home_action_container
                )
            } else {
                navigationIndicatorTint
            }
        }
        binding.root.setBackgroundColor(surface)
        binding.statusBarShade.setBackgroundColor(
            ThemeUtil.getColorWithOpacity(surface, ThemeUtil.SYSTEM_BAR_ALPHA)
        )
        if (InsetsHelper.getSystemGestureType(this) != InsetsHelper.GESTURE_NAVIGATION) {
            binding.navigationBarShade.setBackgroundColor(
                ThemeUtil.getColorWithOpacity(surface, ThemeUtil.SYSTEM_BAR_ALPHA)
            )
        }
    }

    fun displayMultiplayerDialog() {
        val dialog = NetPlayDialog(this)
        dialog.show()
    }

    override fun setTheme(resId: Int) {
        super.setTheme(resId)
        themeId = resId
    }

    private fun checkUserPermissions() {
        val firstTimeSetup = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getBoolean(Settings.PREF_FIRST_APP_LAUNCH, true)

        if (firstTimeSetup) {
            return
        }

        if (!BuildUtil.isGooglePlayBuild) {
            fun requestMissingFilesystemPermission() =
                GrantMissingFilesystemPermissionFragment.newInstance()
                    .show(supportFragmentManager, GrantMissingFilesystemPermissionFragment.TAG)

            if (supportFragmentManager.findFragmentByTag(
                    GrantMissingFilesystemPermissionFragment.TAG
                ) ==
                null
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!Environment.isExternalStorageManager()) {
                        requestMissingFilesystemPermission()
                    }
                } else {
                    if (ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestMissingFilesystemPermission()
                    }
                }
            }
        }

        if (homeViewModel.isPickingUserDir.value) {
            return
        }

        if (!PermissionsHandler.hasWriteAccess(this)) {
            SelectUserDirectoryDialogFragment.newInstance(this)
                .show(supportFragmentManager, SelectUserDirectoryDialogFragment.TAG)
            return
        } else if (CitraDirectoryUtils.needToUpdateManually()) {
            UpdateUserDirectoryDialogFragment.newInstance(this)
                .show(supportFragmentManager, UpdateUserDirectoryDialogFragment.TAG)
            return
        }

        if (!BuildUtil.isGooglePlayBuild) {
            if (supportFragmentManager.findFragmentByTag(SelectUserDirectoryDialogFragment.TAG) ==
                null
            ) {
                if (NativeLibrary.getUserDirectory() == "") {
                    SelectUserDirectoryDialogFragment.newInstance(this)
                        .show(supportFragmentManager, SelectUserDirectoryDialogFragment.TAG)
                }
            }
        }
    }

    fun finishSetup(navController: NavController) {
        navController.navigate(R.id.action_firstTimeSetupFragment_to_gamesFragment)
        (binding.navigationView as NavigationBarView).setupWithNavController(navController)
    }

    private fun setUpNavigation(savedInstanceState: Bundle?, navController: NavController) {
        val firstTimeSetup = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getBoolean(Settings.PREF_FIRST_APP_LAUNCH, true)

        if (firstTimeSetup && !homeViewModel.navigatedToSetup) {
            homeViewModel.setupCurrentPage = savedInstanceState?.getInt(KEY_SETUP_CURRENT_PAGE) ?: 0
            navController.navigate(R.id.firstTimeSetupFragment)
            homeViewModel.navigatedToSetup = true
        } else {
            (binding.navigationView as NavigationBarView).setupWithNavController(navController)
        }
    }

    private fun showNavigation(visible: Boolean, animated: Boolean) {
        if (isDualHome) {
            binding.navigationView.animate().cancel()
            binding.navigationView.visibility = View.INVISIBLE
            return
        }
        if (!animated) {
            binding.navigationView.animate().cancel()
            binding.navigationView.translationX = 0f
            binding.navigationView.translationY = 0f
            if (visible) {
                binding.navigationView.visibility = View.VISIBLE
            } else {
                binding.navigationView.visibility = View.INVISIBLE
            }
            return
        }

        val smallLayout = resources.getBoolean(R.bool.small_layout)
        binding.navigationView.animate().apply {
            if (visible) {
                binding.navigationView.visibility = View.VISIBLE
                duration = 300
                interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

                if (smallLayout) {
                    binding.navigationView.translationY =
                        binding.navigationView.height.toFloat() * 2
                    translationY(0f)
                } else {
                    if (binding.navigationView.layoutDirection ==
                        View.LAYOUT_DIRECTION_LTR
                    ) {
                        binding.navigationView.translationX =
                            binding.navigationView.width.toFloat() * -2
                        translationX(0f)
                    } else {
                        binding.navigationView.translationX =
                            binding.navigationView.width.toFloat() * 2
                        translationX(0f)
                    }
                }
            } else {
                duration = 300
                interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

                if (smallLayout) {
                    translationY(binding.navigationView.height.toFloat() * 2)
                } else {
                    if (binding.navigationView.layoutDirection ==
                        View.LAYOUT_DIRECTION_LTR
                    ) {
                        translationX(binding.navigationView.width.toFloat() * -2)
                    } else {
                        translationX(binding.navigationView.width.toFloat() * 2)
                    }
                }
            }
        }.withEndAction {
            if (!visible) {
                binding.navigationView.visibility = View.INVISIBLE
            }
        }.start()
    }

    private fun showStatusBarShade(visible: Boolean) {
        binding.statusBarShade.animate().apply {
            if (visible) {
                binding.statusBarShade.visibility = View.VISIBLE
                binding.statusBarShade.translationY = binding.statusBarShade.height.toFloat() * -2
                duration = 300
                translationY(0f)
                interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
            } else {
                duration = 300
                translationY(binding.navigationView.height.toFloat() * -2)
                interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
            }
        }.withEndAction {
            if (!visible) {
                binding.statusBarShade.visibility = View.INVISIBLE
            }
        }.start()
    }

    private fun setInsets() {
        val status = binding.homeStatus.root
        val left = status.paddingLeft
        val top = status.paddingTop
        val right = status.paddingRight
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val safe = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            status.setPadding(
                left + safe.left,
                top + safe.top,
                right + safe.right,
                status.paddingBottom
            )
            val mlpStatusShade = binding.statusBarShade.layoutParams as MarginLayoutParams
            mlpStatusShade.height = insets.top
            binding.statusBarShade.layoutParams = mlpStatusShade

            val mlpNavShade = binding.navigationBarShade.layoutParams as MarginLayoutParams
            mlpNavShade.height = insets.bottom
            binding.navigationBarShade.layoutParams = mlpNavShade
            windowInsets
        }
    }

    private fun createOpenCitraDirectoryLauncher(
        permissionsLost: Boolean
    ): ActivityResultLauncher<Uri?> {
        return registerForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { result: Uri? ->
            if (result == null) {
                return@registerForActivityResult
            }

            if (!BuildUtil.isGooglePlayBuild) {
                if (NativeLibrary.getNativePath(result) == "") {
                    SelectUserDirectoryDialogFragment.newInstance(
                        this,
                        R.string.invalid_selection,
                        R.string.invalid_user_directory
                    ).show(supportFragmentManager, SelectUserDirectoryDialogFragment.TAG)
                    return@registerForActivityResult
                }
            }

            CitraDirectoryHelper(this@MainActivity, permissionsLost)
                .showCitraDirectoryDialog(result, buttonState = {})
        }
    }

    val openCitraDirectory = createOpenCitraDirectoryLauncher(permissionsLost = false)
    val openCitraDirectoryLostPermission = createOpenCitraDirectoryLauncher(permissionsLost = true)

    val ciaFileInstaller = registerForActivityResult(
        OpenFileResultContract()
    ) { result: Intent? ->
        if (result == null) {
            return@registerForActivityResult
        }

        val selectedFiles =
            FileBrowserHelper.getSelectedFiles(result, applicationContext, listOf("cia", "zcia"))
        if (selectedFiles == null) {
            Toast.makeText(applicationContext, R.string.cia_file_not_found, Toast.LENGTH_LONG)
                .show()
            return@registerForActivityResult
        }

        val workManager = WorkManager.getInstance(applicationContext)
        workManager.enqueueUniqueWork(
            "installCiaWork",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequest.Builder(CiaInstallWorker::class.java)
                .setInputData(
                    Data.Builder().putStringArray("CIA_FILES", selectedFiles)
                        .build()
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
        )
    }

    val setupOpenCitraDirectory = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { result: Uri? ->
        homeViewModel.selectedCitraDirectory = result
    }

    val setupGetGamesDirectory = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { result: Uri? ->
        homeViewModel.selectedGamesDirectory = result
    }
}
