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


import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.net.toUri
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ApplicationManagerApk
import com.celzero.bravedns.animation.ViewAnimation
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.databinding.ActivityApplicationManagerBinding
import org.koin.android.ext.android.inject


class ApplicationManagerActivity : AppCompatActivity(R.layout.activity_application_manager),
                                   SearchView.OnQueryTextListener {
    private val b by viewBinding(ActivityApplicationManagerBinding::bind)

    private val apkList = ArrayList<ApplicationManagerApk>()

    private var isRotate: Boolean = false

    private val appInfoRepository by inject<AppInfoRepository>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initView()
    }

    private fun initView() {
        b.applicationManagerRecyclerView.layoutManager = LinearLayoutManager(this)
        b.amSearch.setOnQueryTextListener(this)

        ViewAnimation.init(b.amFabUninstallIcon)
        ViewAnimation.init(b.amFabAppinfoIcon)

        ApplicationManagerApk.cleatList()

        b.amFabAddIcon.setOnClickListener {
            isRotate = ViewAnimation.rotateFab(it, !isRotate)
            if (isRotate) {
                ViewAnimation.showIn(b.amFabUninstallIcon)
                ViewAnimation.showIn(b.amFabAppinfoIcon)
            } else {
                ViewAnimation.showOut(b.amFabUninstallIcon)
                ViewAnimation.showOut(b.amFabAppinfoIcon)
            }
        }

        b.amFabAppinfoIcon.setOnClickListener {
            val list = ApplicationManagerApk.getAddedList()
            if (list.size >= 1) {
                list[list.size - 1].packageName?.let { it1 -> appInfoForPackage(it1) }
            }
        }
    }


    private fun appInfoForPackage(packageName: String) {
        val activityManager: ActivityManager = getSystemService(
            Activity.ACTIVITY_SERVICE) as ActivityManager
        activityManager.killBackgroundProcesses(packageName)

        try {
            //Open the specific App Info page:
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = "package:$packageName".toUri()
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            //Open the generic Apps page:
            val intent = Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
            startActivity(intent)
        }
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        return true
    }
}
