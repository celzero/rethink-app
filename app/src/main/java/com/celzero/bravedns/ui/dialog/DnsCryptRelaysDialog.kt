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
package com.celzero.bravedns.ui.dialog

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.databinding.DialogDnscryptRelaysBinding

class DnsCryptRelaysDialog(
    private var activity: Activity,
    internal var adapter: RecyclerView.Adapter<*>,
    themeID: Int
) : Dialog(activity, themeID) {

    private lateinit var b: DialogDnscryptRelaysBinding

    private var mLayoutManager: RecyclerView.LayoutManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        b = DialogDnscryptRelaysBinding.inflate(layoutInflater)
        setContentView(b.root)
        initView()
    }

    private fun initView() {
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        mLayoutManager = LinearLayoutManager(activity)

        b.recyclerViewDialog.layoutManager = mLayoutManager
        b.recyclerViewDialog.adapter = adapter

        b.customDialogOkButton.setOnClickListener { this.dismiss() }
    }
}
