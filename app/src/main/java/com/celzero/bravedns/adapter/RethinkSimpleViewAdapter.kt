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
package com.celzero.bravedns.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.automaton.RethinkBlocklistsManager
import com.celzero.bravedns.data.FileTag
import com.celzero.bravedns.databinding.ListConfigureRethinkBasicBinding
import com.celzero.bravedns.ui.ConfigureRethinkBasicActivity
import com.celzero.bravedns.ui.RethinkLocalBlocklistFragment
import com.celzero.bravedns.ui.RethinkRemoteBlocklistFragment

class RethinkSimpleViewAdapter(var fileTags: List<FileTag>, val type: ConfigureRethinkBasicActivity.FragmentLoader) :
        RecyclerView.Adapter<RethinkSimpleViewAdapter.RethinkSimpleViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RethinkSimpleViewHolder {
        val itemBinding = ListConfigureRethinkBasicBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        return RethinkSimpleViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: RethinkSimpleViewHolder, position: Int) {
        holder.update(position)
    }

    override fun getItemCount(): Int {
        return fileTags.size
    }

    inner class RethinkSimpleViewHolder(private val b: ListConfigureRethinkBasicBinding) :
            RecyclerView.ViewHolder(b.root) {

        fun update(position: Int) {
            val groupName = fileTags[position].simpleViewTag

            displayMetaData(groupName)
            setupClickListener()
        }

        private fun setupClickListener() {
            b.crpCheckBox.setOnCheckedChangeListener { _, isSelected ->
                if (isSelected) {
                    getStamp()
                }
            }

            b.crpCard.setOnClickListener {
                // TODO: show some kind of expand view / some other details of the card?
            }
        }

        private fun getStamp() {
            if (type == ConfigureRethinkBasicActivity.FragmentLoader.LOCAL) {
                RethinkLocalBlocklistFragment.selectedFileTags.value?.let { it ->
                    RethinkBlocklistsManager.getStamp(it)
                }
                return
            }

            RethinkRemoteBlocklistFragment.selectedFileTags.value?.let {
                RethinkBlocklistsManager.getStamp(it)
            }
        }

        private fun displayMetaData(groupName: RethinkBlocklistsManager.SimpleViewTag?) {
            if (groupName == null) return

            b.crpLabelTv.text = groupName.name
            b.crpDescGroupTv.text = groupName.desc
        }

    }

}
