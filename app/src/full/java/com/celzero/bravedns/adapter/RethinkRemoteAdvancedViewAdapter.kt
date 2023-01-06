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
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.database.RethinkRemoteFileTag
import com.celzero.bravedns.databinding.ListItemRethinkBlocklistAdvBinding
import com.celzero.bravedns.ui.RethinkBlocklistFragment
import com.celzero.bravedns.util.Utilities.Companion.fetchColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RethinkRemoteAdvancedViewAdapter(val context: Context) :
    PagingDataAdapter<
        RethinkRemoteFileTag, RethinkRemoteAdvancedViewAdapter.RethinkRemoteFileTagViewHolder
    >(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<RethinkRemoteFileTag>() {

                override fun areItemsTheSame(
                    oldConnection: RethinkRemoteFileTag,
                    newConnection: RethinkRemoteFileTag
                ): Boolean {
                    return oldConnection == newConnection
                }

                override fun areContentsTheSame(
                    oldConnection: RethinkRemoteFileTag,
                    newConnection: RethinkRemoteFileTag
                ): Boolean {
                    return (oldConnection.value == newConnection.value &&
                        oldConnection.isSelected == newConnection.isSelected)
                }
            }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RethinkRemoteFileTagViewHolder {
        val itemBinding =
            ListItemRethinkBlocklistAdvBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
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

            b.crpCheckBox.setOnClickListener { toggleCheckbox(b.crpCheckBox.isChecked, filetag) }

            b.crpCard.setOnClickListener { toggleCheckbox(!b.crpCheckBox.isChecked, filetag) }

            b.crpDescEntriesTv.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, filetag.url[0].toUri())
                context.startActivity(intent)
            }
        }

        private fun displayMetaData(filetag: RethinkRemoteFileTag) {
            b.crpLabelTv.text = filetag.vname

            if (filetag.subg.isEmpty()) {
                b.crpDescGroupTv.text = filetag.group
            } else {
                b.crpDescGroupTv.text = filetag.subg
            }
            b.crpDescEntriesTv.text =
                context.getString(R.string.dc_entries, filetag.entries.toString())

            b.crpCheckBox.isChecked = filetag.isSelected
            setCardBackground(filetag.isSelected)
        }

        private fun setCardBackground(isSelected: Boolean) {
            if (isSelected) {
                b.crpCard.setCardBackgroundColor(fetchColor(context, R.attr.selectedCardBg))
            } else {
                b.crpCard.setCardBackgroundColor(fetchColor(context, R.attr.background))
            }
        }

        private fun toggleCheckbox(isSelected: Boolean, filetag: RethinkRemoteFileTag) {
            b.crpCheckBox.isChecked = isSelected
            setCardBackground(isSelected)
            setFileTag(filetag, isSelected)
        }

        private fun setFileTag(filetag: RethinkRemoteFileTag, selected: Boolean) {
            io {
                filetag.isSelected = selected
                RethinkBlocklistManager.updateFiletagRemote(filetag)
                val list = RethinkBlocklistManager.getSelectedFileTagsRemote().toSet()
                val stamp =
                    RethinkBlocklistManager.getStamp(
                        context,
                        list,
                        RethinkBlocklistManager.RethinkBlocklistType.REMOTE
                    )
                RethinkBlocklistFragment.modifiedStamp = stamp
            }
        }

        private fun displayHeaderIfNeeded(filetag: RethinkRemoteFileTag, position: Int) {
            if (position == 0 || getItem(position - 1)?.group != filetag.group) {
                b.crpTitleLl.visibility = View.VISIBLE
                b.crpBlocktypeHeadingTv.text = getGroupName(filetag.group)
                return
            }

            b.crpTitleLl.visibility = View.GONE
        }

        private fun getGroupName(group: String): String {
            return if (group.equals(RethinkBlocklistManager.PARENTAL_CONTROL.name, true)) {
                context.getString(RethinkBlocklistManager.PARENTAL_CONTROL.label)
            } else if (group.equals(RethinkBlocklistManager.SECURITY.name, true)) {
                context.getString(RethinkBlocklistManager.SECURITY.label)
            } else if (group.equals(RethinkBlocklistManager.PRIVACY.name, true)) {
                context.getString(RethinkBlocklistManager.PRIVACY.label)
            } else {
                ""
            }
        }

        private fun io(f: suspend () -> Unit) {
            CoroutineScope(Dispatchers.IO).launch { f() }
        }
    }
}
