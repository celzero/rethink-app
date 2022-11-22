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
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.RethinkBlocklistManager
import com.celzero.bravedns.databinding.ListItemRethinkBlocklistSimpleBinding
import com.celzero.bravedns.ui.RethinkBlocklistFragment
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RethinkSimpleViewPacksAdapter(val context: Context,
                                    var fileTags: List<RethinkBlocklistManager.SimpleViewPacksTag>,
                                    val selectedTags: List<Int>,
                                    val type: RethinkBlocklistFragment.RethinkBlocklistType) :
        RecyclerView.Adapter<RethinkSimpleViewPacksAdapter.RethinkSimpleViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RethinkSimpleViewHolder {
        val itemBinding = ListItemRethinkBlocklistSimpleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        return RethinkSimpleViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: RethinkSimpleViewHolder, position: Int) {
        holder.update(position)
    }

    override fun getItemCount(): Int {
        return fileTags.size
    }

    inner class RethinkSimpleViewHolder(private val b: ListItemRethinkBlocklistSimpleBinding) :
            RecyclerView.ViewHolder(b.root) {

        fun update(position: Int) {
            displayMetaData(position)
            setupClickListener(position)
        }

        private fun setupClickListener(position: Int) {
            b.crpCheckBox.setOnClickListener {
                toggleCheckbox(b.crpCheckBox.isChecked, position)
            }

            b.crpCard.setOnClickListener {
                toggleCheckbox(!b.crpCheckBox.isChecked, position)
            }
        }

        private fun setCardBackground(card: CardView, isSelected: Boolean) {
            if (isSelected) {
                card.setCardBackgroundColor(Utilities.fetchColor(context, R.attr.selectedCardBg))
            } else {
                card.setCardBackgroundColor(Utilities.fetchColor(context, R.attr.background))
            }
        }

        private fun toggleCheckbox(isSelected: Boolean, position: Int) {
            b.crpCheckBox.isChecked = isSelected
            setCardBackground(b.crpCard, isSelected)
            setFileTag(fileTags[position].tags, if (isSelected) 1 else 0)
        }

        private fun setFileTag(tagIds: MutableList<Int>?, selected: Int) {
            Log.d("TEST","TEST Update blocklist tag: $tagIds, $selected")
            if (tagIds == null || tagIds.isEmpty()) return

            io {
                if (type.isRemote()) {
                    RethinkBlocklistManager.updateFiletagsRemote(tagIds.toSet(), selected)
                    val selectedTags = RethinkBlocklistManager.getSelectedFileTagsRemote().toSet()
                    val stamp = RethinkBlocklistManager.getStamp(context, selectedTags,
                                                                 RethinkBlocklistFragment.RethinkBlocklistType.REMOTE)
                    RethinkBlocklistFragment.modifiedStamp = stamp
                } else {
                    RethinkBlocklistManager.updateFiletagsLocal(tagIds.toSet(), selected)
                    val selectedTags = RethinkBlocklistManager.getSelectedFileTagsLocal().toSet()
                    val stamp = RethinkBlocklistManager.getStamp(context, selectedTags,
                                                                 RethinkBlocklistFragment.RethinkBlocklistType.LOCAL)
                    RethinkBlocklistFragment.modifiedStamp = stamp
                }
            }
        }

        private fun displayMetaData(position: Int) {
            val simpleView = fileTags[position]
            setCardBackground(b.crpCard, false)

            // check to show the title and desc, as of now these values are predefined so checking
            // with those pre defined values.
            if (position == 0 || fileTags[position - 1].group != simpleView.group) {
                b.crpTitleLl.visibility = View.VISIBLE
                b.crpBlocktypeHeadingTv.text = simpleView.group.replaceFirstChar(Char::titlecase)
                b.crpBlocktypeDescTv.text = getTitleDesc(simpleView.group)
            } else {
                b.crpTitleLl.visibility = View.GONE
            }

            b.crpLabelTv.text = simpleView.name.replaceFirstChar(Char::titlecase)
            b.crpDescGroupTv.text = simpleView.desc.replaceFirstChar(
                Char::titlecase) + " blocklists"
            b.crpCheckBox.isChecked = false

            // enable the check box if the stamp contains all the values
            if (selectedTags.isEmpty() || simpleView.tags.isEmpty()) return

            if (selectedTags.containsAll(simpleView.tags)) {
                Log.d("TEST",
                      "TEST Selected tag contains all values: ${simpleView.name}, ${simpleView.tags}")
                b.crpCheckBox.isChecked = true
                setCardBackground(b.crpCard, true)
            }
        }

        private fun getTitleDesc(title: String): String {
            return if (title.equals(RethinkBlocklistManager.PARENTAL_CONTROL.name, true)) {
                RethinkBlocklistManager.PARENTAL_CONTROL.desc
            } else if (title.equals(RethinkBlocklistManager.SECURITY.name, true)) {
                RethinkBlocklistManager.SECURITY.desc
            } else {
                RethinkBlocklistManager.PRIVACY.desc
            }
        }

        private fun io(f: suspend () -> Unit) {
            CoroutineScope(Dispatchers.IO).launch {
                f()
            }
        }

    }

}
