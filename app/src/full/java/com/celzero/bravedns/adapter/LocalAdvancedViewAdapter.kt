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
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.RethinkLocalFileTag
import com.celzero.bravedns.databinding.ListItemRethinkBlocklistAdvBinding
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.ui.fragment.RethinkBlocklistFragment
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.UIUtils.openUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class LocalAdvancedViewAdapter(val context: Context) :
    PagingDataAdapter<RethinkLocalFileTag, LocalAdvancedViewAdapter.RethinkLocalFileTagViewHolder>(
        DIFF_CALLBACK
    ) {

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<RethinkLocalFileTag>() {

                override fun areItemsTheSame(
                    oldConnection: RethinkLocalFileTag,
                    newConnection: RethinkLocalFileTag
                ): Boolean {
                    return oldConnection == newConnection
                }

                override fun areContentsTheSame(
                    oldConnection: RethinkLocalFileTag,
                    newConnection: RethinkLocalFileTag
                ): Boolean {
                    return (oldConnection.value == newConnection.value &&
                        oldConnection.isSelected == newConnection.isSelected)
                }
            }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): RethinkLocalFileTagViewHolder {
        val itemBinding =
            ListItemRethinkBlocklistAdvBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        return RethinkLocalFileTagViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: RethinkLocalFileTagViewHolder, position: Int) {
        val filetag: RethinkLocalFileTag = getItem(position) ?: return

        holder.update(filetag, position)
    }

    inner class RethinkLocalFileTagViewHolder(private val b: ListItemRethinkBlocklistAdvBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(filetag: RethinkLocalFileTag, position: Int) {
            b.root.tag = getGroupName(filetag.group)
            displayHeaderIfNeeded(filetag, position)
            displayMetaData(filetag)

            b.crpCheckBox.setOnClickListener { toggleCheckbox(b.crpCheckBox.isChecked, filetag) }

            b.crpCard.setOnClickListener { toggleCheckbox(!b.crpCheckBox.isChecked, filetag) }

            b.crpDescEntriesTv.setOnClickListener { openUrl(context, filetag.url[0]) }
        }

        private fun displayMetaData(filetag: RethinkLocalFileTag) {
            b.crpLabelTv.text = filetag.vname
            if (filetag.subg.isEmpty()) {
                b.crpDescGroupTv.text = filetag.group
            } else {
                b.crpDescGroupTv.text = filetag.subg
            }

            setEntries(filetag)
            b.crpCheckBox.isChecked = filetag.isSelected
            setCardBackground(filetag.isSelected)
        }

        private fun setEntries(filetag: RethinkLocalFileTag) {
            b.crpDescEntriesTv.text =
                context.getString(R.string.dc_entries, filetag.entries.toString())

            if (filetag.level.isNullOrEmpty()) return

            val level = filetag.level?.get(0) ?: return
            when (level) {
                0 -> {
                    val color = fetchColor(context, R.attr.chipTextPositive)
                    val bgColor = fetchColor(context, R.attr.chipBgColorPositive)
                    b.crpDescEntriesTv.setTextColor(color)
                    b.crpDescEntriesTv.chipBackgroundColor = ColorStateList.valueOf(bgColor)
                }
                1 -> {
                    val color = fetchColor(context, R.attr.chipTextNeutral)
                    val bgColor = fetchColor(context, R.attr.chipBgColorNeutral)
                    b.crpDescEntriesTv.setTextColor(color)
                    b.crpDescEntriesTv.chipBackgroundColor = ColorStateList.valueOf(bgColor)
                }
                2 -> {
                    val color = fetchColor(context, R.attr.chipTextNegative)
                    val bgColor = fetchColor(context, R.attr.chipBgColorNegative)
                    b.crpDescEntriesTv.setTextColor(color)
                    b.crpDescEntriesTv.chipBackgroundColor = ColorStateList.valueOf(bgColor)
                }
                else -> {
                    /* no-op */
                }
            }
        }

        // handle the group name (filetag.json)
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
            setFileTag(filetag, isSelected)
        }

        private fun setFileTag(filetag: RethinkLocalFileTag, selected: Boolean) {
            io {
                filetag.isSelected = selected
                RethinkBlocklistManager.updateFiletagLocal(filetag)
                val list = RethinkBlocklistManager.getSelectedFileTagsLocal().toSet()
                RethinkBlocklistFragment.updateFileTagList(list)
            }
        }

        private fun displayHeaderIfNeeded(filetag: RethinkLocalFileTag, position: Int) {
            if (position == 0 || getItem(position - 1)?.group != filetag.group) {
                b.crpTitleLl.visibility = View.VISIBLE
                b.crpBlocktypeHeadingTv.text = getGroupName(filetag.group)
                b.crpBlocktypeDescTv.text = getTitleDesc(filetag.group)
                return
            }

            b.crpTitleLl.visibility = View.GONE
        }

        private fun getTitleDesc(title: String): String {
            return if (title.equals(RethinkBlocklistManager.PARENTAL_CONTROL.name, true)) {
                context.getString(RethinkBlocklistManager.PARENTAL_CONTROL.desc)
            } else if (title.equals(RethinkBlocklistManager.SECURITY.name, true)) {
                context.getString(RethinkBlocklistManager.SECURITY.desc)
            } else if (title.equals(RethinkBlocklistManager.PRIVACY.name, true)) {
                context.getString(RethinkBlocklistManager.PRIVACY.desc)
            } else {
                ""
            }
        }

        private fun io(f: suspend () -> Unit) {
            CoroutineScope(Dispatchers.IO).launch { f() }
        }
    }
}
