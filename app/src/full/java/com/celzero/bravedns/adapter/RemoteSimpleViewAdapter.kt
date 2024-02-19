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
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.RemoteBlocklistPacksMap
import com.celzero.bravedns.databinding.ListItemRethinkBlocklistSimpleBinding
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.ui.fragment.RethinkBlocklistFragment
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.UIUtils.fetchToggleBtnColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RemoteSimpleViewAdapter(val context: Context) :
    PagingDataAdapter<RemoteBlocklistPacksMap, RemoteSimpleViewAdapter.RethinkSimpleViewHolder>(
        DIFF_CALLBACK
    ) {

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<RemoteBlocklistPacksMap>() {

                override fun areItemsTheSame(
                    oldConnection: RemoteBlocklistPacksMap,
                    newConnection: RemoteBlocklistPacksMap
                ): Boolean {
                    return oldConnection == newConnection
                }

                override fun areContentsTheSame(
                    oldConnection: RemoteBlocklistPacksMap,
                    newConnection: RemoteBlocklistPacksMap
                ): Boolean {
                    return (oldConnection.pack == newConnection.pack &&
                        oldConnection.level == newConnection.level)
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RethinkSimpleViewHolder {
        val itemBinding =
            ListItemRethinkBlocklistSimpleBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        return RethinkSimpleViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: RethinkSimpleViewHolder, position: Int) {
        val map: RemoteBlocklistPacksMap = getItem(position) ?: return
        holder.update(map, position)
    }

    inner class RethinkSimpleViewHolder(private val b: ListItemRethinkBlocklistSimpleBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(map: RemoteBlocklistPacksMap, position: Int) {
            b.root.tag = getGroupName(map.group)
            displayMetaData(map, position)
            setupClickListener(map)
        }

        private fun setupClickListener(map: RemoteBlocklistPacksMap) {
            b.crpCheckBox.setOnClickListener { toggleCheckbox(b.crpCheckBox.isChecked, map) }

            b.crpCard.setOnClickListener { toggleCheckbox(!b.crpCheckBox.isChecked, map) }
        }

        private fun setCardBackground(card: CardView, isSelected: Boolean) {
            if (isSelected) {
                card.setCardBackgroundColor(fetchColor(context, R.attr.selectedCardBg))
            } else {
                card.setCardBackgroundColor(fetchColor(context, R.attr.background))
            }
        }

        private fun toggleCheckbox(isSelected: Boolean, map: RemoteBlocklistPacksMap) {
            b.crpCheckBox.isChecked = isSelected
            setCardBackground(b.crpCard, isSelected)
            setFileTag(map.blocklistIds.toMutableList(), if (isSelected) 1 else 0)
        }

        private fun setFileTag(tagIds: MutableList<Int>, selected: Int) {
            io {
                RethinkBlocklistManager.updateFiletagsRemote(tagIds.toSet(), selected)
                val selectedTags = RethinkBlocklistManager.getSelectedFileTagsRemote().toSet()
                RethinkBlocklistFragment.updateFileTagList(selectedTags)
                ui { notifyDataSetChanged() }
            }
        }

        private fun displayMetaData(map: RemoteBlocklistPacksMap, position: Int) {
            setCardBackground(b.crpCard, false)

            // check to show the title and desc, as of now these values are predefined so checking
            // with those pre defined values.
            if (position == 0 || getItem(position - 1)?.group != map.group) {
                b.crpTitleLl.visibility = View.VISIBLE
                b.crpBlocktypeHeadingTv.text = getGroupName(map.group)
                b.crpBlocktypeDescTv.text = getTitleDesc(map.group)
            } else {
                b.crpTitleLl.visibility = View.GONE
            }

            b.crpLabelTv.text = map.pack.replaceFirstChar(Char::titlecase)
            b.crpDescGroupTv.text =
                context.getString(
                    R.string.rsv_blocklist_count_text,
                    map.blocklistIds.size.toString()
                )

            val selectedTags = RethinkBlocklistFragment.getSelectedFileTags()
            // enable the check box if the stamp contains all the values
            b.crpCheckBox.isChecked = selectedTags.containsAll(map.blocklistIds)
            setCardBackground(b.crpCard, b.crpCheckBox.isChecked)

            // show level indicator
            showLevelIndicator(b.crpLevelIndicator, map.level)
        }

        private fun showLevelIndicator(mIconIndicator: TextView, level: Int) {
            when (level) {
                0 -> {
                    val color = fetchToggleBtnColors(context, R.color.firewallNoRuleToggleBtnBg)
                    mIconIndicator.setBackgroundColor(color)
                }
                1 -> {
                    val color = fetchToggleBtnColors(context, R.color.firewallWhiteListToggleBtnTxt)
                    mIconIndicator.setBackgroundColor(color)
                }
                2 -> {
                    val color = fetchToggleBtnColors(context, R.color.firewallBlockToggleBtnTxt)
                    mIconIndicator.setBackgroundColor(color)
                }
                else -> {
                    /* no-op */
                }
            }
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

        private fun io(f: suspend () -> Unit) {
            CoroutineScope(Dispatchers.IO).launch { f() }
        }

        private fun ui(f: () -> Unit) {
            CoroutineScope(Dispatchers.Main).launch { f() }
        }
    }
}
