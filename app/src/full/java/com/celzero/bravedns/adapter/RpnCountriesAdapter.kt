/*
 * Copyright 2023 RethinkDNS and its authors
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
package com.celzero.bravedns.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ListItemRpnCountriesBinding
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities.getFlag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RpnCountriesAdapter(private val context: Context, private val countries: List<String>, private val selectedCCs: Set<String>) :
    RecyclerView.Adapter<RpnCountriesAdapter.RpnCountriesViewHolder>() {

    private var lifecycleOwner: LifecycleOwner? = null

    override fun onBindViewHolder(holder: RpnCountriesViewHolder, position: Int) {
        holder.update(countries[position])
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RpnCountriesViewHolder {
        val itemBinding =
            ListItemRpnCountriesBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        if (lifecycleOwner == null) {
            lifecycleOwner = parent.findViewTreeLifecycleOwner()
        }
        return RpnCountriesViewHolder(itemBinding)
    }

    override fun getItemCount(): Int {
        return countries.size
    }

    inner class RpnCountriesViewHolder(private val b: ListItemRpnCountriesBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(conf: String) {
            val flag = getFlag(conf)
            val ccName = conf //.name.ifEmpty { getCountryNameFromFlag(flag) }
            b.rpncNameText.text = ccName
            b.rpncFlagText.text = flag
            val isSelected = selectedCCs.contains(conf)
            if (isSelected) {
                enableInterface()
            } else {
                disableInterface()
            }
            val strokeColor = getStrokeColorForStatus(isSelected)
            b.rpncCard.strokeColor = fetchColor(context, strokeColor)
        }

        private fun getStrokeColorForStatus(isActive: Boolean): Int{
            if (!isActive) return fetchColor(context, R.attr.chipTextNegative)
            return fetchColor(context, R.attr.accentGood)
        }

        private fun enableInterface() {
            b.rpncCard.strokeWidth = 2
            b.rpncInfoChipGroup.visibility = View.VISIBLE
            b.rpncCheck.isChecked = true
            b.rpncActiveChip.visibility = View.VISIBLE
            b.rpncActiveLayout.visibility = View.VISIBLE
            b.rpncStatusText.text =
                context.getString(R.string.lbl_active).replaceFirstChar(Char::titlecase)
        }

        private fun disableInterface() {
            b.rpncCard.strokeWidth = 0
            b.rpncInfoChipGroup.visibility = View.GONE
            b.rpncCheck.isChecked = false
            b.rpncActiveChip.visibility = View.GONE
            b.rpncActiveLayout.visibility = View.GONE
            b.rpncStatusText.text =
                context.getString(R.string.lbl_disabled).replaceFirstChar(Char::titlecase)
        }

    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit): Job? {
        return lifecycleOwner?.lifecycleScope?.launch(Dispatchers.IO) { f() }
    }
}
