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
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.RethinkBlocklistManager
import com.celzero.bravedns.database.RethinkRemoteFileTag
import com.celzero.bravedns.databinding.ListItemRethinkBlocklistAdvBinding
import com.celzero.bravedns.ui.RethinkBlocklistFragment.Companion.selectedFileTags
import com.celzero.bravedns.util.Utilities.Companion.fetchColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RethinkRemoteAdvancedViewAdapter(val context: Context) :
        PagedListAdapter<RethinkRemoteFileTag, RethinkRemoteAdvancedViewAdapter.RethinkRemoteFileTagViewHolder>(
            DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RethinkRemoteFileTag>() {

            override fun areItemsTheSame(oldConnection: RethinkRemoteFileTag,
                                         newConnection: RethinkRemoteFileTag): Boolean {
                return oldConnection == newConnection
            }

            override fun areContentsTheSame(oldConnection: RethinkRemoteFileTag,
                                            newConnection: RethinkRemoteFileTag): Boolean {
                return (oldConnection.value == newConnection.value && oldConnection.isSelected == newConnection.isSelected)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup,
                                    viewType: Int): RethinkRemoteFileTagViewHolder {
        Log.d("TEST", "TEST onCreateViewHolder")
        val itemBinding = ListItemRethinkBlocklistAdvBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        return RethinkRemoteFileTagViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: RethinkRemoteFileTagViewHolder, position: Int) {
        val filetag: RethinkRemoteFileTag = getItem(position) ?: return

        holder.update(filetag, position)
    }


    inner class RethinkRemoteFileTagViewHolder(private val b: ListItemRethinkBlocklistAdvBinding) :
            RecyclerView.ViewHolder(b.root) {

        fun update(filetag: RethinkRemoteFileTag, position: Int) {
            displayHeaderIfNeeded(filetag, position)
            displayMetaData(filetag)

            b.crpCheckBox.setOnClickListener {
                toggleCheckbox(b.crpCheckBox.isChecked, filetag)
            }

            b.crpCard.setOnClickListener {
                toggleCheckbox(!b.crpCheckBox.isChecked, filetag)
            }
        }

        private fun displayMetaData(filetag: RethinkRemoteFileTag) {
            b.crpLabelTv.text = filetag.vname
            b.crpDescGroupTv.text = filetag.group
            b.crpDescSubgTv.text = filetag.subg
            b.crpCheckBox.isChecked = filetag.isSelected
            setChipBackground(filetag.isSelected)
        }

        private fun setChipBackground(isSelected: Boolean) {
            if (isSelected) {
                b.crpCard.setCardBackgroundColor(fetchColor(context, R.attr.border))
            } else {
                b.crpCard.setCardBackgroundColor(fetchColor(context, R.attr.background))
            }
        }

        private fun toggleCheckbox(isSelected: Boolean, filetag: RethinkRemoteFileTag) {
            b.crpCheckBox.isChecked = isSelected
            setChipBackground(isSelected)

            io {
                if (isSelected) {
                    addBlocklistTag(filetag)
                    return@io
                }

                removeBlocklistTag(filetag)
            }
        }

        private fun addBlocklistTag(filetag: RethinkRemoteFileTag) {
            io {
                filetag.isSelected = true
                RethinkBlocklistManager.updateSelectedFiletagRemote(filetag)
            }
            if (selectedFileTags.value == null) {
                selectedFileTags.postValue(mutableSetOf(filetag.value))
                return
            }

            selectedFileTags.value?.add(filetag.value)
            selectedFileTags.postValue(selectedFileTags.value)
        }

        private fun removeBlocklistTag(filetag: RethinkRemoteFileTag) {
            io {
                filetag.isSelected = false
                RethinkBlocklistManager.updateSelectedFiletagRemote(filetag)
            }

            if (selectedFileTags.value == null) return

            selectedFileTags.value?.remove(filetag.value)
            selectedFileTags.postValue(selectedFileTags.value)
        }

        private fun displayHeaderIfNeeded(filetag: RethinkRemoteFileTag, position: Int) {
            if (position == 0 || getItem(position - 1)?.group != filetag.group) {
                b.crpTitleLl.visibility = View.VISIBLE
                b.crpBlocktypeHeadingTv.text = getGroupName(filetag.group)
                return
            }

            b.crpTitleLl.visibility = View.GONE
        }

        // fixme: remove this method, add it in strings.xml
        private fun getGroupName(group: String): String {
            if (group.equals("parentalcontrol")) {
                return "Parental Control"
            } else if (group.equals("privacy")) {
                return "Privacy"
            } else if (group.equals("security")) {
                return "Security"
            } else {
                return ""
            }
        }

        private fun io(f: suspend () -> Unit) {
            CoroutineScope(Dispatchers.IO).launch {
                f()
            }
        }

    }
}
