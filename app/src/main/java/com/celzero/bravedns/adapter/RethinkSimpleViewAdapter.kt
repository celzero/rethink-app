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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.automaton.RethinkBlocklistManager
import com.celzero.bravedns.databinding.ListItemRethinkBlocklistSimpleBinding
import com.celzero.bravedns.ui.RethinkBlocklistFragment
import com.celzero.bravedns.ui.RethinkBlocklistFragment.Companion.selectedFileTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RethinkSimpleViewAdapter(var fileTags: List<RethinkBlocklistManager.SimpleViewTag>,
                               val type: RethinkBlocklistFragment.RethinkBlocklistType) :
        RecyclerView.Adapter<RethinkSimpleViewAdapter.RethinkSimpleViewHolder>() {

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

        private fun toggleCheckbox(isSelected: Boolean, position: Int) {
            b.crpCheckBox.isChecked = isSelected
            if (isSelected) {
                addBlocklistTag(fileTags[position].tags)
                return
            }

            removeBlocklistTag(fileTags[position].tags)
        }

        private fun addBlocklistTag(set: MutableList<Int>?) {
            if (set == null) return

            updateRethinkBlocklist(set, 1 /* isSelected: true */)

            if (selectedFileTags.value == null) {
                selectedFileTags.value = set.toMutableSet()
            } else {
                selectedFileTags.value?.addAll(set)
            }

            selectedFileTags.postValue(selectedFileTags.value)
        }

        private fun removeBlocklistTag(set: MutableList<Int>?) {
            if (set == null) return

            updateRethinkBlocklist(set, 0 /* isSelected: false */)

            if (selectedFileTags.value == null) {
                selectedFileTags.value = set.toMutableSet()
            } else {
                selectedFileTags.value?.removeAll(set)
            }

            selectedFileTags.postValue(selectedFileTags.value)
        }

        private fun updateRethinkBlocklist(set: MutableList<Int>, isSelected: Int) {
            io {
                if (type.isRemote()) {
                    RethinkBlocklistManager.updateSelectedFiletagsRemote(set.toSet(), isSelected)
                } else {
                    RethinkBlocklistManager.updateSelectedFiletagsLocal(set.toSet(), isSelected)
                }
            }
        }

        private fun displayMetaData(position: Int) {
            val simpleView = fileTags[position]

            // check to show the title and desc, as of now these values are predefined so checking
            // with those pre defined values.
            if (simpleView.id == 0 || simpleView.id == 5 || simpleView.id == 7) {
                b.crpTitleLl.visibility = View.VISIBLE
                b.crpBlocktypeHeadingTv.text = simpleView.rethinkBlockType.name
                b.crpBlocktypeDescTv.text = simpleView.rethinkBlockType.desc
            } else {
                b.crpTitleLl.visibility = View.GONE
            }

            b.crpLabelTv.text = simpleView.name
            b.crpDescGroupTv.text = simpleView.desc
            b.crpCheckBox.isChecked = false

            // enable the check box if the stamp contains all the values
            if (selectedFileTags.value.isNullOrEmpty()) return

            if (selectedFileTags.value!!.containsAll(simpleView.tags)) {
                b.crpCheckBox.isChecked = true
            }
        }

        private fun io(f: suspend () -> Unit) {
            CoroutineScope(Dispatchers.IO).launch {
                f()
            }
        }

    }

}
