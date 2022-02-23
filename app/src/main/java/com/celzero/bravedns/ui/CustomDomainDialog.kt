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
package com.celzero.bravedns.ui

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.util.Patterns
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.CustomDomainAdapter
import com.celzero.bravedns.automaton.DomainRulesManager
import com.celzero.bravedns.databinding.DialogAddCustomDomainBinding
import com.celzero.bravedns.databinding.DialogCustomDomainBinding
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.removeLeadingAndTrailingDots
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import com.google.android.material.chip.Chip
import java.net.MalformedURLException
import java.util.regex.Pattern

class CustomDomainDialog(val activity: Activity, val viewModel: CustomDomainViewModel, themeId: Int): Dialog(activity, themeId), SearchView.OnQueryTextListener {

    private lateinit var b: DialogCustomDomainBinding
    private var layoutManager: RecyclerView.LayoutManager? = null

    private var filterType = DomainRulesManager.DomainStatus.NONE
    private var filterQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        b = DialogCustomDomainBinding.inflate(layoutInflater)
        setContentView(b.root)

        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                          WindowManager.LayoutParams.MATCH_PARENT)

        setupRecyclerView()
        remakeParentFilterChipsUi()
        setupClickListeners()
    }

    private fun setupRecyclerView() {
        layoutManager = LinearLayoutManager(activity)
        val adapter = CustomDomainAdapter(activity)
        b.cdfRecycler.layoutManager = layoutManager
        b.cdfRecycler.adapter = adapter

        viewModel.customDomainList.observe(activity as LifecycleOwner, androidx.lifecycle.Observer(
            adapter::submitList))

        b.cdfSearchView.setOnQueryTextListener(this)
    }

    private fun setupClickListeners() {
        b.cdfAddDomain.setOnClickListener {
            showAddDomainDialog()
        }

        b.cdfSearchFilterIcon.setOnClickListener {
            toggleChipsUi()
        }

        b.cdfSearchView.setOnClickListener {
            showChipsUi()
        }
    }

    /**
     * Shows dialog to add custom domain. Provides user option to user to add DOMAIN, TLD and
     * WILDCARD. If entered option and text-input is valid, then the dns requests will be
     * filtered based on it. User can either select the entered domain to be added in
     * whitelist or blocklist.
     */
    private fun showAddDomainDialog() {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle(activity.getString(R.string.cd_dialog_title))
        val dBind = DialogAddCustomDomainBinding.inflate(layoutInflater)
        dialog.setContentView(dBind.root)

        val types = DomainRulesManager.DomainType.getAllDomainTypes()
        var selectedType: DomainRulesManager.DomainType = DomainRulesManager.DomainType.DOMAIN

        val aa: ArrayAdapter<*> = ArrayAdapter<Any?>(activity,
                                                     android.R.layout.simple_spinner_item, types)
        aa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dBind.dacdSpinner.adapter = aa

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.attributes = lp

        dBind.dacdUrlTitle.text = activity.getString(R.string.cd_dialog_title)
        dBind.dacdDomainEditText.hint = activity.resources.getString(R.string.cd_dialog_edittext_hint,
                                                            types[0])
        dBind.dacdTextInputLayout.hint = activity.resources.getString(R.string.cd_dialog_edittext_hint,
                                                             types[0])

        dBind.dacdSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // no-op
            }

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int,
                                        id: Long) {
                selectedType = DomainRulesManager.DomainType.getType(position)
                dBind.dacdDomainEditText.hint = activity.resources.getString(
                    R.string.cd_dialog_edittext_hint, types[position])
                dBind.dacdTextInputLayout.hint = activity.resources.getString(
                    R.string.cd_dialog_edittext_hint, types[position])
            }
        }

        dBind.dacdAddBtn.setOnClickListener {
            dBind.dacdFailureText.visibility = View.GONE
            val url = dBind.dacdDomainEditText.text.toString()

            insertDomain(removeLeadingAndTrailingDots(url), selectedType)
        }

        dBind.dacdShowBtn.setOnClickListener {
            val input = dBind.dacdDomainEditText.text.toString()

            when (selectedType) {
                DomainRulesManager.DomainType.TLD -> {
                    val tld = removeLeadingAndTrailingDots(input)
                    if (!isValidTld(tld)) {
                        dBind.dacdFailureText.text = activity.resources.getString(
                            R.string.cd_dialog_error_invalid_tld)
                        dBind.dacdFailureText.visibility = View.VISIBLE
                        return@setOnClickListener
                    }

                    constructTldSamples(tld)
                }
                DomainRulesManager.DomainType.WILDCARD -> {
                    if (!isWildCardEntry(input)) {
                        dBind.dacdFailureText.text = activity.resources.getString(
                            R.string.cd_dialog_error_invalid_wildcard)
                        dBind.dacdFailureText.visibility = View.VISIBLE
                        return@setOnClickListener

                    }

                    constructWildcardSamples(input)
                }
                DomainRulesManager.DomainType.DOMAIN -> {
                    if (!isValidDomain(input)) {
                        dBind.dacdFailureText.text = activity.resources.getString(
                            R.string.cd_dialog_error_invalid_domain)
                        dBind.dacdFailureText.visibility = View.VISIBLE
                        return@setOnClickListener
                    }

                    constructDomainSamples(input)
                }
            }

            dBind.dacdSampleText.visibility = View.VISIBLE
            dBind.dacdSampleText.text = showSampleDomains(input, selectedType)
            dBind.dacdShowBtn.visibility = View.INVISIBLE
            dBind.dacdAddBtn.visibility = View.VISIBLE
        }

        dBind.dacdCancelBtn.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showSampleDomains(input: String, type: DomainRulesManager.DomainType): String {
        return when (type) {
            DomainRulesManager.DomainType.TLD -> constructTldSamples(input)
            DomainRulesManager.DomainType.WILDCARD -> constructWildcardSamples(input)
            DomainRulesManager.DomainType.DOMAIN -> constructDomainSamples(input)
        }
    }

    private fun constructTldSamples(input: String): String {
        val validUnicode = activity.getString(R.string.cd_sample_unicode_valid)
        val invalidUnicode = activity.getString(R.string.cd_sample_unicode_invalid)
        var sample = activity.getString(R.string.cd_add_dialog_sample_1, invalidUnicode,
                                                "abc.xyz", input)
        sample += activity.getString(R.string.cd_add_dialog_sample_2, validUnicode,
                                             "abc.xyz", "pqr")
        sample += activity.getString(R.string.cd_add_dialog_sample_3, invalidUnicode,
                                             "xyz.$input")
        sample += activity.getString(R.string.cd_add_dialog_sample_4, validUnicode,
                                             "xyz.pqr")
        return sample
    }

    private fun constructWildcardSamples(input: String): String {
        val validUnicode = activity.getString(R.string.cd_sample_unicode_valid)
        val invalidUnicode = activity.getString(R.string.cd_sample_unicode_invalid)
        var sample = activity.getString(R.string.cd_add_dialog_sample_1, validUnicode,
                                                "abc.", input)
        sample += activity.getString(R.string.cd_add_dialog_sample_2, validUnicode, "abc.",
                                             "$input.pqr")
        sample += activity.getString(R.string.cd_add_dialog_sample_3, invalidUnicode, "xyz")
        sample += activity.getString(R.string.cd_add_dialog_sample_4, validUnicode, "xyz")
        return sample
    }

    private fun constructDomainSamples(input: String): String {
        val validUnicode = activity.getString(R.string.cd_sample_unicode_valid)
        val invalidUnicode = activity.getString(R.string.cd_sample_unicode_invalid)
        var sample = activity.getString(R.string.cd_add_dialog_sample_1, invalidUnicode,
                                                "abc.", input)
        sample += activity.getString(R.string.cd_add_dialog_sample_2, validUnicode, "abc.",
                                             "$input.pqr")
        sample += activity.getString(R.string.cd_add_dialog_sample_3, invalidUnicode, "xyz")
        sample += activity.getString(R.string.cd_add_dialog_sample_4, validUnicode, "xyz")
        return sample
    }

    private fun isValidDomain(url: String): Boolean {
        return try {
            Patterns.WEB_URL.matcher(url).matches() || Patterns.DOMAIN_NAME.matcher(url).matches()
        } catch (ignored: MalformedURLException) { // ignored
            false
        }
    }

    private fun isValidTld(url: String): Boolean {
        // ref: https://regexr.com/38p7n
        val regEx = Pattern.compile("^\\w+(\\.\\w+)*\$")
        return regEx.matcher(url).find()
    }

    private fun isWildCardEntry(url: String): Boolean {
        // ref: https://stackoverflow.com/a/50448970
        /*val regEx = Pattern.compile(
            "(?i)^(?:\\\\S+(?::\\\\S*)?@)?(?:(?!(?:10|127)(?:\\.\\d{1,3}){3})(?!(?:169\\.254|192\\.168)(?:\\.\\d{1,3}){2})(?!172\\.(?:1[6-9]|2\\d|3[0-1])(?:\\.\\d{1,3}){2})(?:[1-9]\\d?|1\\d\\d|2[01]\\d|22[0-3])(?:\\.(?:1?\\d{1,2}|2[0-4]\\d|25[0-5])){2}(?:\\.(?:[1-9]\\d?|1\\d\\d|2[0-4]\\d|25[0-4]))|"
                    //DOMAIN
                    + "(?:(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+|\\*)(?:\\.(?:[a-z\\u00a1-\\uffff0-9]-*)*[a-z\\u00a1-\\uffff0-9]+)*(?:\\.(?:[a-z\\u00a1-\\uffff]{2,}))\\.?)(?::\\d{2,5})?(?:[/?#]\\S*)?$")*/

        // ref: https://stackoverflow.com/questions/51500161/regex-to-validate-wildcard-domains-with-special-conditions
        val pattern = Pattern.compile(
            "(?=^[*]|.*[*]\$)(?:\\*(?:\\.|-)?)?(?:(?!-)[\\w-]+(?:(?<!-)\\.)?)+([a-z])+(?:(?:-|\\.)?\\*)?\$")
        return pattern.matcher(url).find()
    }

    private fun insertDomain(domain: String, type: DomainRulesManager.DomainType) {
        DomainRulesManager.block(domain, type = type)
        Utilities.showToastUiCentered(activity,
                                      activity.resources.getString(R.string.cd_toast_added),
                                      Toast.LENGTH_SHORT)
    }

    private fun toggleChipsUi() {
        if (b.cdfFilterChipGroup.isVisible) {
            hideChipsUi()
        } else {
            showChipsUi()
        }
    }

    private fun hideChipsUi() {
        b.cdfFilterChipGroup.visibility = View.GONE
    }

    private fun showChipsUi() {
        b.cdfFilterChipGroup.visibility = View.VISIBLE
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        filterQuery = query
        viewModel.setFilter(query, filterType)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        filterQuery = query
        viewModel.setFilter(query, filterType)
        return true
    }

    private fun remakeParentFilterChipsUi() {
        b.cdfFilterChipGroup.removeAllViews()

        val all = makeParentChip(DomainRulesManager.DomainStatus.NONE.statusId,
                                 activity.getString(R.string.cd_filter_all), true)
        val allowed = makeParentChip(DomainRulesManager.DomainStatus.WHITELISTED.statusId,
                                     activity.getString(R.string.cd_filter_allowed), false)
        val blocked = makeParentChip(DomainRulesManager.DomainStatus.BLOCKED.statusId,
                                     activity.getString(R.string.cd_filter_blocked), false)

        b.cdfFilterChipGroup.addView(all)
        b.cdfFilterChipGroup.addView(allowed)
        b.cdfFilterChipGroup.addView(blocked)
    }

    private fun makeParentChip(id: Int, label: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, null, false) as Chip
        chip.tag = id
        chip.text = label
        chip.isChecked = checked

        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            if (isSelected) { // apply filter only when the CompoundButton is selected
                // tag attached to the button is of type integer
                applyParentFilter(button.tag as Int)
            }
        }

        return chip
    }

    private fun applyParentFilter(id: Int) {
        viewModel.setFilter(filterQuery, DomainRulesManager.DomainStatus.getStatus(id))
    }
}
