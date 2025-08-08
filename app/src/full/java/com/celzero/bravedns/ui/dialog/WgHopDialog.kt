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

import Logger
import Logger.LOG_TAG_UI
import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import androidx.recyclerview.widget.LinearLayoutManager
import com.celzero.bravedns.adapter.WgHopAdapter
import com.celzero.bravedns.databinding.DialogWgHopBinding
import com.celzero.bravedns.wireguard.Config
import org.koin.core.component.KoinComponent

class WgHopDialog(
    private var activity: Activity,
    themeID: Int,
    private val srcId: Int,
    private val hopables: List<Config>,
    private val selectedId: Int
) : Dialog(activity, themeID), KoinComponent {

    private lateinit var b: DialogWgHopBinding
    private lateinit var animation: Animation
    private lateinit var adapter: WgHopAdapter

    companion object {
        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f

        private const val TAG = "HopDlg"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        b = DialogWgHopBinding.inflate(layoutInflater)
        setContentView(b.root)
        setCancelable(false)
        addAnimation()
        init()
        setupClickListeners()
    }

    private fun addAnimation() {
        animation =
            RotateAnimation(
                ANIMATION_START_DEGREE,
                ANIMATION_END_DEGREE,
                Animation.RELATIVE_TO_SELF,
                ANIMATION_PIVOT_VALUE,
                Animation.RELATIVE_TO_SELF,
                ANIMATION_PIVOT_VALUE
            )
        animation.repeatCount = ANIMATION_REPEAT_COUNT
        animation.duration = ANIMATION_DURATION
    }

    private fun init() {
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        Logger.v(LOG_TAG_UI, "$TAG; init called")

        val layoutManager = LinearLayoutManager(activity)
        b.wgHopRecyclerView.layoutManager = layoutManager
        adapter = WgHopAdapter(activity, srcId, hopables, selectedId)
        b.wgHopRecyclerView.adapter = adapter
    }

    private fun setupClickListeners() {
        b.wgHopDialogOkButton.setOnClickListener {
            Logger.d(LOG_TAG_UI, "$TAG; dismiss hop dialog")
            dismiss()
        }
    }
}
