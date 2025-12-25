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

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.Spanned
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityWelcomeBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import org.koin.android.ext.android.inject

class WelcomeActivity : AppCompatActivity(R.layout.activity_welcome) {

    private val b by viewBinding(ActivityWelcomeBinding::bind)
    private lateinit var dots: Array<androidx.appcompat.widget.AppCompatTextView?>
    private val layouts: IntArray = intArrayOf(
        R.layout.welcome_slide1,
        R.layout.welcome_slide2,
        R.layout.welcome_slide3,
        R.layout.welcome_slide4
    )
    private var myPagerAdapter: MyPagerAdapter? = null
    private val persistentState by inject<PersistentState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        // Add bottom dots
        addBottomDots(0)

        // Change status bar color
        changeStatusBarColor()

        // Initialize adapter
        myPagerAdapter = MyPagerAdapter()

        // Set up ViewPager
        b.viewPager.adapter = myPagerAdapter
        b.viewPager.addOnPageChangeListener(
            object : ViewPager.OnPageChangeListener {
                override fun onPageScrollStateChanged(state: Int) {}

                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    positionOffsetPixels: Int
                ) {
                    // if the scroll is after the layout count then finish the activity
                    if (position >= layouts.count() - 1 && positionOffset > 0) {
                        launchHomeScreen()
                    }
                }

                override fun onPageSelected(position: Int) {
                    addBottomDots(position)

                    // Change the next button text 'NEXT' / 'GOT IT'
                    if (position >= layouts.count() - 1) {
                        // Last page. Make button text to GOT IT
                        b.btnNext.text = getString(R.string.finish)
                        b.btnSkip.visibility = View.INVISIBLE
                    } else {
                        // Still pages are left
                        b.btnNext.text = getString(R.string.next)
                        b.btnSkip.visibility = View.VISIBLE
                    }
                }
            }
        )

        // Set up button click listeners
        b.btnSkip.setOnClickListener { launchHomeScreen() }
        b.btnNext.setOnClickListener {
            // Check if user is on last page, then go to home screen
            val currentItem = getItem()
            if (currentItem + 1 >= layouts.count()) {
                launchHomeScreen()
            } else {
                // Otherwise go to next page
                b.viewPager.currentItem = currentItem + 1
            }
        }

        // on back pressed, finish the activity and go to home screen
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    launchHomeScreen()
                }
            })
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun addBottomDots(currentPage: Int) {
        dots = arrayOfNulls(layouts.size)

        val colorActive = resources.getIntArray(R.array.array_dot_active)
        val colorInActive = resources.getIntArray(R.array.array_dot_inactive)

        b.layoutDots.removeAllViews()

        for (i in dots.indices) {
            dots[i] = androidx.appcompat.widget.AppCompatTextView(this)
            dots[i]?.text = updateHtmlEncodedText("&#8226;")
            dots[i]?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30F)
            dots[i]?.setTextColor(colorInActive[currentPage])
            b.layoutDots.addView(dots[i])
        }

        if (dots.isNotEmpty()) {
            dots[currentPage]?.setTextColor(colorActive[currentPage])
        }
    }

    fun updateHtmlEncodedText(text: String): Spanned {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
        } else {
            HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
        }
    }

    private fun getItem(): Int {
        return b.viewPager.currentItem
    }

    private fun launchHomeScreen() {
        persistentState.firstTimeLaunch = false
        val intent = Intent(this, HomeScreenActivity::class.java)
        intent.setPackage(this.packageName)
        startActivity(intent)
        finish()
    }

    @Suppress("DEPRECATION")
    private fun changeStatusBarColor() {
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
    }

    // ViewPager adapter
    inner class MyPagerAdapter : PagerAdapter() {
        override fun isViewFromObject(view: View, `object`: Any): Boolean {
            return view == `object`
        }

        override fun getCount(): Int {
            return layouts.count()
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val layoutInflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val view = layoutInflater.inflate(layouts[position], container, false)
            container.addView(view)
            return view
        }

        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
            container.removeView(`object` as View)
        }
    }
}
