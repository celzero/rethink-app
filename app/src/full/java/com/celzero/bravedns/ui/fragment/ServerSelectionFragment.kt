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
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.databinding.FragmentServerSelectionBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.adapter.CountryServerAdapter
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
    VpnServerAdapter.ServerSelectionListener,
    CountryServerAdapter.CitySelectionListener {

    private val b by viewBinding(FragmentServerSelectionBinding::bind)
    private lateinit var serverAdapter: CountryServerAdapter
    private lateinit var selectedAdapter: VpnServerAdapter
    private val allServers = mutableListOf<CountryConfig>()
    private val unselectedServers = mutableListOf<CountryConfig>()
    private val selectedServers = mutableListOf<CountryConfig>()
    private var selectedServerIds = mutableSetOf<String>()

    private val fragmentScope = CoroutineScope(Dispatchers.Main + Job())
    private var statusUpdateJob: Job? = null
    private var isWinRegistered = false
    private var autoServer: CountryConfig? = null

    companion object {
        private const val MAX_SELECTIONS = 5
        const val EXTRA_SELECTED_SERVERS = "selected_servers"
        private const val TAG = "ServerSelectionFragment"
        private const val AUTO_SERVER_ID = "AUTO"
        private const val AUTO_COUNTRY_CODE = "AUTO"
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupToolbar()
        setupRecyclerViews()
        setupSearchBar()
        setupHeaderUI()

        // load servers asynchronously from RpnProxyManager
        fragmentScope.launch(Dispatchers.IO) {
            // Check if WIN is registered
            isWinRegistered = VpnController.isWinRegistered()
            Logger.v(LOG_TAG_UI, "$TAG; WIN registered: $isWinRegistered")

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

    override fun onResume() {
        super.onResume()
        // Refresh servers from API to ensure we have the latest list
        // This will also detect and notify about removed selected servers
        fragmentScope.launch(Dispatchers.IO) {
            val (refreshedServers, removedServers) = RpnProxyManager.refreshWinServers()

            if (removedServers.isNotEmpty()) {
                Logger.w(LOG_TAG_UI, "$TAG.onResume: ${removedServers.size} selected servers were removed from the list")

                withContext(Dispatchers.Main) {
                    if (isAdded && !requireActivity().isFinishing) {
                        // Show premium notification bottom sheet
                        val removedNames = removedServers.joinToString(", ") { it.countryName }
                        Logger.i(LOG_TAG_UI, "$TAG.onResume: showing premium notification for removed servers: $removedNames")

                        try {
                            val bottomSheet = com.celzero.bravedns.ui.bottomsheet.ServerRemovalNotificationBottomSheet.newInstance(removedServers)
                            bottomSheet.setOnDismissCallback {
                                // Update the UI with refreshed server list after user acknowledges
                                if (isAdded && refreshedServers.isNotEmpty()) {
                                    initServers(refreshedServers)
                                }
                            }
                            bottomSheet.show(parentFragmentManager, "ServerRemovalNotification")
                        } catch (e: Exception) {
                            Logger.e(LOG_TAG_UI, "$TAG.onResume: error showing bottom sheet: ${e.message}", e)
                            // Fallback: update UI directly if bottom sheet fails
                            if (refreshedServers.isNotEmpty()) {
                                initServers(refreshedServers)
                            }
                        }
                    }
                }
            } else if (refreshedServers.isNotEmpty()) {
                // Even if no servers were removed, update the cache with latest data
                Logger.v(LOG_TAG_UI, "$TAG.onResume: refreshed ${refreshedServers.size} servers, no selected servers removed")
            }
        }
    }

    private fun initServers(servers: List<CountryConfig>) {
        Logger.v(LOG_TAG_UI, "$TAG.initServers: received ${servers.size} servers, WIN registered: $isWinRegistered")
        allServers.clear()
        allServers.addAll(servers)

        // Check if there are no servers available
        if (servers.isEmpty()) {
            showErrorState()
            Logger.w(LOG_TAG_UI, "$TAG.initServers: no servers available to display")
            return
        }

        // Hide error state if it was showing
        hideErrorState()

        // Create AUTO server if WIN is registered
        if (isWinRegistered) {
            autoServer = createAutoServer()
        }

        // initial split based on isActive
        selectedServers.clear()

        // If WIN is registered and no servers are selected, AUTO is active
        if (isWinRegistered && allServers.none { it.isActive }) {
            autoServer?.let {
                it.isActive = true
                selectedServers.add(it)
                selectedServerIds = mutableSetOf(AUTO_SERVER_ID)
            }
        } else {
            // Add manually selected servers
            selectedServers.addAll(allServers.filter { it.isActive })
            selectedServerIds = selectedServers.mapTo(mutableSetOf()) { it.id }
        }

        unselectedServers.clear()
        unselectedServers.addAll(allServers.filter { !it.isActive })

        Logger.v(LOG_TAG_UI, "$TAG.initServers: selected=${selectedServers.size}, unselected=${unselectedServers.size}, AUTO active=${autoServer?.isActive}")

        selectedAdapter.updateServers(selectedServers)
        serverAdapter.updateCountries(buildCountries(unselectedServers))

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

    private fun showErrorState() {
        if (!isAdded) return

        // Hide all normal UI elements
        b.rvServers.isVisible = false
        b.searchCard.isVisible = false
        b.selectedServersCard.isVisible = false
        b.emptySelectionCard.isVisible = false

        // Show error state with animation
        b.errorStateContainer.visibility = View.VISIBLE
        b.errorStateContainer.alpha = 0f
        b.errorStateContainer.translationY = 40f

        b.errorStateContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(500)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // Animate illustration
        b.errorIllustration.alpha = 0f
        b.errorIllustration.scaleX = 0.8f
        b.errorIllustration.scaleY = 0.8f
        b.errorIllustration.animate()
            .alpha(0.4f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(200)
            .setDuration(600)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()

        // Setup retry button
        b.errorRetryBtn.setOnClickListener {
            retryLoadingServers()
        }

        Logger.e(LOG_TAG_UI, "$TAG.showErrorState: No servers available, showing premium error UI")
    }

    private fun hideErrorState() {
        if (!isAdded) return

        // Animate out error state
        if (b.errorStateContainer.isVisible) {
            b.errorStateContainer.animate()
                .alpha(0f)
                .translationY(-40f)
                .setDuration(300)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    if (isAdded) {
                        b.errorStateContainer.visibility = View.GONE
                    }
                }
                .start()
        }

        // Show normal UI
        b.rvServers.isVisible = true
        b.searchCard.isVisible = true
    }

    private fun retryLoadingServers() {
        if (!isAdded) return

        Logger.v(LOG_TAG_UI, "$TAG.retryLoadingServers: user requested retry")

        // Show loading state on button
        b.errorRetryBtn.isEnabled = false
        b.errorRetryBtn.text = getString(R.string.loading)

        // Reload servers
        fragmentScope.launch(Dispatchers.IO) {
            delay(500) // Brief delay for better UX

            isWinRegistered = VpnController.isWinRegistered()
            val servers = RpnProxyManager.getWinServers()
            Logger.v(LOG_TAG_UI, "$TAG.retryLoadingServers: fetched ${servers.size} servers")

            withContext(Dispatchers.Main) {
                if (isAdded) {
                    b.errorRetryBtn.isEnabled = true
                    b.errorRetryBtn.text = getString(R.string.server_selection_retry)
                    initServers(servers)
                }
            }
        }
    }

    private fun createAutoServer(): CountryConfig {
        return CountryConfig(
            id = AUTO_SERVER_ID,
            cc = AUTO_COUNTRY_CODE,
            name = "AUTO",
            address = "",
            city = "Automatic",
            key = "auto",
            load = 0,
            link = 0,
            count = 1,
            isActive = true
        )
    }

    private fun setupToolbar() {
        val activity = requireActivity() as AppCompatActivity
        activity.setSupportActionBar(b.toolbar)

        activity.supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(false)
        }

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
            // Check if it's AUTO server
            if (currentServer.id == AUTO_SERVER_ID) {
                updateCurrentLocation(
                    countryFlag = "🌐",
                    countryName = "AUTO",
                    locationName = "Automatic server selection"
                )
            } else {
                updateCurrentLocation(
                    countryFlag = currentServer.flagEmoji,
                    countryName = currentServer.countryName,
                    locationName = currentServer.serverLocation
                )
            }
        } else {
            // Default fallback when no server is selected
            if (isWinRegistered) {
                // If WIN is registered, show AUTO as active
                updateCurrentLocation(
                    countryFlag = "🌐",
                    countryName = "AUTO",
                    locationName = "Automatic server selection"
                )
            } else {
                updateCurrentLocation(
                    countryFlag = "🌐",
                    countryName = "Not Connected",
                    locationName = "Select a server to connect"
                )
            }
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
        // main list grouped by country, expanding into city rows
        serverAdapter = CountryServerAdapter(buildCountries(unselectedServers), this)
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

    private fun updateSelectedSectionVisibility() {
        if (!isAdded) return
        val hasSelection = selectedServers.isNotEmpty()
        b.selectedServersCard.isVisible = hasSelection
        b.rvSelectedServers.isVisible = hasSelection
        b.emptySelectionCard.isVisible = !hasSelection
    }

    private fun updateSelectionCount() {
        if (!isAdded) return
        val total = selectedServers.size
        b.tvSelectedCount.text =
            if (total == 0) getString(R.string.server_selection_none_selected)
            else getString(
                R.string.server_selection_selected_count,
                resources.getQuantityString(R.plurals.server_selection_count, total, total),
                resources.getQuantityString(R.plurals.server_count, MAX_SELECTIONS, MAX_SELECTIONS)
            )
    }

    private fun buildCountries(servers: List<CountryConfig>): List<CountryServerAdapter.CountryItem> {
        if (servers.isEmpty()) return emptyList()

        val grouped = servers.groupBy { it.cc }
        return grouped.map { (cc, serverList) ->
            val sample = serverList.first()
            CountryServerAdapter.CountryItem(
                countryCode = cc,
                countryName = sample.countryName,
                flagEmoji = sample.flagEmoji,
                cities = serverList.map { CountryServerAdapter.CityItem(it) }
            )
        }.sortedBy { it.countryName.lowercase() }
    }

    private fun filterServers(query: String) {
        val searchText = query.trim().lowercase()
        Logger.v(LOG_TAG_UI, "$TAG.filterServers: query='$searchText'")

        if (searchText.isEmpty()) {
            serverAdapter.updateCountries(buildCountries(unselectedServers))
            animateSearchClearButton(false)
            return
        }

        val filtered = unselectedServers.filter { server ->
            server.countryName.lowercase().contains(searchText) ||
                server.serverLocation.lowercase().contains(searchText) ||
                server.cc.lowercase().contains(searchText)
        }
        serverAdapter.updateCountries(buildCountries(filtered))
        animateSearchClearButton(true)
    }

    override fun onServerSelected(server: CountryConfig, isSelected: Boolean) {
        Logger.v(LOG_TAG_UI, "$TAG.onServerSelected: server=${server.countryName}, city=${server.serverLocation}, isSelected=$isSelected")
        if (isSelected) {
            if (selectedServers.size >= MAX_SELECTIONS) {
                // Fallback simple message since server_selection_limit_reached does not exist
                Toast.makeText(
                    requireContext(),
                    "You can select up to $MAX_SELECTIONS servers.",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            if (selectedServerIds.contains(server.id)) {
                Toast.makeText(
                    requireContext(),
                    "Already selected ${server.countryName}",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            io {
                val key = server.key
                Logger.v(LOG_TAG_UI, "$TAG add rpn${server.countryName}, key=$key")
                val res = RpnProxyManager.enableWinServer(key)
                uiCtx {
                    if (!res.first) {
                        Toast.makeText(
                            requireContext(),
                            "Failed to add ${server.countryName}: ${res.second}",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@uiCtx
                    }
                    server.isActive = true
                    selectedServers.add(server)
                    selectedServerIds.add(server.id)
                    unselectedServers.removeAll { it.id == server.id }
                    selectedAdapter.updateServers(selectedServers)
                    serverAdapter.updateCountries(buildCountries(unselectedServers))

                    updateSelectedSectionVisibility()
                    updateSelectionCount()
                }
            }
        } else {
            server.isActive = false
            selectedServers.removeAll { it.id == server.id }
            selectedServerIds.remove(server.id)
            if (unselectedServers.any { it.id == server.id }) {
                Toast.makeText(
                    requireContext(),
                    "${server.countryName} is already unselected",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            io {
                val key = server.key
                Logger.v(LOG_TAG_UI, "$TAG.onServerSelected: removing WireGuard proxy for server=${server.countryName}, key=$key")
                val res = RpnProxyManager.disableWinServer(key)
                uiCtx {
                    if (!res.first) {
                        Toast.makeText(
                            requireContext(),
                            "Failed to remove ${server.countryName}: ${res.second}",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@uiCtx
                    }
                    unselectedServers.add(server)
                    selectedAdapter.updateServers(selectedServers)
                    serverAdapter.updateCountries(buildCountries(unselectedServers))

                    updateSelectedSectionVisibility()
                    updateSelectionCount()
                }
            }
        }
    }

    // delegate from country/city adapter to existing selection logic
    override fun onCitySelected(server: CountryConfig, isSelected: Boolean) {
        onServerSelected(server, isSelected)
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
