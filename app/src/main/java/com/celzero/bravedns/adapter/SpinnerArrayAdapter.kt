package com.celzero.bravedns.adapter

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import com.celzero.bravedns.R
import com.celzero.bravedns.R.color.colorAccent
import com.celzero.bravedns.data.BraveMode
import kotlinx.android.synthetic.main.spinner_custom_list.view.*
import java.util.ArrayList

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
            view.modeText.text = braveMode!!.modeName
            //view.background = context.resources.getDrawable(R.color.colorGreen_900)

            return view
        }

}