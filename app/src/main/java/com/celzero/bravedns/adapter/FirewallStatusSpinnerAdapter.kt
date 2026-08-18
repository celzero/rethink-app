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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.appcompat.widget.AppCompatImageView
import androidx.appcompat.widget.AppCompatTextView
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.SpinnerItemFirewallStatusBinding

class FirewallStatusSpinnerAdapter(val context: Context, private val spinnerLabels: Array<String>) :
    BaseAdapter() {

    override fun getCount(): Int {
        return spinnerLabels.size
    }

    override fun getItem(position: Int): String {
        return spinnerLabels[position]
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val itemBinding =
            convertView
                ?: SpinnerItemFirewallStatusBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false
                    )
                    .root

        setItem(itemBinding, getItem(position))
        return itemBinding
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val itemBinding =
            convertView
                ?: SpinnerItemFirewallStatusBinding.inflate(
                        LayoutInflater.from(parent.context),
                        parent,
                        false
                    )
                    .root

        setItem(itemBinding, getItem(position))
        return itemBinding
    }

    private fun setItem(view: View, status: String?) {
        if (status == null) return

        val tv = view.findViewById<AppCompatTextView>(R.id.spinner_text)
        val iv = view.findViewById<AppCompatImageView>(R.id.spinner_icon)
        tv.text = status
        // do not show down arrow on drop down
        iv.visibility = View.INVISIBLE
    }
}
