package com.celzero.bravedns.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.opengl.Visibility
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.TextViewCompat
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import androidx.viewpager.widget.ViewPager.OnPageChangeListener
import com.celzero.bravedns.R
import com.celzero.bravedns.util.PrefManager




class WelcomeActivity  : AppCompatActivity() {

    private lateinit var prefManager : PrefManager
    private lateinit var viewPager : ViewPager
    private lateinit var dotsLayout: LinearLayout
    private lateinit var dots : Array<TextView?>
    internal val layout : IntArray = intArrayOf(R.layout.welcome_slide1,R.layout.welcome_slide2,R.layout.welcome_slide3,R.layout.welcome_slide4)

    private lateinit var buttonNext : TextView
    private lateinit var buttonSkip : TextView
    private lateinit var myPagerAdapter : PagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        this.prefManager = PrefManager(this)
        if(!prefManager.isFirstTimeLaunch()){
            launchHomeScreen()
        }

        if (Build.VERSION.SDK_INT >= 21) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        setContentView(R.layout.activity_welcome)

        viewPager = findViewById(R.id.view_pager)
        dotsLayout =  findViewById(R.id.layoutDots)
        buttonSkip = findViewById(R.id.btn_skip)
        buttonNext =  findViewById(R.id.btn_next)

        addBottomDots(0)
        changeStatusBarColor()

        myPagerAdapter = MyPagerAdapter()

        viewPager.adapter = myPagerAdapter

        buttonSkip.setOnClickListener {
            launchHomeScreen()
        }

        buttonNext.setOnClickListener {
            var currentItem = getItem(1)
            Log.w("TAG","Value:"+currentItem + " :: Layout size: "+layout.size)
            if(currentItem < layout.size)
                viewPager.setCurrentItem(currentItem)
            else
                launchHomeScreen()
        }

        viewPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {
                if(ViewPager.SCROLL_STATE_DRAGGING == state){
                    if(getItem(1) == layout.size)
                        launchHomeScreen()
                }
            }

            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {

            }

            override fun onPageSelected(position: Int) {
                addBottomDots(position)

                if(position == layout.size - 1){
                    buttonNext.setText(R.string.start)
                    buttonSkip.visibility=View.GONE
                    buttonNext.visibility = View.VISIBLE

                }else{
                    buttonNext.setText(R.string.next)
                    //buttonSkip.visibility  = View.VISIBLE
                }
            }

        })

    }

    private fun changeStatusBarColor() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val window: Window = window
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            window.setStatusBarColor(Color.TRANSPARENT)
        }
    }

    private fun addBottomDots(currentPage: Int) {
        dots = arrayOfNulls(layout.size)

        var colorActive  = (resources.getIntArray(R.array.array_dot_active))
        var colorInActive = resources.getIntArray(R.array.array_dot_inactive)

        dotsLayout.removeAllViews()
        for(i in 0..dots.size-1){
            dots[i] = TextView(this)
            dots[i]?.setText(Html.fromHtml("&#8226;"))
            dots[i]?.setTextSize(35F)
            dots[i]?.setTextColor(colorInActive[currentPage])
            dotsLayout.addView(dots[i])
        }
        if(dots.size > 0){
            dots[currentPage]?.setTextColor(colorActive[currentPage])
        }
    }

    private fun getItem(i : Int): Int{
        return viewPager.currentItem+i
    }

    private fun launchHomeScreen() {
        this.prefManager.setFirstTimeLaunch(false)
        startActivity(Intent(this, HomeScreenActivity::class.java))
        finish()
    }


    inner class MyPagerAdapter : PagerAdapter() {
        private lateinit var layoutInflater : LayoutInflater

        override fun isViewFromObject(view: View, `object`: Any): Boolean {
            return view == `object`
        }

        override fun getCount(): Int {
            return layout.size
        }

        override fun instantiateItem(container: ViewGroup, position: Int): Any {

            layoutInflater = getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

            val view: View =
                layoutInflater.inflate(layout.get(position), container, false)
            container.addView(view)
            return view
        }


        override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
            //container.removeViewAt(position)
            //super.destroyItem(container, position, `object`)
        }

    }


}


