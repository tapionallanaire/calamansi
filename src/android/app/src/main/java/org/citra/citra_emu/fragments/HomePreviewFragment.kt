// Copyright 2026 Azahar Emulator Project
// Licensed under GPLv3 or any later version
// Refer to the LICENSE.txt file included.

package org.citra.citra_emu.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.citra.citra_emu.databinding.ViewHomePreviewBinding
import org.citra.citra_emu.ui.main.MainActivity
import org.citra.citra_emu.viewmodel.GamesViewModel
import org.citra.citra_emu.viewmodel.HomeViewModel

class HomePreviewFragment : Fragment() {
    private var _binding: ViewHomePreviewBinding? = null
    private val gamesViewModel: GamesViewModel by activityViewModels()
    private val homeViewModel: HomeViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = ViewHomePreviewBinding.inflate(inflater, container, false)
        _binding = binding
        binding.root.layoutParams?.height = ViewGroup.LayoutParams.MATCH_PARENT
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = requireNotNull(_binding)
        val activity = requireActivity() as MainActivity
        ViewCompat.setOnApplyWindowInsetsListener(view) { root, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            root.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom
            )
            insets
        }
        val lifecycle = viewLifecycleOwner.lifecycle
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                gamesViewModel.selectedHomeGame.collect { game ->
                    view.doOnAttach {
                        val home = activity.homeFragment() ?: return@doOnAttach
                        home.view?.doOnAttach {
                            if (_binding === binding &&
                                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                gamesViewModel.selectedHomeGame.value === game
                            ) {
                                home.bindPreview(binding, game)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        homeViewModel.setNavigationVisibility(visible = true, animated = false)
        homeViewModel.setStatusBarShadeVisibility(visible = true)
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
