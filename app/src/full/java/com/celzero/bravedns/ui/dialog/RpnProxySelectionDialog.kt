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
package com.rethinkdns.retrixed.ui.dialog

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rethinkdns.retrixed.databinding.DialogRpnWinServersBinding

class RpnProxySelectionDialog(private var activity: Activity,
    internal var adapter: RecyclerView.Adapter<*>,
    themeID: Int) : Dialog(activity, themeID) {

    private lateinit var b: DialogRpnWinServersBinding

    private lateinit var animation: Animation

    companion object {
        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f

        private const val REFRESH_TIMEOUT: Long = 4000
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        b = DialogRpnWinServersBinding.inflate(layoutInflater)
        setContentView(b.root)
        setCancelable(false)
        addAnimation()
        initViews()
        initClickListeners()
    }

    private fun addAnimation() {
        animation = RotateAnimation(
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

    private fun initViews() {
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        val layoutManager = LinearLayoutManager(activity)
        b.rwsRecyclerView.layoutManager = layoutManager
        b.rwsRecyclerView.adapter = adapter
    }

    fun initClickListeners() {
        b.rwsDialogOkButton.setOnClickListener {
            dismiss()
        }
    }
}
