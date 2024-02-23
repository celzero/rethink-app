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
package com.celzero.bravedns.ui.activity

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityWelcomeBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.Themes
import org.koin.android.ext.android.inject

class WelcomeActivity : AppCompatActivity(R.layout.activity_welcome) {
    private val b by viewBinding(ActivityWelcomeBinding::bind)
    private lateinit var dots: Array<TextView?>
    internal val layout: IntArray = intArrayOf(R.layout.welcome_slide2, R.layout.welcome_slide1)

    private lateinit var myPagerAdapter: PagerAdapter
    private val persistentState by inject<PersistentState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)

        addBottomDots(0)
        changeStatusBarColor()

        myPagerAdapter = MyPagerAdapter()

        b.viewPager.adapter = myPagerAdapter

        b.btnSkip.setOnClickListener { launchHomeScreen() }

        b.btnNext.setOnClickListener {
            val currentItem = getItem()
            // size and count() are almost always equivalent. However some lazy Seq cannot know
            // their size until being fulfilled so size will be undefined for those cases and
            // calling count() will fulfill the lazy Seq to determine its size.
            if (currentItem + 1 >= layout.count()) {
                launchHomeScreen()
            } else {
                b.viewPager.currentItem = currentItem + 1
            }
        }

        b.viewPager.addOnPageChangeListener(
            object : ViewPager.OnPageChangeListener {
                override fun onPageScrollStateChanged(state: Int) {}

                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    positionOffsetPixels: Int
                ) {}

                override fun onPageSelected(position: Int) {
                    addBottomDots(position)
                    if (position >= layout.count() - 1) {
                        b.btnNext.text = getString(R.string.finish)
                        b.btnNext.visibility = View.VISIBLE
                        b.btnSkip.visibility = View.INVISIBLE
                    } else {
                        b.btnSkip.visibility = View.VISIBLE
                        b.btnNext.visibility = View.INVISIBLE
                    }
                }
            }
        )

        // Note that you shouldn't override the onBackPressed() as that will make the
        // onBackPressedDispatcher callback not to fire
        onBackPressedDispatcher.addCallback(
            this /* lifecycle owner */,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Back is pressed...
                    return
                }
            }
        )
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun changeStatusBarColor() {
        val window: Window = window
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
    }

    private fun addBottomDots(currentPage: Int) {
        dots = arrayOfNulls(layout.count())

        val colorActive = resources.getIntArray(R.array.array_dot_active)
        val colorInActive = resources.getIntArray(R.array.array_dot_inactive)

        b.layoutDots.removeAllViews()
        for (i in dots.indices) {
            dots[i] = TextView(this)
            dots[i]?.layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            dots[i]?.text = HtmlCompat.fromHtml("&#8226;", HtmlCompat.FROM_HTML_MODE_LEGACY)
            dots[i]?.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30F)
            dots[i]?.setTextColor(colorInActive[currentPage])
            b.layoutDots.addView(dots[i])
        }
        if (dots.isNotEmpty()) {
            dots[currentPage]?.setTextColor(colorActive[currentPage])
        }
    }

    private fun getItem(): Int {
        return b.viewPager.currentItem
    }

    private fun launchHomeScreen() {
        persistentState.firstTimeLaunch = false
        startActivity(Intent(this, HomeScreenActivity::class.java))
        finish()
    }

    inner class MyPagerAdapter : PagerAdapter() {
        private lateinit var layoutInflater: LayoutInflater

        override fun isViewFromObject(view: View, `object`: Any): Boolean {
            return view == `object`
        }

        override fun getCount(): Int {
            return layout.count()
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            layoutInflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val view: View = layoutInflater.inflate(layout[position], container, false)
            container.addView(view)
            return view
        }

        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {}
    }
}
