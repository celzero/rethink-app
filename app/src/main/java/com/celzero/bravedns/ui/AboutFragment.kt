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

import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.BuildConfig
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.DialogWhatsnewBinding
import com.celzero.bravedns.databinding.FragmentAboutBinding
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.DOWNLOAD_SOURCE_PLAY_STORE
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_UI
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.openVpnProfile


class AboutFragment : Fragment(R.layout.fragment_about), View.OnClickListener {
    private val b by viewBinding(FragmentAboutBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun initView() {

        if (BuildConfig.FLAVOR == Constants.FLAVOR_FDROID) {
            b.aboutAppUpdate.visibility = View.GONE
        }

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
        b.aboutAppInfo.setOnClickListener(this)
        b.aboutAppNotification.setOnClickListener(this)
        b.aboutVpnProfile.setOnClickListener(this)

        try {
            val version = getVersionName()
            b.aboutAppVersion.text = getString(R.string.about_version_install_source, version,
                                               getDownloadSource().toString())
            b.aboutWhatsNew.text = getString(R.string.about_whats_new,
                                             getString(R.string.about_version, version))
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(LOG_TAG_UI, "package name not found: ${e.message}", e)
        }
    }

    private fun getVersionName(): String {
        val pInfo: PackageInfo? = Utilities.getPackageMetadata(requireContext().packageManager,
                                                               requireContext().packageName)
        return pInfo?.versionName ?: ""
    }

    private fun getDownloadSource(): Int {
        return when (BuildConfig.FLAVOR) {
            Constants.FLAVOR_PLAY -> {
                DOWNLOAD_SOURCE_PLAY_STORE
            }
            Constants.FLAVOR_FDROID -> {
                Constants.DOWNLOAD_SOURCE_FDROID
            }
            else -> {
                Constants.DOWNLOAD_SOURCE_WEBSITE
            }
        }
    }

    override fun onClick(view: View?) {
        when (view) {
            b.aboutTelegram -> {
                val intent = Intent(Intent.ACTION_VIEW,
                                    getString(R.string.about_telegram_link).toUri())
                startActivity(intent)
            }
            b.aboutBlog -> {
                val intent = Intent(Intent.ACTION_VIEW, getString(R.string.about_docs_link).toUri())
                startActivity(intent)
            }
            b.aboutFaq -> {
                val intent = Intent(Intent.ACTION_VIEW, getString(R.string.about_faq_link).toUri())
                startActivity(intent)
            }
            b.aboutGithub -> {
                val intent = Intent(Intent.ACTION_VIEW,
                                    getString(R.string.about_github_link).toUri())
                startActivity(intent)
            }
            b.aboutMail -> {
                val intent = Intent(Intent.ACTION_VIEW, (getString(R.string.about_mail_to)).toUri())
                intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_mail_subject))
                startActivity(intent)
            }
            b.aboutTwitter -> {
                val intent = Intent(Intent.ACTION_VIEW,
                                    getString(R.string.about_twitter_handle).toUri())
                startActivity(intent)
            }
            b.aboutWebsite -> {
                val intent = Intent(Intent.ACTION_VIEW,
                                    getString(R.string.about_website_link).toUri())
                startActivity(intent)
            }
            b.mozillaImg -> {
                val intent = Intent(Intent.ACTION_VIEW,
                                    getString(R.string.about_mozilla_alumni_link).toUri())
                startActivity(intent)
            }
            b.aboutAppUpdate -> {
                (requireContext() as HomeScreenActivity).checkForUpdate(
                    AppUpdater.UserPresent.INTERACTIVE)
            }
            b.aboutWhatsNew -> {
                showNewFeaturesDialog()
            }
            b.aboutAppInfo -> {
                openAppInfo()
            }
            b.aboutVpnProfile -> {
                openVpnProfile(requireContext())
            }
            b.aboutAppNotification -> {
                openNotificationSettings()
            }
        }
    }

    private fun openAppInfo() {
        val packageName = requireContext().packageName
        try {
            val intent = Intent(ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Utilities.showToastUiCentered(requireContext(), getString(R.string.app_info_error),
                                          Toast.LENGTH_SHORT)
            Log.w(LOG_TAG_UI, "activity not found ${e.message}", e)
        }
    }

    private fun openNotificationSettings() {
        val packageName = requireContext().packageName
        try {
            val intent = Intent()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                intent.action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            } else {
                intent.action = ACTION_APPLICATION_DETAILS_SETTINGS
                intent.addCategory(Intent.CATEGORY_DEFAULT)
                intent.data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Utilities.showToastUiCentered(requireContext(), getString(R.string.vpn_profile_error),
                                          Toast.LENGTH_SHORT)
            Log.w(LOG_TAG_UI, "activity not found ${e.message}", e)
        }
    }

    private fun showNewFeaturesDialog() {
        val binding = DialogWhatsnewBinding.inflate(LayoutInflater.from(requireContext()), null,
                                                    false)
        AlertDialog.Builder(requireContext()).setView(binding.root).setTitle(
            getString(R.string.whats_dialog_title)).setPositiveButton(
            getString(R.string.about_dialog_positive_button)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }.setNeutralButton(
            getString(R.string.about_dialog_neutral_button)) { _: DialogInterface, _: Int ->
            val intent = Intent(Intent.ACTION_VIEW, (getString(R.string.about_mail_to)).toUri())
            intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_mail_subject))
            startActivity(intent)
        }.setCancelable(true).create().show()
    }

}
