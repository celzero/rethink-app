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
import android.widget.ArrayAdapter
import com.celzero.bravedns.R
import com.celzero.bravedns.data.BraveMode
import kotlinx.android.synthetic.main.spinner_custom_list.view.*
import java.util.*

class SpinnerArrayAdapter(context: Context,  braveModeList : ArrayList<BraveMode>) : ArrayAdapter<BraveMode>(context, 0, braveModeList) {

        override fun getView(position: Int, recycledView: View?, parent: ViewGroup): View {
            return this.createView(position, recycledView, parent)
        }

        override fun getDropDownView(position: Int, recycledView: View?, parent: ViewGroup): View {
            return this.createView(position, recycledView, parent)
        }

        private fun createView(position: Int, recycledView: View?, parent: ViewGroup): View {

            val braveMode = getItem(position)

            val view = recycledView ?: LayoutInflater.from(context).inflate(R.layout.spinner_custom_list, parent,false)
            /*if(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && position == 2){
                view.modeImage.setImageResource(braveMode!!.icon)
                view.modeText.text = braveMode.modeName
                //view.alpha = 0.5F
                //view.background = context.resources.getDrawable(R.color.colorAccent)
            }*/

            view.modeImage.setImageResource(braveMode!!.icon)
            view.modeText.text = braveMode.modeName
            //view.background = context.resources.getDrawable(R.color.colorGreen_900)

            return view
        }

}