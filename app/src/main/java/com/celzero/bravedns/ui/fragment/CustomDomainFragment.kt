/*
 * Copyright 2021 RethinkDNS and its authors
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

import android.content.Context.INPUT_METHOD_SERVICE
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.adapter.CustomDomainAdapter
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.DialogAddCustomDomainBinding
import com.celzero.bravedns.databinding.DialogImportConfirmBinding
import com.celzero.bravedns.databinding.FragmentCustomDomainBinding
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.DomainRulesManager.isValidDomain
import com.celzero.bravedns.service.DomainRulesManager.isWildCardEntry
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.activity.CustomRulesActivity
import com.celzero.bravedns.util.Constants.Companion.INTENT_UID
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.removeLeadingAndTrailingDots
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

class CustomDomainFragment :
    Fragment(R.layout.fragment_custom_domain), SearchView.OnQueryTextListener {

    private val b by viewBinding(FragmentCustomDomainBinding::bind)
    private var layoutManager: RecyclerView.LayoutManager? = null
    private lateinit var adapter: CustomDomainAdapter

    private val viewModel by inject<CustomDomainViewModel>()
    private val eventLogger by inject<EventLogger>()

    private var uid = UID_EVERYBODY
    private var rule = CustomRulesActivity.RULES.APP_SPECIFIC_RULES

    // ActivityResultLauncher for the document picker (DEBUG import only).
    // Must be registered in onCreate — before onStart — to survive configuration changes.
    private lateinit var importFileLauncher: ActivityResultLauncher<Array<String>>

    companion object {
        fun newInstance(uid: Int, rules: CustomRulesActivity.RULES): CustomDomainFragment {
            val args = Bundle()
            args.putInt(INTENT_UID, uid)
            args.putInt(CustomRulesActivity.INTENT_RULES, rules.type)
            val fragment = CustomDomainFragment()
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Register the document picker launcher here (before onStart) so it survives
        // configuration changes. The actual UI is only shown when DEBUG == true.
        importFileLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                // uri is null when the user dismisses the picker without selecting a file
                uri ?: return@registerForActivityResult
                handleImportUri(uri)
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    override fun onResume() {
        super.onResume()
        // fix for #1939, OEM-specific bug, especially on heavily customized Android
        // some ROMs kill or freeze the keyboard/IME process to save memory or battery,
        // causing SearchView to stop receiving input events
        // this is a workaround to restart the IME process
        b.cdaSearchView.setQuery("", false)
        b.cdaSearchView.clearFocus()

        val imm = requireContext().getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.restartInput(b.cdaSearchView)
    }

    private fun initView() {
        uid = arguments?.getInt(INTENT_UID, UID_EVERYBODY) ?: UID_EVERYBODY
        rule =
            arguments?.getInt(CustomRulesActivity.INTENT_RULES)?.let {
                CustomRulesActivity.RULES.getType(it)
            } ?: CustomRulesActivity.RULES.APP_SPECIFIC_RULES

        b.cdaSearchView.setOnQueryTextListener(this)
        setupRecyclerView()
        setupClickListeners()

        b.cdaRecycler.requestFocus()
    }

    private fun setupRecyclerView() {
        layoutManager = LinearLayoutManager(requireContext())
        b.cdaRecycler.layoutManager = layoutManager
        b.cdaRecycler.setHasFixedSize(true)
        if (rule == CustomRulesActivity.RULES.APP_SPECIFIC_RULES) {
            b.cdaAddFab.visibility = View.VISIBLE
            setupAppSpecificRules(rule)
        } else {
            b.cdaAddFab.visibility = View.GONE
            setupAllRules(rule)
        }
    }

    private fun setupAppSpecificRules(rule: CustomRulesActivity.RULES) {
        observeCustomRules()
        adapter = CustomDomainAdapter(requireContext(), this, rule, eventLogger)
        b.cdaRecycler.adapter = adapter
        viewModel.setUid(uid)
        viewModel.customDomains.observe(viewLifecycleOwner) {
            adapter.submitData(viewLifecycleOwner.lifecycle, it)
        }
        io {
            val appName = FirewallManager.getAppNameByUid(uid)
            if (appName != null) {
                uiCtx { updateAppNameInSearchHint(appName) }
            }
        }
    }

    private fun updateAppNameInSearchHint(appName: String) {
        val appNameTruncated = appName.substring(0, appName.length.coerceAtMost(10))
        val hint = getString(
            R.string.two_argument_colon,
            appNameTruncated,
            getString(R.string.search_custom_domains)
        )
        b.cdaSearchView.queryHint = hint
        b.cdaSearchView.findViewById<SearchView.SearchAutoComplete>(androidx.appcompat.R.id.search_src_text).textSize =
            14f
        return
    }

    private fun setupAllRules(rule: CustomRulesActivity.RULES) {
        observeAllRules()
        adapter = CustomDomainAdapter(requireContext(), this, rule, eventLogger)
        b.cdaRecycler.adapter = adapter
        viewModel.allDomainRules.observe(viewLifecycleOwner) {
            adapter.submitData(viewLifecycleOwner.lifecycle, it)
        }
    }

    private fun setupClickListeners() {
        // see CustomIpFragment#setupClickListeners#bringToFront()
        b.cdaAddFab.bringToFront()
        b.cdaAddFab.setOnClickListener { showAddDomainDialog() }

        b.cdaSearchDeleteIcon.setOnClickListener { showDomainRulesDeleteDialog() }

        // Import FAB is only shown and wired up in DEBUG builds.
        // The FAB itself is GONE in XML; this block also stays dead-code in release builds
        // so ProGuard/R8 can strip it entirely.
        if (DEBUG) {
            b.cdaImportFab.visibility = View.VISIBLE
            b.cdaImportFab.setOnClickListener {
                // Launch the system document picker; accept plain text files only
                importFileLauncher.launch(arrayOf("text/plain"))
            }
        }
    }

    private fun observeCustomRules() {
        viewModel.domainRulesCount(uid).observe(viewLifecycleOwner) {
            if (it <= 0) {
                showNoRulesUi()
                hideRulesUi()
                return@observe
            }

            hideNoRulesUi()
            showRulesUi()
        }
    }

    private fun observeAllRules() {
        viewModel.allDomainRulesCount().observe(viewLifecycleOwner) {
            if (it <= 0) {
                showNoRulesUi()
                hideRulesUi()
                return@observe
            }

            hideNoRulesUi()
            showRulesUi()
        }
    }

    private fun hideRulesUi() {
        b.cdaShowRulesRl.visibility = View.GONE
    }

    private fun showRulesUi() {
        b.cdaShowRulesRl.visibility = View.VISIBLE
    }

    private fun hideNoRulesUi() {
        b.cdaNoRulesRl.visibility = View.GONE
    }

    private fun showNoRulesUi() {
        b.cdaNoRulesRl.visibility = View.VISIBLE
    }

    /**
     * Shows dialog to add custom domain. Provides user option to user to add DOMAIN, TLD and
     * WILDCARD. If entered option and text-input is valid, then the dns requests will be filtered
     * based on it. User can either select the entered domain to be added in whitelist or blocklist.
     */
    private fun showAddDomainDialog() {
        val dBind = DialogAddCustomDomainBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim).setView(dBind.root)
        val lp = WindowManager.LayoutParams()
        val dialog = builder.create()
        dialog.show()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        dialog.setCancelable(true)
        dialog.window?.attributes = lp

        var selectedType: DomainRulesManager.DomainType = DomainRulesManager.DomainType.DOMAIN

        dBind.dacdDomainEditText.addTextChangedListener {
            if (it?.startsWith("*") == true || it?.startsWith(".") == true) {
                dBind.dacdWildcardChip.isChecked = true
            } else {
                dBind.dacdDomainChip.isChecked = true
            }
        }

        dBind.dacdDomainChip.setOnCheckedChangeListener { _, isSelected ->
            if (isSelected) {
                selectedType = DomainRulesManager.DomainType.DOMAIN
                dBind.dacdDomainEditText.hint =
                    resources.getString(
                        R.string.cd_dialog_edittext_hint,
                        getString(R.string.lbl_domain)
                    )
                dBind.dacdTextInputLayout.hint =
                    resources.getString(
                        R.string.cd_dialog_edittext_hint,
                        getString(R.string.lbl_domain)
                    )
            }
        }

        dBind.dacdWildcardChip.setOnCheckedChangeListener { _, isSelected ->
            if (isSelected) {
                selectedType = DomainRulesManager.DomainType.WILDCARD
                dBind.dacdDomainEditText.hint =
                    resources.getString(
                        R.string.cd_dialog_edittext_hint,
                        getString(R.string.lbl_wildcard)
                    )
                dBind.dacdTextInputLayout.hint =
                    resources.getString(
                        R.string.cd_dialog_edittext_hint,
                        getString(R.string.lbl_wildcard)
                    )
            }
        }

        dBind.dacdUrlTitle.text = getString(R.string.cd_dialog_title)
        dBind.dacdDomainEditText.hint =
            resources.getString(R.string.cd_dialog_edittext_hint, getString(R.string.lbl_domain))
        dBind.dacdTextInputLayout.hint =
            resources.getString(R.string.cd_dialog_edittext_hint, getString(R.string.lbl_domain))

        dBind.dacdBlockBtn.setOnClickListener {
            handleDomain(dBind, selectedType, DomainRulesManager.Status.BLOCK)
        }

        dBind.dacdTrustBtn.setOnClickListener {
            handleDomain(dBind, selectedType, DomainRulesManager.Status.TRUST)
        }

        dBind.dacdCancelBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun handleDomain(
        dBind: DialogAddCustomDomainBinding,
        selectedType: DomainRulesManager.DomainType,
        status: DomainRulesManager.Status
    ) {
        dBind.dacdFailureText.visibility = View.GONE
        val url = dBind.dacdDomainEditText.text.toString()
        val extractedHost = extractHost(url) ?: run {
            dBind.dacdFailureText.text =
                getString(R.string.cd_dialog_error_invalid_domain)
            dBind.dacdFailureText.visibility = View.VISIBLE
            return
        }
        when (selectedType) {
            DomainRulesManager.DomainType.WILDCARD -> {
                if (!isWildCardEntry(extractedHost)) {
                    dBind.dacdFailureText.text =
                        getString(R.string.cd_dialog_error_invalid_wildcard)
                    dBind.dacdFailureText.visibility = View.VISIBLE
                    return
                }
            }
            DomainRulesManager.DomainType.DOMAIN -> {
                if (!isValidDomain(extractedHost)) {
                    dBind.dacdFailureText.text = getString(R.string.cd_dialog_error_invalid_domain)
                    dBind.dacdFailureText.visibility = View.VISIBLE
                    return
                }
            }
        }

        insertDomain(removeLeadingAndTrailingDots(extractedHost), selectedType, status)
    }

    private fun extractHost(input: String): String? {
        // Use centralized domain extraction logic from DomainRulesManager
        return DomainRulesManager.extractHost(input)
    }


    private fun insertDomain(
        domain: String,
        type: DomainRulesManager.DomainType,
        status: DomainRulesManager.Status
    ) {
        io { DomainRulesManager.addDomainRule(domain, status, type, uid = uid) }
        Utilities.showToastUiCentered(
            requireContext(),
            resources.getString(R.string.cd_toast_added),
            Toast.LENGTH_SHORT
        )
        logEvent("Added domain: $domain, Type: $type, Status: $status, UID: $uid")
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        viewModel.setFilter(query)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        viewModel.setFilter(query)
        return true
    }

    private fun showDomainRulesDeleteDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(getString(R.string.univ_delete_domain_dialog_title))
        builder.setMessage(getString(R.string.univ_delete_domain_dialog_message))
        builder.setPositiveButton(getString(R.string.univ_ip_delete_dialog_positive)) { _, _ ->

            io {
                val selectedItems = adapter.getSelectedItems()
                if (selectedItems.isNotEmpty()) {
                    uiCtx { adapter.clearSelection() }
                    DomainRulesManager.deleteRules(selectedItems)
                    logEvent("Deleted domains: $selectedItems, Rule: $rule, UID: $uid")
                } else {
                    if (rule == CustomRulesActivity.RULES.APP_SPECIFIC_RULES) {
                        DomainRulesManager.deleteRulesByUid(uid)
                        logEvent("Deleted all domains for UID: $uid")
                    } else {
                        DomainRulesManager.deleteAllRules()
                        logEvent("Deleted all custom domain rules")
                    }
                }
            }
            Utilities.showToastUiCentered(
                requireContext(),
                getString(R.string.cd_deleted_toast),
                Toast.LENGTH_SHORT
            )
        }

        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
            adapter.clearSelection()
        }

        builder.setCancelable(true)
        builder.create().show()
    }

    // -----------------------------------------------------------------------------------------
    // DEBUG-only import helpers
    // The methods below are only called when DEBUG == true. They are intentionally grouped
    // together at the bottom of the class to make the debug boundary visually clear.
    // -----------------------------------------------------------------------------------------

    /**
     * Called after the user picks a file in the document picker.
     * Parses the file on an IO coroutine, then shows the confirmation dialog on the main thread.
     */
    private fun handleImportUri(uri: Uri) {
        io {
            val parsed = RulesImportHelper.parseFile(
                requireContext(), uri, RulesImportHelper.ImportType.DOMAIN
            )
            uiCtx {
                if (parsed == null) {
                    Utilities.showToastUiCentered(
                        requireContext(),
                        getString(R.string.import_rules_error_unreadable),
                        Toast.LENGTH_SHORT
                    )
                    return@uiCtx
                }
                if (parsed.valid.isEmpty()) {
                    Utilities.showToastUiCentered(
                        requireContext(),
                        getString(R.string.import_rules_error_empty),
                        Toast.LENGTH_SHORT
                    )
                    return@uiCtx
                }
                showImportConfirmDialog(parsed)
            }
        }
    }

    /**
     * Shows the import confirmation dialog.
     * Displays file name, valid entry count, ignored count, and Block / Allow radio group.
     * Domain rules always use TRUST for "Allow" (unlike IP rules which vary by UID).
     */
    private fun showImportConfirmDialog(parsed: RulesImportHelper.ParsedFile) {
        val dBind = DialogImportConfirmBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setView(dBind.root)
            .create()

        val lp = WindowManager.LayoutParams()
        dialog.show()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.setCancelable(true)
        dialog.window?.attributes = lp

        dBind.dicFileName.text = parsed.fileName
        dBind.dicValidCount.text = parsed.valid.size.toString()
        dBind.dicIgnoredCount.text = parsed.invalidCount.toString()

        // Domain rules use the same labels as manual add: Block vs Trust
        dBind.dicAllowRadio.text = getString(R.string.ci_trust_rule)

        dBind.dicCancelBtn.setOnClickListener { dialog.dismiss() }

        dBind.dicImportBtn.setOnClickListener {
            val isBlock = dBind.dicActionGroup.checkedRadioButtonId == R.id.dic_block_radio
            val domainStatus = if (isBlock) DomainRulesManager.Status.BLOCK else DomainRulesManager.Status.TRUST
            dialog.dismiss()
            runImport(parsed.valid, domainStatus)
        }
    }

    /**
     * Runs the actual insertion on an IO coroutine, then shows the summary dialog.
     * The RecyclerView refreshes automatically via LiveData once insertion is complete.
     */
    private fun runImport(entries: List<String>, domainStatus: DomainRulesManager.Status) {
        io {
            val summary = RulesImportHelper.importRules(
                entries = entries,
                importType = RulesImportHelper.ImportType.DOMAIN,
                uid = uid,
                domainStatus = domainStatus
            )
            uiCtx { showImportSummaryDialog(summary) }
        }
    }

    /** Shows a simple summary dialog after all rules have been inserted. */
    private fun showImportSummaryDialog(summary: RulesImportHelper.ImportSummary) {
        val msg = getString(
            R.string.import_rules_summary,
            summary.imported,
            summary.duplicates,
            summary.invalid
        )
        MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setTitle(getString(R.string.import_rules_complete_title))
            .setMessage(msg)
            .setPositiveButton(getString(R.string.fapps_info_dialog_positive_btn)) { d, _ -> d.dismiss() }
            .create()
            .show()
        logEvent("Import complete: imported=${summary.imported}, duplicates=${summary.duplicates}, invalid=${summary.invalid}, uid=$uid")
    }

    private fun logEvent(details: String) {
        eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, "Custom Domain", EventSource.UI, false, details)
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}

