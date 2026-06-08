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
import android.animation.ValueAnimator
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.core.graphics.withRotation
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomsheetServerSettingsBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Themes.Companion.getBottomsheetCurrentTheme
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.viewmodel.ServerSelectionViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import kotlin.time.Duration.Companion.milliseconds

/**
 * bottom sheet combining DNS filter settings and new Configuration Handling section.
 */
class ServerSettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomsheetServerSettingsBinding? = null
    private val binding
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()

    private val serverSelectionViewModel: ServerSelectionViewModel by activityViewModel()

    /** True while the proxy is stopped; some controls are additionally locked. */
    private var isProxyStopped: Boolean = false
    /** Looping spin animator attached to the refresh button icon while a refresh is in progress. */
    private var refreshAnimator: ValueAnimator? = null
    /** Original text of [binding.btnResetRpn] captured in [onViewCreated]; restored when reset finishes. */
    private var originalResetBtnText: CharSequence = ""
    private var refreshAnimStartTime: Long = 0L
    private var minRefreshAnimJob: Job? = null

    companion object {
        private const val TAG = "ServerSettingsBS"
        private const val ARG_PROXY_STOPPED = "proxy_stopped"
        private const val MIN_REFRESH_ANIM_MS = 1500L

        /**
         * Available port values shown in the port-selection dialog.
         * Index 0 → random (stored as 0); other indices are literal port numbers.
         * 443, 80, 53, 123, 1194, 65142 are the most common ports which was seen from win-api
         */
        private val PORT_VALUES = intArrayOf(0, 80, 443, 53, 123, 1194, 65142)

        fun newInstance(isProxyStopped: Boolean): ServerSettingsBottomSheet {
            return ServerSettingsBottomSheet().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_PROXY_STOPPED, isProxyStopped)
                }
            }
        }
    }

    /**
     * The receiving fragment is responsible for dispatching tunnel work onto a
     * background dispatcher so the operation survives bottom-sheet dismissal.
     */
    interface OnSettingsChangedListener {
        /**
         * Fired immediately each time the user changes the DNS filter selection.
         * [tunTypes] is a comma-separated string of [RpnProxyManager.DnsMode.tunType] values
         * representing all currently active filters (e.g. `"privacy,family"`).
         */
        fun onDnsModeChanged(tunTypes: String)
        /**
         * Fired once when the sheet is dismissed (Done tap or swipe-away), but
         * **only** if at least one of the four configuration values changed since
         * the sheet was opened. The caller reads the final values from
         * [PersistentState] directly.
         */
        fun onConfigChanged()
        fun onReset()

        /**
         * Fired when the user confirms a new set of excluded country codes via the
         * Exclude Countries bottom sheet. [excludedCcCsv] is a comma-separated
         * string of country codes (e.g. "US,DE,FR"), or an empty string when the
         * exclusion list is cleared.
         *
         * The default implementation is intentionally empty; callers that do not
         * need to react to this event are not required to override it.
         */
        fun onAutoExcludeCountriesChanged(excludedCcCsv: String)
    }

    private var listener: OnSettingsChangedListener? = null

    /** Attach a [OnSettingsChangedListener]; call before [show]. */
    fun setOnSettingsChangedListener(l: OnSettingsChangedListener) {
        listener = l
    }

    /**
     * Tracks the last comma-separated tunType string emitted to the listener so we can
     * guard against spurious re-emissions when checkboxes are programmatically initialised.
     */
    private var lastEmittedTunTypes: String = RpnProxyManager.DnsMode.PRIVACY.tunType

    // Used by hasConfigChanged() to decide whether to fire onConfigChanged().
    private var snapshotConfigManual: Boolean = false
    private var snapshotAlwaysChangeIdentity: Boolean = false
    private var snapshotPort: Int = 0
    private var snapshotPermanentConfig: Boolean = false

    /** Returns true if any of the four config values differ from their opening snapshot. */
    private fun hasConfigChanged(): Boolean =
        persistentState.rpnConfigHandlingManual != snapshotConfigManual ||
        persistentState.rpnAlwaysChangeIdentity != snapshotAlwaysChangeIdentity ||
        persistentState.rpnPort != snapshotPort ||
        persistentState.rpnUsePermanentConfig != snapshotPermanentConfig

    private fun isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES

    override fun getTheme(): Int =
        getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.BottomSheetDialogTheme)
        isCancelable = true
        isProxyStopped = arguments?.getBoolean(ARG_PROXY_STOPPED, false) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomsheetServerSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Keep nav bar transparent / dark on Q+
        dialog?.window?.let { window ->
            if (isAtleastQ()) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.isAppearanceLightNavigationBars = false
                window.isNavigationBarContrastEnforced = false
            }
        }

        // Snapshot config values before any UI interaction so hasConfigChanged() is accurate.
        snapshotConfigManual = persistentState.rpnConfigHandlingManual
        snapshotAlwaysChangeIdentity  = persistentState.rpnAlwaysChangeIdentity
        snapshotPort = persistentState.rpnPort
        snapshotPermanentConfig = persistentState.rpnUsePermanentConfig

        setupDnsSection()
        setupConfigHandlingSection()
        setupExcludeCountriesRow()

        binding.btnDone.setOnClickListener { dismiss() }
        binding.btnResetRpn.setOnClickListener {
            if (!VpnController.hasTunnel()) {
                Logger.w(LOG_TAG_UI, "$TAG: reset tapped but no VPN tunnel, showing hint")
                if (isAdded) {
                    Utilities.showToastUiCentered(
                        requireContext(),
                        getString(R.string.ssv_toast_start_rethink),
                        Toast.LENGTH_SHORT
                    )
                }
                return@setOnClickListener
            }
            showResetConfirmationDialog()
        }
        binding.refreshBtn.setOnClickListener { doRefreshServers() }

        // Capture original reset button text before any state observer can change it.
        originalResetBtnText = binding.btnResetRpn.text

        observeRefreshState()
        observeResetState()

        Logger.i(LOG_TAG_UI, "$TAG: view created, proxyStopped=$isProxyStopped")
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        if (hasConfigChanged()) {
            Logger.i(LOG_TAG_UI, "$TAG: config changed on dismiss, notifying listener")
            listener?.onConfigChanged()
        }
        minRefreshAnimJob?.cancel()
        minRefreshAnimJob = null
        refreshAnimator?.cancel()
        refreshAnimator = null
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        listener = null // prevent fragment leak via callback reference
        _binding = null
    }

    /** Starts a continuous (infinite) clockwise spin on the refresh button icon. */
    private fun startRefreshAnimation() {
        refreshAnimator?.cancel()

        val raw = binding.refreshBtn.icon
        val spinning: RotatingDrawable = raw as? RotatingDrawable ?: RotatingDrawable(raw ?: return).also { binding.refreshBtn.icon = it }
        spinning.rotation = 0f
        refreshAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 700L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { spinning.rotation = it.animatedValue as Float }
            start()
        }
    }

    /**
     * Stops the spin and eases the icon back to 0° so it doesn't snap
     * abruptly when the IO operation completes.
     */
    private fun stopRefreshAnimation() {
        refreshAnimator?.cancel()
        refreshAnimator = null
        val icon = binding.refreshBtn.icon as? RotatingDrawable ?: return
        ValueAnimator.ofFloat(icon.rotation, 0f).apply {
            duration = 200L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { icon.rotation = it.animatedValue as Float }
            start()
        }
    }

    /**
     * Observes [ServerSelectionViewModel.refreshState] to manage the refresh button state.
     */
    private fun observeRefreshState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                serverSelectionViewModel.refreshState.collect { state ->
                    when (state) {
                        is ServerSelectionViewModel.RefreshState.InProgress -> {
                            binding.refreshBtn.isClickable = false
                            if (refreshAnimator == null) {
                                startRefreshAnimation()
                                refreshAnimStartTime = System.currentTimeMillis()
                            }
                            minRefreshAnimJob?.cancel()
                            minRefreshAnimJob = viewLifecycleOwner.lifecycleScope.launch {
                                val remaining = MIN_REFRESH_ANIM_MS - (System.currentTimeMillis() - refreshAnimStartTime)
                                if (remaining > 0) delay(remaining.milliseconds)
                                if (isAdded) stopRefreshAnimation()
                            }
                        }
                        else -> {
                            minRefreshAnimJob?.cancel()
                            minRefreshAnimJob = viewLifecycleOwner.lifecycleScope.launch {
                                val elapsed = System.currentTimeMillis() - refreshAnimStartTime
                                if (elapsed < MIN_REFRESH_ANIM_MS) {
                                    delay((MIN_REFRESH_ANIM_MS - elapsed).milliseconds)
                                }
                                if (isAdded) {
                                    binding.refreshBtn.isClickable = !isProxyStopped
                                    stopRefreshAnimation()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Observes [ServerSelectionViewModel.resetState] to keep the reset and refresh buttons
     * in sync with any ongoing reset — including when the user opens this sheet while a
     * reset triggered from a previous sheet visit is still running in the background.
     *
     * While [ServerSelectionViewModel.ResetState.InProgress]:
     * - The reset button is disabled and its label changes to "Restoring…" so the user
     *   understands what is happening without needing the progress dialog to be open.
     * - The refresh button is also blocked (a refresh during a reset is unsafe).
     *
     * When the reset reaches a terminal state the buttons are restored to their normal state.
     */
    private fun observeResetState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                serverSelectionViewModel.resetState.collect { state ->
                    val resetInProgress = state is ServerSelectionViewModel.ResetState.InProgress
                    if (resetInProgress) {
                        binding.btnResetRpn.isEnabled   = false
                        binding.btnResetRpn.alpha        = 0.55f
                        binding.btnResetRpn.text         = getString(R.string.rpn_restore_in_progress_btn)
                        // Block refresh during reset — they both touch server registration
                        binding.refreshBtn.isClickable  = false
                        stopRefreshAnimation()
                    } else {
                        binding.btnResetRpn.isEnabled   = !isProxyStopped
                        binding.btnResetRpn.alpha        = if (isProxyStopped) 0.55f else 1f
                        binding.btnResetRpn.text         = originalResetBtnText
                        // Restore refresh only if a refresh itself isn't also running
                        val refreshRunning = serverSelectionViewModel.refreshState.value is ServerSelectionViewModel.RefreshState.InProgress
                        binding.refreshBtn.isClickable  = !isProxyStopped && !refreshRunning
                    }
                }
            }
        }
    }

    private fun doRefreshServers() {
        if (isProxyStopped) return
        if (!VpnController.hasTunnel()) {
            Logger.w(LOG_TAG_UI, "$TAG.doRefreshServers: no VPN tunnel, showing hint")
            if (isAdded) {
                Utilities.showToastUiCentered(
                    requireContext(),
                    getString(R.string.ssv_toast_start_rethink),
                    Toast.LENGTH_SHORT
                )
            }
            return
        }
        if (refreshAnimator == null) {
            refreshAnimStartTime = System.currentTimeMillis()
            binding.refreshBtn.isClickable = false
            startRefreshAnimation()
        }
        serverSelectionViewModel.refresh()
    }

    /**
     * Sets up the DNS filter section using three independent [AppCompatCheckBox] rows:
     * Privacy, Family, and Security. Default is no longer a selectable option.
     *
     * When all three are unchecked the description shows "No filters" and the backend uses
     * the DEFAULT DNS URL (no explicit filter). Selecting any combination enables those
     * server-side blocklists.
     *
     * State is persisted in [PersistentState.rpnDnsTunTypes] as a CSV of active
     * [RpnProxyManager.DnsMode.tunType] values, or an empty string for "no filters".
     *
     * [OnSettingsChangedListener.onDnsModeChanged] is fired on every real change.
     */
    private fun setupDnsSection() {
        // restore initial selection (DEFAULT is filtered out and migrated to Privacy)
        val activeModes = getActiveModesFromState()
        lastEmittedTunTypes = if (activeModes.isEmpty()) ""
                              else RpnProxyManager.DnsMode.tunTypesFromSet(activeModes)

        // Initialise checkboxes without triggering listeners (listeners are registered below).
        setCheckboxesQuietly(activeModes)
        // Show the initial description immediately.
        updateDnsFilterDesc(activeModes)

        val splitEnabled = persistentState.splitDns
        binding.splitDnsBanner.visibility = if (splitEnabled) View.GONE else View.VISIBLE
        setDnsCheckboxesEnabled(splitEnabled && !isProxyStopped)

        binding.splitDnsEnableBtn.setOnClickListener {
            persistentState.splitDns = true
            binding.splitDnsBanner.animate()
                .alpha(0f)
                .setDuration(200)
                .withEndAction {
                    if (isAdded) {
                        binding.splitDnsBanner.visibility = View.GONE
                        binding.splitDnsBanner.alpha = 1f
                        setDnsCheckboxesEnabled(!isProxyStopped)
                    }
                }
                .start()
        }

        val rows = listOf(
            binding.dnsRowPrivacy  to binding.cbDnsPrivacy,
            binding.dnsRowFamily   to binding.cbDnsFamily,
            binding.dnsRowSecurity to binding.cbDnsSecurity,
        )

        rows.forEach { (row, checkbox) ->
            row.setOnClickListener {
                if (!persistentState.splitDns || isProxyStopped) return@setOnClickListener
                checkbox.isChecked = !checkbox.isChecked
            }
        }

        val changeListener = { _: android.widget.CompoundButton, _: Boolean ->
            if (persistentState.splitDns && !isProxyStopped) {
                onDnsCheckboxChanged()
            }
        }
        binding.cbDnsPrivacy .setOnCheckedChangeListener(changeListener)
        binding.cbDnsFamily  .setOnCheckedChangeListener(changeListener)
        binding.cbDnsSecurity.setOnCheckedChangeListener(changeListener)
    }

    /**
     * Called whenever any DNS checkbox state changes.
     * Derives the new active set (Privacy / Family / Security only), updates the
     * description text, persists [PersistentState.rpnDnsTunTypes] (empty string for
     * "no filters"), and fires [OnSettingsChangedListener.onDnsModeChanged] only on
     * a real change.
     */
    private fun onDnsCheckboxChanged() {
        val selected = buildSelectedModes()

        // Always refresh the description regardless of whether the tunTypes string changed.
        updateDnsFilterDesc(selected)

        // Empty selection = "no filters"; store blank so getActiveModesFromState() detects it.
        val newTunTypes = if (selected.isEmpty()) ""
                          else RpnProxyManager.DnsMode.tunTypesFromSet(selected)

        // Guard against spurious re-emissions during programmatic initialization.
        if (newTunTypes == lastEmittedTunTypes) return
        lastEmittedTunTypes = newTunTypes

        // csv tunTypes for the filter
        persistentState.rpnDnsTunTypes = newTunTypes

        listener?.onDnsModeChanged(newTunTypes)
        Logger.i(LOG_TAG_UI, "$TAG: DNS filter → ${newTunTypes.ifEmpty { "(no filters)" }}")
    }

    /**
     * Reads the current checkbox states and returns the corresponding [RpnProxyManager.DnsMode]
     * set. Only Privacy / Family / Security are selectable; DEFAULT is never included.
     */
    private fun buildSelectedModes(): Set<RpnProxyManager.DnsMode> {
        val result = mutableSetOf<RpnProxyManager.DnsMode>()
        if (binding.cbDnsPrivacy .isChecked) result += RpnProxyManager.DnsMode.PRIVACY
        if (binding.cbDnsFamily  .isChecked) result += RpnProxyManager.DnsMode.PARENTAL
        if (binding.cbDnsSecurity.isChecked) result += RpnProxyManager.DnsMode.SECURITY
        return result
    }

    /**
     * Sets all three checkboxes (Privacy / Family / Security) to match [modes]
     * **without** triggering their change-listeners (used during initialisation to
     * avoid spurious emissions).
     */
    private fun setCheckboxesQuietly(modes: Set<RpnProxyManager.DnsMode>) {
        binding.cbDnsPrivacy .setOnCheckedChangeListener(null)
        binding.cbDnsFamily  .setOnCheckedChangeListener(null)
        binding.cbDnsSecurity.setOnCheckedChangeListener(null)

        binding.cbDnsPrivacy .isChecked = RpnProxyManager.DnsMode.PRIVACY  in modes
        binding.cbDnsFamily  .isChecked = RpnProxyManager.DnsMode.PARENTAL in modes
        binding.cbDnsSecurity.isChecked = RpnProxyManager.DnsMode.SECURITY in modes
        // Listeners are wired in setupDnsSection() after this call.
    }

    /**
     * Resolves the active [RpnProxyManager.DnsMode] set from [PersistentState].
     *
     * - Blank stored value → empty set (user explicitly chose "no filters").
     * - (Privacy) which is the default.
     */
    private fun getActiveModesFromState(): Set<RpnProxyManager.DnsMode> {
        val stored = persistentState.rpnDnsTunTypes
        // no filter
        if (stored.isBlank()) return emptySet()
        val modes = RpnProxyManager.DnsMode.setFromCsv(stored)
        val nonDefault = modes.filter { it != RpnProxyManager.DnsMode.DEFAULT }.toSet()
        return nonDefault.ifEmpty { setOf(RpnProxyManager.DnsMode.PRIVACY) }
    }

    /**
     * Enables or disables all three DNS checkbox rows (Privacy / Family / Security).
     * The container alpha provides a clear disabled affordance without hiding controls.
     */
    private fun setDnsCheckboxesEnabled(enabled: Boolean) {
        binding.dnsCheckboxContainer.alpha = if (enabled) 1f else 0.38f
        listOf(
            binding.dnsRowPrivacy,
            binding.dnsRowFamily,  binding.dnsRowSecurity,
        ).forEach {
            it.isClickable = enabled
            it.isFocusable  = enabled
        }
        listOf(
            binding.cbDnsPrivacy,
            binding.cbDnsFamily,  binding.cbDnsSecurity,
        ).forEach { it.isEnabled = enabled }
    }

    /**
     * Updates the DNS filter description TextView.
     * Shows "No filters" when [modes] is empty; otherwise lists the active filter names.
     */
    private fun updateDnsFilterDesc(modes: Set<RpnProxyManager.DnsMode>) {
        if (!isAdded) return
        val text = if (modes.isEmpty()) {
            getString(R.string.server_settings_dns_no_filters)
        } else {
            val names = modes
                .filter { it != RpnProxyManager.DnsMode.DEFAULT }
                .sortedBy { it.id }
                .joinToString(", ") { mode ->
                    when (mode) {
                        RpnProxyManager.DnsMode.PRIVACY  -> getString(R.string.rbl_privacy)
                        RpnProxyManager.DnsMode.PARENTAL -> getString(R.string.server_settings_dns_family)
                        RpnProxyManager.DnsMode.SECURITY -> getString(R.string.rbl_security)
                        else -> ""
                    }
                }
            getString(R.string.server_settings_dns_desc_includes, names)
        }
        binding.tvDnsFilterDesc.text = text
    }

    private fun setupConfigHandlingSection() {
        val isManual = persistentState.rpnConfigHandlingManual

        // Initialize toggle without firing the listener
        val initialChecked = if (isManual) R.id.btn_config_manual else R.id.btn_config_auto
        binding.configModeToggle.check(initialChecked)
        applyManualModeUi(isManual, animate = false)

        // Initialize child toggle states from persisted values
        binding.identitySwitch.isChecked = persistentState.rpnAlwaysChangeIdentity
        updatePortValueLabel(persistentState.rpnPort)
        binding.permanentConfigSwitch.isChecked = persistentState.rpnUsePermanentConfig

        // Set initial toggle text colors
        updateToggleTextColors(isManual)

        // AUTO / MANUAL toggle
        binding.configModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val manual = checkedId == R.id.btn_config_manual
            persistentState.rpnConfigHandlingManual = manual
            applyManualModeUi(manual, animate = true)
            updateToggleTextColors(manual)
            Logger.i(LOG_TAG_UI, "$TAG: config mode → ${if (manual) "MANUAL" else "AUTO"}")
        }

        // Always Change Identity
        binding.identitySwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!persistentState.rpnConfigHandlingManual) return@setOnCheckedChangeListener
            persistentState.rpnAlwaysChangeIdentity = isChecked
            Logger.i(LOG_TAG_UI, "$TAG: alwaysChangeIdentity → $isChecked")
        }

        // Port row (opens selection dialog)
        binding.portRow.setOnClickListener {
            if (!persistentState.rpnConfigHandlingManual) return@setOnClickListener
            showPortSelectionDialog()
        }

        // Permanent Configuration
        binding.permanentConfigSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (!persistentState.rpnConfigHandlingManual) return@setOnCheckedChangeListener
            persistentState.rpnUsePermanentConfig = isChecked
            Logger.i(LOG_TAG_UI, "$TAG: permanentConfig → $isChecked")
        }
    }

    /**
     * Applies text colors to the AUTO/MANUAL toggle buttons.
     * The selected button uses [R.attr.secondaryTextColor]; the unselected one
     * uses [R.attr.primaryTextColor].
     */
    private fun updateToggleTextColors(isManual: Boolean) {
        val selectedColor = UIUtils.fetchColor(requireContext(), R.attr.secondaryTextColor)
        val unselectedColor = UIUtils.fetchColor(requireContext(), R.attr.primaryTextColor)
        binding.btnConfigManual.setTextColor(if (isManual) selectedColor else unselectedColor)
        binding.btnConfigAuto.setTextColor(if (isManual) unselectedColor else selectedColor)
    }

    /**
     * Enables or disables the three manual-only settings rows.
     *
     * The [animate] flag controls whether the transition is instant or eased.
     */
    private fun applyManualModeUi(isManual: Boolean, animate: Boolean) {
        // Update hint text
        binding.tvConfigModeHint.text = getString(
            if (isManual) R.string.server_settings_config_manual_hint
            else R.string.server_settings_config_auto_hint
        )

        val targetAlpha = if (isManual) 1f else 0.35f
        val duration = if (animate) 220L else 0L

        // Identity row
        if (animate) {
            binding.identityRow.animate()
                .alpha(targetAlpha)
                .setDuration(duration)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else {
            binding.identityRow.alpha = targetAlpha
        }
        binding.identitySwitch.isEnabled = isManual

        // Port row
        if (animate) {
            binding.portRow.animate()
                .alpha(targetAlpha)
                .setDuration(duration)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else {
            binding.portRow.alpha = targetAlpha
        }
        binding.portRow.isClickable = isManual
        binding.portRow.isFocusable = isManual

        // Permanent config row
        if (animate) {
            binding.permanentConfigRow.animate()
                .alpha(targetAlpha)
                .setDuration(duration)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else {
            binding.permanentConfigRow.alpha = targetAlpha
        }
        binding.permanentConfigSwitch.isEnabled = isManual
    }

    /**
     * Sets up the "Excluded Countries for AUTO" row.
     *
     * - Reads [PersistentState.rpnAutoExcludedCcs] to populate the subtitle with the current
     *   exclusion count.
     * - On tap, opens [AutoExcludeCountriesBottomSheet] via the parent fragment manager so it
     *   appears as a second sheet layered on top of this one.
     * - When the country sheet calls [AutoExcludeCountriesBottomSheet.OnExcludeCountriesChangedListener.onExcludeCountriesChanged],
     *   updates the subtitle and propagates to [OnSettingsChangedListener.onAutoExcludeCountriesChanged].
     */
    private fun setupExcludeCountriesRow() {
        if (!isAdded) return

        updateExcludeCcSubtitle()

        binding.excludeCountriesRow.setOnClickListener {
            if (parentFragmentManager.findFragmentByTag(AutoExcludeCountriesBottomSheet.TAG) != null) {
                return@setOnClickListener
            }

            // Subtle scale-pop to indicate navigation
            binding.excludeCountriesRow.animate()
                .scaleX(0.97f).scaleY(0.97f)
                .setDuration(80)
                .withEndAction {
                    if (isAdded) {
                        binding.excludeCountriesRow.animate()
                            .scaleX(1f).scaleY(1f)
                            .setDuration(100)
                            .setInterpolator(AccelerateDecelerateInterpolator())
                            .start()
                    }
                }
                .start()

            val sheet = AutoExcludeCountriesBottomSheet.newInstance()
            sheet.setOnExcludeCountriesChangedListener(object :
                AutoExcludeCountriesBottomSheet.OnExcludeCountriesChangedListener {
                override fun onExcludeCountriesChanged(excludedCcCsv: String) {
                    // Update the subtitle in the settings row
                    if (isAdded) updateExcludeCcSubtitle()
                    // Propagate to the parent listener
                    listener?.onAutoExcludeCountriesChanged(excludedCcCsv)
                    Logger.i(LOG_TAG_UI, "$TAG.setupExcludeCountriesRow: excluded CCs updated='${excludedCcCsv.ifEmpty { "(none)" }}'")
                }
            })
            sheet.show(parentFragmentManager, AutoExcludeCountriesBottomSheet.TAG)
        }
    }

    /**
     * Updates the subtitle of the "Excluded Countries" row to reflect the current
     * [PersistentState.rpnAutoExcludedCcs] value.
     */
    private fun updateExcludeCcSubtitle() {
        if (!isAdded) return
        val stored = persistentState.rpnAutoExcludedCcs
        val count = stored.split(",")
            .map { it.trim() }
            .count { it.isNotEmpty() }
        binding.tvExcludeCcDesc.text = if (count == 0) {
            getString(R.string.auto_exclude_cc_none)
        } else {
            getString(R.string.auto_exclude_cc_count, count)
        }
    }

    /**
     * Shows a [MaterialAlertDialogBuilder] single-choice dialog for selecting
     * the connection port. The current selection is pre-checked.
     */
    private fun showPortSelectionDialog() {
        if (!isAdded) return

        // lbl_random string resource as updatePortValueLabel()
        // replace 0 to "RANDOM" in the dialog list
        val randomLabel = getString(R.string.lbl_random).trim('(', ')').uppercase()
        val portLabels  = arrayOf(randomLabel, "80", "443", "53", "123", "1194", "65142")

        val currentPort = persistentState.rpnPort
        val selectedIndex = PORT_VALUES.indexOfFirst { it == currentPort }.let {
            if (it < 0) 0 else it  // fall back to random if stored value is unknown
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.server_settings_port_dialog_title))
            .setSingleChoiceItems(portLabels, selectedIndex) { dialog, which ->
                val newPort = PORT_VALUES[which]
                persistentState.rpnPort = newPort
                updatePortValueLabel(newPort)
                Logger.i(LOG_TAG_UI, "$TAG: port selected → $newPort")
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.lbl_cancel), null)
            .show()
    }

    /** Updates the port display label in the port row. */
    private fun updatePortValueLabel(port: Int) {
        binding.tvPortValue.text = if (port == 0) {
            // Use lbl_random ("(random)"), strip the parentheses, and display in caps → "RANDOM"
            getString(R.string.lbl_random).trim('(', ')').uppercase()
        } else {
            port.toString()
        }
    }

    /**
     * Shows a confirmation dialog before executing the RPN reset.
     */
    private fun showResetConfirmationDialog() {
        if (!isAdded) return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.rpn_restore_confirm_title))
            .setMessage(getString(R.string.rpn_restore_confirm_message))
            .setPositiveButton(getString(R.string.brbs_restore_dialog_positive)) { dialog, _ ->
                dialog.dismiss()
                dismiss() // dismiss the bottom sheet first
                listener?.onReset() // then trigger reset in the parent fragment
            }
            .setNegativeButton(getString(R.string.lbl_cancel), null)
            .show()
    }


    /**
     * A [DrawableWrapper] that applies a canvas rotation around its own center
     * during [draw], leaving the host view's background and tint untouched.
     */
    private class RotatingDrawable(drawable: Drawable) : DrawableWrapper(drawable.mutate()) {
        var rotation: Float = 0f
            set(value) {
                field = value
                invalidateSelf()
            }

        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.withRotation(rotation, b.exactCenterX(), b.exactCenterY()) {
                super.draw(this)
            }
        }
    }
}
