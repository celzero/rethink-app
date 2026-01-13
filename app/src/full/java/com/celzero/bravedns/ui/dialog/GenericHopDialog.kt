/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.dialog

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.adapter.GenericHopAdapter
import com.celzero.bravedns.adapter.HopItem
import com.celzero.bravedns.databinding.DialogWgHopBinding

/**
 * Generic dialog for hopping between different proxy types
 * Supports both WireGuard configs and RPN proxies through HopItem sealed class
 */
open class GenericHopDialog(
    private val activity: Activity,
    themeID: Int,
    private val srcId: Int,
    private val hopItems: List<HopItem>,
    private val selectedId: Int,
    private val onHopChanged: ((Int) -> Unit)? = null
) : Dialog(activity, themeID) {

    private lateinit var b: DialogWgHopBinding
    private var mLayoutManager: RecyclerView.LayoutManager? = null
    private lateinit var adapter: GenericHopAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        b = DialogWgHopBinding.inflate(layoutInflater)
        setContentView(b.root)
        initView()
    }

    private fun initView() {
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        mLayoutManager = LinearLayoutManager(activity)

        adapter = GenericHopAdapter(
            activity,
            activity as LifecycleOwner,
            srcId,
            hopItems,
            selectedId,
            onHopChanged
        )

        b.wgHopRecyclerView.layoutManager = mLayoutManager
        b.wgHopRecyclerView.adapter = adapter

        b.wgHopDialogOkButton.setOnClickListener {
            this.dismiss()
        }
    }
}

