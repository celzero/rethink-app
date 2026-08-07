/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.activity

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityWelcomeBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.BaseActivity
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import org.koin.android.ext.android.inject

class WelcomeActivity : BaseActivity(R.layout.activity_welcome) {

    private val b by viewBinding(ActivityWelcomeBinding::bind)
    private val layouts: IntArray = intArrayOf(
        R.layout.welcome_slide1,
        R.layout.welcome_slide2,
        R.layout.welcome_slide3,
        R.layout.welcome_slide4
    )
    private val persistentState by inject<PersistentState>()
    private lateinit var dots: Array<View>

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        // Edge-to-edge support
        enableEdgeToEdge()

        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val margin16 = (16 * resources.displayMetrics.density).toInt()
            b.btnNext.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom + margin16
            }
            b.btnSkip.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom + margin16
            }
            b.layoutDots.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = systemBars.bottom + margin16
            }
            insets
        }

        // Initialize adapter and dots
        b.viewPager.adapter = WelcomePagerAdapter(layouts)
        setupDots()

        b.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateDots(position)
                if (position >= layouts.count() - 1) {
                    b.btnNext.text = getString(R.string.finish)
                    b.btnSkip.visibility = View.INVISIBLE
                } else {
                    b.btnNext.text = getString(R.string.next)
                    b.btnSkip.visibility = View.VISIBLE
                }
            }
        })

        // Set up button click listeners
        b.btnSkip.setOnClickListener { launchHomeScreen() }
        b.btnNext.setOnClickListener {
            val currentItem = b.viewPager.currentItem
            if (currentItem + 1 >= layouts.count()) {
                launchHomeScreen()
            } else {
                b.viewPager.currentItem = currentItem + 1
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                launchHomeScreen()
            }
        })
    }

    private fun setupDots() {
        val density = resources.displayMetrics.density
        val dotSize = (8 * density).toInt()
        val dotMargin = (4 * density).toInt()
        dots = Array(layouts.size) { index ->
            View(this).also { dot ->
                dot.layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    setMargins(dotMargin, 0, dotMargin, 0)
                }
                dot.background = dotDrawable(active = index == 0)
                b.layoutDots.addView(dot)
            }
        }
    }

    private fun updateDots(selectedIndex: Int) {
        dots.forEachIndexed { index, dot ->
            dot.background = dotDrawable(active = index == selectedIndex)
        }
    }

    private fun dotDrawable(active: Boolean): GradientDrawable {
        val tv = TypedValue()
        theme.resolveAttribute(R.attr.svgStrokeColor, tv, true)
        val color = tv.data
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) color else ColorUtils.setAlphaComponent(color, 80))
        }
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun launchHomeScreen() {
        persistentState.firstTimeLaunch = false
        val intent = Intent(this, HomeScreenActivity::class.java)
        intent.setPackage(this.packageName)
        startActivity(intent)
        finish()
    }

    private class WelcomePagerAdapter(private val layouts: IntArray) :
        RecyclerView.Adapter<WelcomePagerAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {}

        override fun getItemCount(): Int = layouts.size

        override fun getItemViewType(position: Int): Int = layouts[position]
    }
}
