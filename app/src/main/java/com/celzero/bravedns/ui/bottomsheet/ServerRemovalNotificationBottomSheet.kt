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

import com.celzero.bravedns.ui.compose.rpn.*
import com.celzero.bravedns.ui.compose.RethinkTheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import android.animation.ObjectAnimator
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getBottomSheetCurrentTheme
import org.koin.android.ext.android.inject

/**
 * Bottom sheet notification for informing users about removed server locations
 * Features:
 * - Elegant Material Design 3 UI
 * - Smooth animations
 * - List of removed servers with details
 * - Professional notification experience
 */
class ServerRemovalNotificationBottomSheet : BaseBottomSheetDialogFragment() {

    private lateinit var ui: RpnRemovalState
    private val persistentState by inject<PersistentState>()

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

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    override fun getTheme(): Int =
        getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.BottomSheetDialogTheme)
        isCancelable = true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        ui = RpnRemovalState(inflater.context)
        return ComposeView(inflater.context).apply {
            id = R.id.rpn_removal_compose
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { RpnRemovalContent(ui) } }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }

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
            ui.notificationTitle.text = getString(R.string.server_removal_title)
            ui.notificationSubtitle.text = getString(R.string.server_removal_subtitle)
        } else {
            ui.notificationTitle.text = getString(R.string.server_removal_title).replace("Location", "Locations")
            ui.notificationSubtitle.text = "${removedServers.size} locations are no longer available"
        }

        ui.rows.clear()
        removedServers.forEachIndexed { index, server ->
            val row = RpnControlState().apply { alpha = 0f; translationY = 20f }
            ui.rows.add(server to row)
            row.animate().alpha(1f).translationY(0f).setStartDelay(index * 80L)
                .setDuration(400).setInterpolator(AccelerateDecelerateInterpolator()).start()
        }

        // Setup action button
        ui.btnUnderstand.setOnClickListener {
            animateExitAndDismiss()
        }
    }

    private fun animateEntry() {
        // Animate icon
        ui.iconContainer.scaleX = 0f
        ui.iconContainer.scaleY = 0f
        ui.iconContainer.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(500)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // Rotate icon slightly for attention
        ObjectAnimator.ofFloat(ui.notificationIcon, "rotation", 0f, 360f).apply {
            duration = 800
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }

        // Animate title
        ui.notificationTitle.alpha = 0f
        ui.notificationTitle.translationX = -30f
        ui.notificationTitle.animate()
            .alpha(1f)
            .translationX(0f)
            .setStartDelay(200)
            .setDuration(500)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // Animate subtitle
        ui.notificationSubtitle.alpha = 0f
        ui.notificationSubtitle.translationX = -30f
        ui.notificationSubtitle.animate()
            .alpha(1f)
            .translationX(0f)
            .setStartDelay(300)
            .setDuration(500)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // Animate button
        ui.btnUnderstand.alpha = 0f
        ui.btnUnderstand.scaleX = 0.9f
        ui.btnUnderstand.scaleY = 0.9f
        ui.btnUnderstand.animate()
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
        ui.btnUnderstand.isEnabled = false

        // Animate card out
        ui.notificationCard.animate()
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

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissCallback?.invoke()
    }

    override fun onDestroyView() {
        super.onDestroyView()

    }

    /**
     * Set a callback to be invoked when the bottom sheet is dismissed
     */
    fun setOnDismissCallback(callback: () -> Unit) {
        onDismissCallback = callback
    }
}

