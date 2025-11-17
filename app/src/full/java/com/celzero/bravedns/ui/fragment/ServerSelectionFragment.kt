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
package com.celzero.bravedns.ui.fragment

import Logger
import Logger.LOG_TAG_UI
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.database.RpnWinServer
import com.celzero.bravedns.databinding.FragmentServerSelectionBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.adapter.VpnServerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Fragment for selecting VPN servers from a list
 * Features:
 * - Search/filter servers
 * - Select/deselect servers
 * - Display server information (latency, speeds, features)
 * - Material Design 3 UI with dark/light mode support
 */
class ServerSelectionFragment : Fragment(R.layout.fragment_server_selection),
    VpnServerAdapter.ServerSelectionListener {

    private val b by viewBinding(FragmentServerSelectionBinding::bind)
    private lateinit var serverAdapter: VpnServerAdapter
    private lateinit var selectedAdapter: VpnServerAdapter
    private val allServers = mutableListOf<RpnWinServer>()
    private val unselectedServers = mutableListOf<RpnWinServer>()
    private val selectedServers = mutableListOf<RpnWinServer>()
    private var selectedServerIds = mutableSetOf<String>()

    private val fragmentScope = CoroutineScope(Dispatchers.Main + Job())
    private var statusUpdateJob: Job? = null

    companion object {
        private const val MAX_SELECTIONS = 5
        const val EXTRA_SELECTED_SERVERS = "selected_servers"
        private const val TAG = "ServerSelectionFragment"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerViews()
        setupSearchBar()
        setupHeaderUI()

        // load servers asynchronously from RpnProxyManager
        fragmentScope.launch(Dispatchers.IO) {
            val servers = RpnProxyManager.getWinServers()
            Logger.v(LOG_TAG_UI, "$TAG; fetched ${servers.size} servers from RPN")
            withContext(Dispatchers.Main) {
                if (isAdded) {
                    initServers(servers)
                }
            }
        }

        animateHeaderEntry()
    }

    private fun initServers(servers: List<RpnWinServer>) {
        Logger.v(LOG_TAG_UI, "$TAG.initServers: received ${servers.size} servers")
        allServers.clear()
        allServers.addAll(servers)

        // initial split based on isSelected
        selectedServers.clear()
        selectedServers.addAll(allServers.filter { it.isSelected })
        selectedServerIds = selectedServers.mapTo(mutableSetOf()) { it.id }

        unselectedServers.clear()
        unselectedServers.addAll(allServers.filter { !it.isSelected })

        Logger.v(LOG_TAG_UI, "$TAG.initServers: selected=${selectedServers.size}, unselected=${unselectedServers.size}")

        selectedAdapter.updateServers(selectedServers)
        serverAdapter.updateServers(unselectedServers)

        updateSelectedSectionVisibility()
        updateSelectionCount()

        // Force RecyclerView to request layout after data update
        b.rvServers.post {
            b.rvServers.requestLayout()
            Logger.v(LOG_TAG_UI, "$TAG.initServers: forced rvServers layout, adapter.itemCount=${serverAdapter.itemCount}")
        }
        b.rvSelectedServers.post {
            b.rvSelectedServers.requestLayout()
            Logger.v(LOG_TAG_UI, "$TAG.initServers: forced rvSelectedServers layout, adapter.itemCount=${selectedAdapter.itemCount}")
        }

        Logger.v(LOG_TAG_UI, "$TAG.initServers: completed")
    }

    private fun setupToolbar() {
        b.toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // Show title only when AppBar is collapsed
        var isCollapsed = false
        b.appBarLayout.addOnOffsetChangedListener { appBarLayout, verticalOffset ->
            val totalScrollRange = appBarLayout.totalScrollRange
            val scrollPercentage = abs(verticalOffset).toFloat() / totalScrollRange.toFloat()

            // Show title when scrolled more than 80%
            if (scrollPercentage > 0.8f && !isCollapsed) {
                isCollapsed = true
                b.collapsingToolbar.title = getString(R.string.server_selection_title)
            } else if (scrollPercentage <= 0.8f && isCollapsed) {
                isCollapsed = false
                b.collapsingToolbar.title = ""
            }
        }

        // Initially hide the title (expanded state)
        b.collapsingToolbar.title = ""
    }

    private fun setupHeaderUI() {
        // Initial status update
        updateVpnStatus()

        // Periodically update VPN status (every 3 seconds)
        statusUpdateJob = fragmentScope.launch {
            while (true) {
                delay(3000)
                if (isAdded) {
                    updateVpnStatus()
                }
            }
        }
    }

    private fun updateVpnStatus() {
        if (!isAdded) return

        val isConnected = VpnController.state().on
        updateConnectionStatus(isConnected)

        // Get current server info from selected servers
        if (selectedServers.isNotEmpty()) {
            val currentServer = selectedServers.first()
            updateCurrentLocation(
                countryFlag = currentServer.flagEmoji,
                countryName = currentServer.countryName,
                locationName = currentServer.serverLocation
            )
        } else {
            // Default fallback when no server is selected
            updateCurrentLocation(
                countryFlag = "🌐",
                countryName = "Not Connected",
                locationName = "Select a server to connect"
            )
        }
    }

    override fun onDestroyView() {
        // Cancel all animations to prevent callbacks from accessing destroyed view binding
        try {
            b.statusIndicator.animate().cancel()
            b.statusCard.animate().cancel()
            b.searchCard.animate().cancel()
            b.searchClearBtn.animate().cancel()
        } catch (_: Exception) {
            // View binding might already be destroyed, ignore
        }

        statusUpdateJob?.cancel()
        super.onDestroyView()
    }

    private fun updateConnectionStatus(isConnected: Boolean) {
        if (!isAdded) return

        if (isConnected) {
            b.tvConnectionStatus.text = getString(R.string.vpn_status_connected)
            b.tvConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accentGood))
            b.statusIndicator.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.accentGood)

            // Pulse animation for connected indicator
            b.statusIndicator.animate()
                .scaleX(1.3f)
                .scaleY(1.3f)
                .setDuration(500)
                .withEndAction {
                    if (isAdded) {
                        b.statusIndicator.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(500)
                            .start()
                    }
                }
                .start()
        } else {
            b.tvConnectionStatus.text = getString(R.string.vpn_status_disconnected)
            b.tvConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.accentBad))
            b.statusIndicator.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.accentBad)
        }
    }

    private fun updateCurrentLocation(countryFlag: String, countryName: String, locationName: String) {
        if (!isAdded) return

        b.tvCurrentCountryFlag.text = countryFlag
        b.tvCurrentCountry.text = countryName
        b.tvCurrentLocation.text = locationName
    }

    private fun animateHeaderEntry() {
        if (!isAdded) return

        // Animate the status card with a subtle scale and fade in
        b.statusCard.alpha = 0f
        b.statusCard.scaleX = 0.9f
        b.statusCard.scaleY = 0.9f
        b.statusCard.translationZ = 2f

        b.statusCard.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(400)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun setupRecyclerViews() {
        Logger.v(LOG_TAG_UI, "$TAG.setupRecyclerViews: initializing adapters")

        b.rvServers.layoutManager = LinearLayoutManager(requireContext())
        serverAdapter = VpnServerAdapter(unselectedServers, this)
        b.rvServers.adapter = serverAdapter
        Logger.v(LOG_TAG_UI, "$TAG.setupRecyclerViews: serverAdapter created and set, itemCount=${serverAdapter.itemCount}")

        // Add smooth item animations
        b.rvServers.itemAnimator?.apply {
            changeDuration = 300
            moveDuration = 300
            addDuration = 300
            removeDuration = 300
        }

        b.rvSelectedServers.layoutManager = LinearLayoutManager(requireContext())
        selectedAdapter = VpnServerAdapter(selectedServers, this)
        b.rvSelectedServers.adapter = selectedAdapter
        Logger.v(LOG_TAG_UI, "$TAG.setupRecyclerViews: selectedAdapter created and set, itemCount=${selectedAdapter.itemCount}")

        b.rvSelectedServers.itemAnimator?.apply {
            changeDuration = 300
            moveDuration = 300
            addDuration = 300
            removeDuration = 300
        }
    }

    private fun setupSearchBar() {
        b.searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterServers(s.toString())
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        // Clear button functionality with animation
        b.searchClearBtn.setOnClickListener {
            b.searchBar.text?.clear()
            animateSearchClearButton(false)
        }

        // Smooth scroll to search when focused
        b.searchBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                b.searchCard.animate()
                    .scaleX(1.02f)
                    .scaleY(1.02f)
                    .setDuration(150)
                    .start()
            } else {
                b.searchCard.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150)
                    .start()
            }
        }
    }

    private fun animateSearchClearButton(show: Boolean) {
        if (!isAdded) return

        if (show && b.searchClearBtn.visibility != View.VISIBLE) {
            b.searchClearBtn.visibility = View.VISIBLE
            b.searchClearBtn.alpha = 0f
            b.searchClearBtn.scaleX = 0.5f
            b.searchClearBtn.scaleY = 0.5f
            b.searchClearBtn.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else if (!show && b.searchClearBtn.isVisible) {
            b.searchClearBtn.animate()
                .alpha(0f)
                .scaleX(0.5f)
                .scaleY(0.5f)
                .setDuration(200)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    if (isAdded) {
                        b.searchClearBtn.visibility = View.GONE
                    }
                }
                .start()
        }
    }

    private fun filterServers(query: String) {
        if (query.isEmpty()) {
            animateSearchClearButton(false)
            serverAdapter.updateServers(unselectedServers)
        } else {
            animateSearchClearButton(true)
            val filtered = unselectedServers.filter { server ->
                server.countryName.contains(query, ignoreCase = true) ||
                server.countryCode.contains(query, ignoreCase = true) ||
                server.serverLocation.contains(query, ignoreCase = true)
            }
            serverAdapter.updateServers(filtered)
        }
    }

    override fun onServerSelected(server: RpnWinServer, isSelected: Boolean) {
        if (!isAdded) return

        b.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)

        if (isSelected) {
            if (selectedServerIds.size >= MAX_SELECTIONS) {
                b.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.server_selection_max_reached, MAX_SELECTIONS),
                    Toast.LENGTH_SHORT
                ).show()
                // Revert
                server.isSelected = false
                refreshLists()
                return
            }
            if (!selectedServerIds.contains(server.id)) {
                selectedServerIds.add(server.id)
                server.isSelected = true
                unselectedServers.remove(server)
                selectedServers.add(server)
                animateServerSelection()
                b.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            }
        } else {
            if (selectedServerIds.contains(server.id)) {
                selectedServerIds.remove(server.id)
                server.isSelected = false
                selectedServers.remove(server)
                unselectedServers.add(server)
            }
        }
        refreshLists()
        updateVpnStatus()
    }

    private fun animateServerSelection() {
        // Subtle pulse animation on status card to indicate selection
        val scaleUp = ObjectAnimator.ofFloat(b.statusCard, "scaleX", 1f, 1.02f).apply {
            duration = 100
        }
        val scaleDown = ObjectAnimator.ofFloat(b.statusCard, "scaleX", 1.02f, 1f).apply {
            duration = 100
        }
        scaleUp.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                scaleDown.start()
            }
        })
        scaleUp.start()
    }

    private val serverComparator = Comparator<RpnWinServer> { a, b ->
        val c = a.countryName.compareTo(b.countryName, ignoreCase = true)
        if (c != 0) c else a.serverLocation.compareTo(b.serverLocation, ignoreCase = true)
    }

    private fun refreshLists() {
        selectedServers.sortWith(serverComparator)
        unselectedServers.sortWith(serverComparator)
        selectedAdapter.updateServers(selectedServers)

        val query = b.searchBar.text?.toString().orEmpty()
        if (query.isNotEmpty()) {
            val filtered = unselectedServers.filter { server ->
                server.countryName.contains(query, ignoreCase = true) ||
                server.countryCode.contains(query, ignoreCase = true) ||
                server.serverLocation.contains(query, ignoreCase = true)
            }
            serverAdapter.updateServers(filtered)
        } else {
            serverAdapter.updateServers(unselectedServers)
        }
        updateSelectionCount()
        updateSelectedSectionVisibility()
    }

    private fun updateSelectedSectionVisibility() {
        if (selectedServers.isNotEmpty()) {
            b.selectedServersCard.visibility = View.VISIBLE
            b.emptySelectionCard.visibility = View.GONE
        } else {
            b.selectedServersCard.visibility = View.GONE
            b.emptySelectionCard.visibility = View.VISIBLE
        }
    }

    private fun updateSelectionCount() {
        val count = selectedServerIds.size
        Logger.v(LOG_TAG_UI, "$TAG.updateSelectionCount: selectedCount=$count, unselectedCount=${unselectedServers.size}")

        b.tvSelectedCount.text = resources.getQuantityString(
            R.plurals.server_selection_count,
            count,
            count
        )

        b.tvServerCount.text = resources.getQuantityString(
            R.plurals.server_count,
            unselectedServers.size,
            unselectedServers.size
        )

        // empty state for main list
        if (unselectedServers.isEmpty()) {
            Logger.v(LOG_TAG_UI, "$TAG.updateSelectionCount: unselectedServers is empty, hiding rvServers")
            b.rvServers.visibility = View.GONE
            b.emptyStateLayout.visibility = View.VISIBLE
        } else {
            Logger.v(LOG_TAG_UI, "$TAG.updateSelectionCount: unselectedServers has ${unselectedServers.size} items, showing rvServers")
            b.rvServers.visibility = View.VISIBLE
            b.emptyStateLayout.visibility = View.GONE
        }
    }

}

