/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import com.celzero.bravedns.databinding.SpinnerListItemBinding
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode

class CustomSpinnerAdapter(val context: Context, var dataSource: List<String>) : BaseAdapter() {

    private val inflater: LayoutInflater = context.getSystemService(
        Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {

        val view: View
        val vh: ItemHolder
        if (convertView == null) {
            val itemBinding = SpinnerListItemBinding.inflate(inflater, parent, false)
            view = itemBinding.root
            vh = ItemHolder(itemBinding)
            view.tag = vh
        } else {
            view = convertView
            vh = view.tag as ItemHolder
        }
        vh.label.text = dataSource[position]
        if (position == (appMode?.getDNSType()?.minus(1))) {
            vh.img.visibility = View.VISIBLE
        } else {
            vh.img.visibility = View.INVISIBLE
        }

        return view
    }

    override fun getItem(position: Int): Any {
        return dataSource[position]
    }

    override fun getCount(): Int {
        return dataSource.size
    }

    override fun getItemId(position: Int): Long {
        return position.toLong()
    }

    private class ItemHolder(b: SpinnerListItemBinding) {
        val label: TextView = b.spinnerAdapterText
        val img: ImageView = b.spinnerAdapterImage
    }

}