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
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.DialogWhatsnewBinding
import com.celzero.bravedns.databinding.FragmentAboutBinding


class AboutFragment : Fragment(R.layout.fragment_about), View.OnClickListener {
    private val b by viewBinding(FragmentAboutBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun initView() {
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
            b.aboutAppVersion.text = getString(R.string.About_version, version)
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }

    }

    override fun onClick(view: View?) {
        when (view) {
            b.aboutTelegram -> {
                val intent = Intent(Intent.ACTION_VIEW, getString(R.string.about_telegram_link).toUri())
                startActivity(intent)
            }
            b.aboutBlog -> {
                val intent = Intent(Intent.ACTION_VIEW, getString(R.string.about_blog_link).toUri())
                startActivity(intent)
            }
            b.aboutFaq -> {
                val intent = Intent(Intent.ACTION_VIEW, getString(R.string.about_faq_link).toUri())
                startActivity(intent)
            }
            b.aboutGithub -> {
                val intent = Intent(Intent.ACTION_VIEW, getString(R.string.about_github_link).toUri())
                startActivity(intent)
            }
            b.aboutMail -> {
                val intent = Intent(Intent.ACTION_VIEW, (getString(R.string.about_mail_to)).toUri())
                intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_mail_subject))
                startActivity(intent)
            }
            b.aboutTwitter -> {
                val intent = Intent(Intent.ACTION_VIEW, getString(R.string.about_twitter_handle).toUri())
                startActivity(intent)
            }
            b.aboutWebsite -> {
                val intent = Intent(Intent.ACTION_VIEW, getString(R.string.about_website_link).toUri())
                startActivity(intent)
            }
            b.mozillaImg -> {
                val intent = Intent(Intent.ACTION_VIEW, getString(R.string.about_mozilla_alumni_link).toUri())
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

    private fun showNewFeaturesDialog() {
        val binding = DialogWhatsnewBinding.inflate(LayoutInflater.from(requireContext()), null, false)
        AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .setTitle(getString(R.string.whats_dialog_title))
            .setPositiveButton(getString(R.string.about_dialog_positive_button)) { dialogInterface, _ ->
                dialogInterface.dismiss()
            }.setCancelable(true).create().show()
    }

}
