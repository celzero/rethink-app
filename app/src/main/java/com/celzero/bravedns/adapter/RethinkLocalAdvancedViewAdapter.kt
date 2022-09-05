/*
 * Copyright 2022 RethinkDNS and its authors
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
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.RethinkBlocklistManager
import com.celzero.bravedns.database.RethinkLocalFileTag
import com.celzero.bravedns.databinding.ListItemRethinkBlocklistAdvBinding
import com.celzero.bravedns.ui.RethinkBlocklistFragment.Companion.selectedFileTags
import com.celzero.bravedns.util.Utilities.Companion.fetchColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RethinkLocalAdvancedViewAdapter(val context: Context) :
        PagingDataAdapter<RethinkLocalFileTag, RethinkLocalAdvancedViewAdapter.RethinkLocalFileTagViewHolder>(
            DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RethinkLocalFileTag>() {

            override fun areItemsTheSame(oldConnection: RethinkLocalFileTag,
                                         newConnection: RethinkLocalFileTag): Boolean {
                return oldConnection == newConnection
            }

            override fun areContentsTheSame(oldConnection: RethinkLocalFileTag,
                                            newConnection: RethinkLocalFileTag): Boolean {
                return (oldConnection.value == newConnection.value && oldConnection.isSelected == newConnection.isSelected)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup,
                                    viewType: Int): RethinkLocalFileTagViewHolder {
        val itemBinding = ListItemRethinkBlocklistAdvBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        return RethinkLocalFileTagViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: RethinkLocalFileTagViewHolder, position: Int) {
        val filetag: RethinkLocalFileTag = getItem(position) ?: return

        holder.update(filetag, position)
    }


    inner class RethinkLocalFileTagViewHolder(private val b: ListItemRethinkBlocklistAdvBinding) :
            RecyclerView.ViewHolder(b.root) {

        fun update(filetag: RethinkLocalFileTag, position: Int) {
            displayHeaderIfNeeded(filetag, position)
            displayMetaData(filetag)

            b.crpCheckBox.setOnClickListener {
                toggleCheckbox(b.crpCheckBox.isChecked, filetag)
            }

            b.crpCard.setOnClickListener {
                toggleCheckbox(!b.crpCheckBox.isChecked, filetag)
            }

            b.crpDescEntriesTv.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, filetag.url.toUri())
                context.startActivity(intent)
            }
        }

        private fun displayMetaData(filetag: RethinkLocalFileTag) {
            b.crpLabelTv.text = filetag.vname
            if (filetag.subg.isEmpty()) {
                b.crpDescGroupTv.text = filetag.group
            } else {
                b.crpDescGroupTv.text = filetag.subg
            }

            b.crpDescEntriesTv.text = context.getString(R.string.dc_entries,
                                                        filetag.entries.toString())
            b.crpCheckBox.isChecked = filetag.isSelected
            setCardBackground(filetag.isSelected)
        }

        // fixme: remove this method, add it in strings.xml
        private fun getGroupName(group: String): String {
            if (group == "parentalcontrol") {
                return context.getString(R.string.rbl_parental_control)
            } else if (group == "privacy") {
                return context.getString(R.string.rbl_privacy)
            } else if (group == "security") {
                return context.getString(R.string.rbl_security)
            } else {
                return ""
            }
        }

        private fun setCardBackground(isSelected: Boolean) {
            if (isSelected) {
                b.crpCard.setCardBackgroundColor(fetchColor(context, R.attr.selectedCardBg))
            } else {
                b.crpCard.setCardBackgroundColor(fetchColor(context, R.attr.background))
            }
        }

        private fun toggleCheckbox(isSelected: Boolean, filetag: RethinkLocalFileTag) {
            b.crpCheckBox.isChecked = isSelected
            setCardBackground(isSelected)

            io {
                if (isSelected) {
                    addBlocklistTag(filetag)
                    return@io
                }

                removeBlocklistTag(filetag)
            }
        }

        private fun addBlocklistTag(filetag: RethinkLocalFileTag) {
            io {
                filetag.isSelected = true
                RethinkBlocklistManager.updateSelectedFiletagLocal(filetag)
            }
            if (selectedFileTags.value == null) {
                selectedFileTags.postValue(mutableSetOf(filetag.value))
                return
            }

            selectedFileTags.value?.add(filetag.value)
            selectedFileTags.postValue(selectedFileTags.value)
        }

        private fun removeBlocklistTag(filetag: RethinkLocalFileTag) {
            io {
                filetag.isSelected = false
                RethinkBlocklistManager.updateSelectedFiletagLocal(filetag)
            }

            if (selectedFileTags.value == null) return

            selectedFileTags.value?.remove(filetag.value)
            selectedFileTags.postValue(selectedFileTags.value)
        }

        private fun displayHeaderIfNeeded(filetag: RethinkLocalFileTag, position: Int) {
            if (position == 0 || getItem(position - 1)?.group != filetag.group) {
                b.crpTitleLl.visibility = View.VISIBLE
                b.crpBlocktypeHeadingTv.text = getGroupName(filetag.group)
                return
            }

            b.crpTitleLl.visibility = View.GONE
        }

        private fun io(f: suspend () -> Unit) {
            CoroutineScope(Dispatchers.IO).launch {
                f()
            }
        }

    }
}
