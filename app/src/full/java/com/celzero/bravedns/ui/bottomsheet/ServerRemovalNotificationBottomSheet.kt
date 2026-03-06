/*
 * Copyright 2025 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui.bottomsheet

import Logger
import Logger.LOG_TAG_UI
import android.animation.Animator
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.databinding.BottomsheetServerRemovalNotificationBinding
import com.celzero.bravedns.databinding.ItemRemovedServerBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Premium bottom sheet notification for informing users about removed server locations
 * Features:
 * - Elegant Material Design 3 UI
 * - Smooth animations
 * - List of removed servers with details
 * - Professional notification experience
 */
class ServerRemovalNotificationBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomsheetServerRemovalNotificationBinding? = null
    private val binding get() = _binding!!

    private var removedServers: List<CountryConfig> = emptyList()
    private var onDismissCallback: (() -> Unit)? = null

    companion object {
        private const val TAG = "ServerRemovalNotificationBS"
        private const val ARG_REMOVED_SERVERS = "removed_servers"

        fun newInstance(removedServers: List<CountryConfig>): ServerRemovalNotificationBottomSheet {
            return ServerRemovalNotificationBottomSheet().apply {
                arguments = Bundle().apply {
                    putParcelableArrayList(ARG_REMOVED_SERVERS, ArrayList<CountryConfig>(removedServers) as ArrayList<android.os.Parcelable>)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.BottomSheetDialogTheme)
        isCancelable = true
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomsheetServerRemovalNotificationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Extract removed servers from arguments
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        removedServers = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelableArrayList(ARG_REMOVED_SERVERS, CountryConfig::class.java) as? List<CountryConfig> ?: emptyList()
        } else {
            arguments?.getParcelableArrayList<CountryConfig>(ARG_REMOVED_SERVERS) ?: emptyList()
        }

        Logger.i(LOG_TAG_UI, "$TAG: showing notification for ${removedServers.size} removed servers")

        setupUI()
        animateEntry()
    }

    private fun setupUI() {
        // Update title and subtitle based on count
        if (removedServers.size == 1) {
            binding.notificationTitle.text = getString(R.string.server_removal_title)
            binding.notificationSubtitle.text = getString(R.string.server_removal_subtitle)
        } else {
            binding.notificationTitle.text = getString(R.string.server_removal_title).replace("Location", "Locations")
            binding.notificationSubtitle.text = "${removedServers.size} locations are no longer available"
        }

        // Populate removed servers list
        binding.removedServersContainer.removeAllViews()
        removedServers.forEachIndexed { index, server ->
            val itemBinding = ItemRemovedServerBinding.inflate(
                layoutInflater,
                binding.removedServersContainer,
                false
            )

            // Set server details
            itemBinding.serverFlag.text = server.flagEmoji
            itemBinding.serverName.text = server.countryName
            itemBinding.serverLocation.text = server.serverLocation

            // Add to container
            binding.removedServersContainer.addView(itemBinding.root)

            // Animate item entry with stagger
            itemBinding.root.alpha = 0f
            itemBinding.root.translationY = 20f
            itemBinding.root.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((index * 80L))
                .setDuration(400)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }

        // Setup action button
        binding.btnUnderstand.setOnClickListener {
            animateExitAndDismiss()
        }
    }

    private fun animateEntry() {
        // Animate icon
        binding.iconContainer.scaleX = 0f
        binding.iconContainer.scaleY = 0f
        binding.iconContainer.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // Rotate icon slightly for attention
        ObjectAnimator.ofFloat(binding.notificationIcon, "rotation", 0f, 360f).apply {
            duration = 800
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Animate title
        binding.notificationTitle.alpha = 0f
        binding.notificationTitle.translationX = -30f
        binding.notificationTitle.animate()
            .alpha(1f)
            .translationX(0f)
            .setStartDelay(200)
            .setDuration(500)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // Animate subtitle
        binding.notificationSubtitle.alpha = 0f
        binding.notificationSubtitle.translationX = -30f
        binding.notificationSubtitle.animate()
            .alpha(1f)
            .translationX(0f)
            .setStartDelay(300)
            .setDuration(500)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // Animate button
        binding.btnUnderstand.alpha = 0f
        binding.btnUnderstand.scaleX = 0.9f
        binding.btnUnderstand.scaleY = 0.9f
        binding.btnUnderstand.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(600)
            .setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun animateExitAndDismiss() {
        // Disable button to prevent double tap
        binding.btnUnderstand.isEnabled = false

        // Animate card out
        binding.notificationCard.animate()
            .alpha(0f)
            .scaleX(0.95f)
            .scaleY(0.95f)
            .translationY(50f)
            .setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                dismissAllowingStateLoss()
            }
            .start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        onDismissCallback?.invoke()
    }

    /**
     * Set a callback to be invoked when the bottom sheet is dismissed
     */
    fun setOnDismissCallback(callback: () -> Unit) {
        onDismissCallback = callback
    }
}

