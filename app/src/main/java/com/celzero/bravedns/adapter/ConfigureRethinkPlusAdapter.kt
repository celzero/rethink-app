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
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.FileTag
import com.celzero.bravedns.databinding.ListConfigureRethinkPlusBinding
import com.celzero.bravedns.ui.ConfigureRethinkPlusActivity
import java.util.*
import kotlin.collections.ArrayList

class ConfigureRethinkPlusAdapter(val context: ConfigureRethinkPlusActivity, var fileTags: List<FileTag>) :
        RecyclerView.Adapter<ConfigureRethinkPlusAdapter.ConfigureRethinkPlusViewHolder>(),
        Filterable {

    override fun onCreateViewHolder(parent: ViewGroup,
                                    viewType: Int): ConfigureRethinkPlusViewHolder {
        val itemBinding = ListConfigureRethinkPlusBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        return ConfigureRethinkPlusViewHolder(itemBinding)
    }

    var filteredTags: List<FileTag> = ArrayList()

    override fun getFilter(): Filter {
        return object : Filter() {
            override fun performFiltering(constraint: CharSequence?): FilterResults {
                val charSearch = constraint.toString()
                filteredTags = if (charSearch.isEmpty()) {
                    fileTags
                } else {
                    getFilteredFileTag(charSearch)
                }
                val filterResults = FilterResults()
                filterResults.values = filteredTags
                return filterResults
            }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
                val r = results?.values as? List<FileTag> ?: return

                filteredTags = r
                notifyDataSetChanged()
            }

            private fun getFilteredFileTag(search: String): ArrayList<FileTag> {
                val resultList = ArrayList<FileTag>()
                val filter = context.filterObserver()

                var d : List<FileTag> = fileTags
                if (filter.value?.groups?.isNotEmpty() == true) {
                    d = fileTags.filter { filter.value!!.groups.contains(it.subg) }
                }

                var e : List<FileTag> = d
                if (filter.value?.subGroups?.isNotEmpty() == true) {
                    e = d.filter { filter.value!!.subGroups.contains(it.group) }
                }

                for (row in e) {
                    if (row.vname.lowercase(Locale.ROOT).contains(search.lowercase(Locale.ROOT))) {
                        resultList.add(row)
                    }
                }
                return resultList
            }
        }
    }

    override fun onBindViewHolder(holder: ConfigureRethinkPlusViewHolder, position: Int) {
        holder.update(position)
    }

    override fun getItemCount(): Int {
        return filteredTags.size
    }

    fun updateFileTag(f: List<FileTag>) {
        if (f.isEmpty()) {
            fileTags = f
            this.filteredTags = f
            notifyItemChanged(0, filteredTags.size)
        } else {
            val result = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int {
                    return fileTags.size
                }

                override fun getNewListSize(): Int {
                    return f.size
                }

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return f[oldItemPosition].uname === f[newItemPosition].uname
                }

                override fun areContentsTheSame(oldItemPosition: Int,
                                                newItemPosition: Int): Boolean {
                    val newMovie: FileTag = f[oldItemPosition]
                    val oldMovie = f[newItemPosition]
                    return newMovie.uname === oldMovie.uname
                }
            })
            fileTags = f
            filteredTags = f
            result.dispatchUpdatesTo(this)
        }
    }

    inner class ConfigureRethinkPlusViewHolder(private val b: ListConfigureRethinkPlusBinding) :
            RecyclerView.ViewHolder(b.root) {

        fun update(position: Int) {
            val groupName = filteredTags[position].group.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }

            displayHeaderIfNeeded(position, groupName)
            displayBlocklistName(position)
            displayMetaData(groupName, position)
        }

        private fun displayBlocklistName(position: Int) {
            b.crpLabelTv.text = filteredTags[position].vname
        }

        private fun displayMetaData(groupName: String, position: Int) {
            b.crpDescGroupTv.text = groupName
            if (filteredTags[position].subg.isBlank()) {
                b.crpDescSubgTv.text = context.getString(R.string.crp_empty_name)
                return
            }

            b.crpDescSubgTv.text = filteredTags[position].subg
        }

        private fun displayHeaderIfNeeded(position: Int, groupName: String) {
            if (position == 0 || filteredTags[position - 1].group != filteredTags[position].group) {
                b.crpHeadingTv.visibility = View.VISIBLE
                b.crpHeadingTv.text = groupName
                return
            }

            b.crpHeadingTv.visibility = View.GONE
        }
    }

}
