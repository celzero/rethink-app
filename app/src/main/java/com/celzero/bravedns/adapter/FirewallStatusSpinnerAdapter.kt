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
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.databinding.SpinnerItemFirewallStatusBinding

class FirewallStatusSpinnerAdapter(context: Context) :
        ArrayAdapter<FirewallManager.FirewallStatus>(context, 0, FirewallManager.FirewallStatus.values()) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {

        val itemBinding = convertView ?: SpinnerItemFirewallStatusBinding.inflate(
            LayoutInflater.from(parent.context), parent, false).root

        getItem(position)?.let { status ->
            setItem(itemBinding, status)
        }

        return itemBinding
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val itemBinding = SpinnerItemFirewallStatusBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        if (position == 0) {
            itemBinding.root.setOnClickListener {
                itemBinding.root.dispatchKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                itemBinding.root.dispatchKeyEvent(
                    KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            }
        }

        getItem(position)?.let { status ->
            setItem(itemBinding.root, status)
        }
        return itemBinding.root
    }

    override fun isEnabled(position: Int) = position != 0

    private fun setItem(view: View, status: FirewallManager.FirewallStatus) {
        val tv = view.findViewById<TextView>(R.id.spinner_text)
        tv.text = status.name
    }
}
