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
package com.celzero.bravedns.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityWelcomeBinding
import com.celzero.bravedns.service.PersistentState
import org.koin.android.ext.android.inject

class WelcomeActivity : AppCompatActivity(R.layout.activity_welcome) {
    private val b by viewBinding(ActivityWelcomeBinding::bind)
    private lateinit var dots: Array<TextView?>
    internal val layout: IntArray = intArrayOf(R.layout.welcome_slide1, R.layout.welcome_slide2)

    private lateinit var myPagerAdapter: PagerAdapter

    private val persistentState by inject<PersistentState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!persistentState.firstTimeLaunch) {
            launchHomeScreen()
        }

        addBottomDots(0)
        changeStatusBarColor()

        myPagerAdapter = MyPagerAdapter()

        b.viewPager.adapter = myPagerAdapter

        b.btnSkip.setOnClickListener {
            launchHomeScreen()
        }

        b.btnNext.setOnClickListener {
            val currentItem = getItem(1)
            if (currentItem < layout.size) b.viewPager.currentItem = currentItem
            else launchHomeScreen()
        }

        b.viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {
                if (ViewPager.SCROLLBAR_POSITION_RIGHT == state + 1) {
                    if (getItem(1) == layout.size) launchHomeScreen()
                }
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {

            }

            override fun onPageSelected(position: Int) {
                addBottomDots(position)
            }

        })

    }

    private fun changeStatusBarColor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val window: Window = window
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.statusBarColor = Color.TRANSPARENT
        }
    }

    private fun addBottomDots(currentPage: Int) {
        dots = arrayOfNulls(layout.size)

        val colorActive = resources.getIntArray(R.array.array_dot_active)
        val colorInActive = resources.getIntArray(R.array.array_dot_inactive)

        b.layoutDots.removeAllViews()
        for (i in dots.indices) {
            dots[i] = TextView(this)
            dots[i]?.text = Html.fromHtml("&#8226;")
            dots[i]?.textSize = 35F
            dots[i]?.setTextColor(colorInActive[currentPage])
            b.layoutDots.addView(dots[i])
        }
        if (dots.isNotEmpty()) {
            dots[currentPage]?.setTextColor(colorActive[currentPage])
        }
    }

    private fun getItem(i: Int): Int {
        return b.viewPager.currentItem + i
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
            return layout.size
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            layoutInflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            val view: View = layoutInflater.inflate(layout[position], container, false)
            container.addView(view)
            return view
        }


        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {

        }

    }


}


