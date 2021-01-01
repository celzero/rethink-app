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
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.FragmentAboutBinding
import com.celzero.bravedns.service.AppUpdater
import org.koin.android.ext.android.inject


class AboutFragment : Fragment(), View.OnClickListener {
    private var _binding: FragmentAboutBinding? = null
    private val b get() = _binding!!

    private val appUpdater by inject<AppUpdater>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        initView()
        return b.root
    }

    private fun initView() {
        //Log.d(LOG_TAG,"Download source:"+ Utilities.verifyInstallerId(requireContext()))

        b.aboutWebsite.setOnClickListener(this)
        b.aboutTwitter.setOnClickListener(this)
        b.aboutGithub.setOnClickListener(this)
        b.aboutBlog.setOnClickListener(this)
        b.aboutMail.setOnClickListener(this)
        b.aboutTelegram.setOnClickListener(this)
        b.aboutFaq.setOnClickListener(this)
        b.mozillaImg.setOnClickListener(this)
        b.aboutAppUpdate.setOnClickListener(this)
        b.aboutWhatsNew.setOnClickListener(this)

        try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            val version = pInfo.versionName
            b.aboutAppVersion.text = "v$version"
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }

    }

    override fun onClick(view: View?) {
        when (view) {
            b.aboutTelegram -> {
                val intent = Intent(Intent.ACTION_VIEW, "http://www.telegram.me/rethinkdns".toUri())
                startActivity(intent)
            }
            b.aboutBlog -> {
                val intent = Intent(Intent.ACTION_VIEW, "https://blog.rethinkdns.com/".toUri())
                startActivity(intent)
            }
            b.aboutFaq -> {
                val intent = Intent(Intent.ACTION_VIEW, "https://www.bravedns.com/faq".toUri())
                startActivity(intent)
            }
            b.aboutGithub -> {
                val intent = Intent(Intent.ACTION_VIEW, "https://github.com/celzero/rethink-app".toUri())
                startActivity(intent)
            }
            b.aboutMail -> {
                val intent = Intent(Intent.ACTION_VIEW, ("mailto:" + "hello@celzero.com").toUri())
                intent.putExtra(Intent.EXTRA_SUBJECT, "[RethinkDNS]:")
                startActivity(intent)
            }
            b.aboutTwitter -> {
                val intent = Intent(Intent.ACTION_VIEW, "https://twitter.com/rethinkdns".toUri())
                startActivity(intent)
            }
            b.aboutWebsite -> {
                val intent = Intent(Intent.ACTION_VIEW, "https://www.bravedns.com/".toUri())
                startActivity(intent)
            }
            b.mozillaImg -> {
                val intent = Intent(Intent.ACTION_VIEW, "https://builders.mozilla.community/alumni.html".toUri())
                startActivity(intent)
            }
            b.aboutAppUpdate -> {
                (requireContext() as HomeScreenActivity).checkForUpdate(true)
            }
            b.aboutWhatsNew -> {
                showNewFeaturesDialog()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun showNewFeaturesDialog() {
        val inflater: LayoutInflater = LayoutInflater.from(requireContext())
        val view: View = inflater.inflate(R.layout.dialog_whatsnew, null)
        //val builder: android.app.AlertDialog.Builder = AlertDialog.Builder(this)
        val builder = AlertDialog.Builder(requireContext())
        builder.setView(view).setTitle(getString(R.string.whats_dialog_title))

        builder.setPositiveButton("Let\'s Go") { dialogInterface, _ ->
            dialogInterface.dismiss()
        }

        builder.setCancelable(true)
        builder.create().show()

    }

}
