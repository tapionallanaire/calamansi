// Copyright 2026 Azahar Emulator Project
// Licensed under GPLv3 or any later version
// Refer to the LICENSE.txt file included.

package org.citra.citra_emu.fragments

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import java.text.Collator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.citra.citra_emu.R
import org.citra.citra_emu.adapters.AppAdapter
import org.citra.citra_emu.databinding.FragmentAppsBinding
import org.citra.citra_emu.ui.main.MainActivity
import org.citra.citra_emu.viewmodel.HomeViewModel

class AppsFragment : Fragment() {
    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!
    private val homeViewModel: HomeViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        homeViewModel.setNavigationVisibility(visible = true, animated = true)
        homeViewModel.setStatusBarShadeVisibility(visible = true)

        binding.gridApps.apply {
            val grid = GridLayoutManager(context, 1)
            layoutManager = grid
            addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                val width = view.width - view.paddingLeft - view.paddingRight
                val columns = (width / resources.getDimensionPixelSize(R.dimen.app_tile_width))
                    .coerceAtLeast(1)
                if (grid.spanCount != columns) grid.spanCount = columns
            }
        }
        setInsets()

        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val packageManager = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val seenPackages = HashSet<String>()
                @Suppress("DEPRECATION")
                val activities = packageManager.queryIntentActivities(intent, 0)
                val entries = activities.mapNotNullTo(ArrayList()) { result ->
                    currentCoroutineContext().ensureActive()
                    val activity = result.activityInfo
                    if (!activity.enabled || !activity.exported || !activity.applicationInfo.enabled ||
                        activity.packageName == context.packageName ||
                        !seenPackages.add(activity.packageName)
                    ) {
                        null
                    } else {
                        AppAdapter.App(
                            ComponentName(activity.packageName, activity.name),
                            result.loadLabel(packageManager).toString(),
                            result.loadIcon(packageManager)
                        )
                    }
                }
                val collator = Collator.getInstance()
                entries.sortWith { first, second ->
                    val labelOrder = collator.compare(first.label, second.label)
                    if (labelOrder != 0) labelOrder else
                        first.component.packageName.compareTo(second.component.packageName)
                }
                entries
            }
            binding.gridApps.adapter = AppAdapter(apps, ::launchApp)
            binding.noAppsView.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        homeViewModel.setNavigationVisibility(visible = true, animated = true)
        homeViewModel.setStatusBarShadeVisibility(visible = true)
    }

    private fun launchApp(app: AppAdapter.App) {
        try {
            startActivity(Intent.makeMainActivity(app.component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            (requireActivity() as MainActivity).homeFragment()?.handoffHome()
        } catch (_: ActivityNotFoundException) {
            showLaunchFailure(app.label)
        } catch (_: SecurityException) {
            showLaunchFailure(app.label)
        }
    }

    private fun showLaunchFailure(label: String) {
        Toast.makeText(requireContext(), getString(R.string.home_app_launch_failed, label), Toast.LENGTH_SHORT)
            .show()
    }

    private fun setInsets() = ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val spacing = resources.getDimensionPixelSize(R.dimen.spacing_med)
        val headingSpacing = resources.getDimensionPixelSize(R.dimen.spacing_chip)
        val dual = (requireActivity() as MainActivity).isDualHome
        val navigation = if (dual) 0 else
            resources.getDimensionPixelSize(R.dimen.spacing_navigation)
        val rail = if (dual) 0 else
            resources.getDimensionPixelSize(R.dimen.spacing_navigation_rail)
        val leftRail = if (view.layoutDirection == View.LAYOUT_DIRECTION_LTR) rail else 0
        val rightRail = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) rail else 0
        view.updatePadding(
            left = bars.left,
            top = bars.top,
            right = bars.right
        )
        binding.appsTitle.updatePadding(
            left = headingSpacing + leftRail,
            right = headingSpacing + rightRail
        )
        binding.gridApps.updatePadding(
            left = spacing + leftRail,
            top = spacing,
            right = spacing + rightRail,
            bottom = bars.bottom + navigation + spacing
        )
        binding.noAppsView.updatePadding(
            left = leftRail,
            right = rightRail,
            bottom = bars.bottom + navigation
        )
        insets
    }

    override fun onDestroyView() {
        binding.gridApps.adapter = null
        super.onDestroyView()
        _binding = null
    }
}
