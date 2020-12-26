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

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.celzero.bravedns.R


class AboutFragment : Fragment(), View.OnClickListener {

    private lateinit var websiteTxt : TextView
    private lateinit var twitterTxt : TextView
    private lateinit var githubTxt : TextView
    private lateinit var  blogTxt : TextView
    private lateinit var mailTxt : TextView
    private lateinit var telegramTxt : TextView
    private lateinit var faqTxt : TextView
    private lateinit var mozillaImg : ImageView
    private lateinit var appVersionText : TextView
    private lateinit var appUpdateTxt : TextView
    private lateinit var whatsNewTxt : TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view: View = inflater.inflate(R.layout.fragment_about, container, false)
        initView(view)
        return view
    }

    private fun initView(view: View) {
        websiteTxt = view.findViewById(R.id.about_website)
        twitterTxt = view.findViewById(R.id.about_twitter)
        githubTxt = view.findViewById(R.id.about_github)
        blogTxt = view.findViewById(R.id.about_blog)
        mailTxt = view.findViewById(R.id.about_mail)
        telegramTxt = view.findViewById(R.id.about_telegram)
        faqTxt = view.findViewById(R.id.about_faq)
        mozillaImg = view.findViewById(R.id.mozilla_img)
        appVersionText = view.findViewById(R.id.about_app_version)
        appUpdateTxt = view.findViewById(R.id.about_app_update)
        whatsNewTxt = view.findViewById(R.id.about_whats_new)

        //Log.d(LOG_TAG,"Download source:"+ Utilities.verifyInstallerId(requireContext()))


        websiteTxt.setOnClickListener(this)
        twitterTxt.setOnClickListener(this)
        githubTxt.setOnClickListener(this)
        blogTxt.setOnClickListener(this)
        mailTxt.setOnClickListener(this)
        telegramTxt.setOnClickListener(this)
        faqTxt.setOnClickListener(this)
        mozillaImg.setOnClickListener(this)
        appUpdateTxt.setOnClickListener(this)
        whatsNewTxt.setOnClickListener(this)

        try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            val version = pInfo.versionName
            appVersionText.text = "v$version"
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }

    }

    override fun onClick(view: View?) {
        when {
            view == telegramTxt -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://www.telegram.me/rethinkdns"))
                startActivity(intent)
            }
            view == blogTxt -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://blog.rethinkdns.com/"))
                startActivity(intent)
            }
            view == faqTxt -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bravedns.com/faq"))
                startActivity(intent)
            }
            view == githubTxt -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/celzero/rethink-app"))
                startActivity(intent)
            }
            view == mailTxt -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("mailto:" + "hello@celzero.com"))
                intent.putExtra(Intent.EXTRA_SUBJECT, "[RethinkDNS]:")
                startActivity(intent)
            }
            view == twitterTxt -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://twitter.com/rethinkdns"))
                startActivity(intent)
            }
            view == websiteTxt -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bravedns.com/"))
                startActivity(intent)
            }
            view == mozillaImg -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://builders.mozilla.community/alumni.html"))
                startActivity(intent)
            }
            view == appUpdateTxt ->{
                (requireContext() as HomeScreenActivity).checkForAppUpdate(true)
            }
            view == whatsNewTxt ->{
                showNewFeaturesDialog()
            }
        }
    }

    private fun showNewFeaturesDialog() {
        val inflater: LayoutInflater = LayoutInflater.from(requireContext())
        val view: View = inflater.inflate(R.layout.dialog_whatsnew, null)
        //val builder: android.app.AlertDialog.Builder = AlertDialog.Builder(this)
        val builder = AlertDialog.Builder(requireContext())
        builder.setView(view).setTitle(getString(R.string.whats_dialog_title))

        builder.setPositiveButton("Let\'s Go") { dialogInterface, which ->
            dialogInterface.dismiss()
        }

        builder.setCancelable(true)
        builder.create().show()

    }

}
