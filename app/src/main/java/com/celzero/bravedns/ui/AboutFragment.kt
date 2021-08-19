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
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
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
import com.celzero.bravedns.util.Constants.Companion.FILE_PROVIDER_NAME
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_UI
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.isAtleastR
import com.celzero.bravedns.util.Utilities.Companion.isFdroidFlavour
import com.celzero.bravedns.util.Utilities.Companion.openVpnProfile
import com.celzero.bravedns.util.Utilities.Companion.sendEmailIntent
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream


class AboutFragment : Fragment(R.layout.fragment_about), View.OnClickListener {
    private val b by viewBinding(FragmentAboutBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun initView() {

        if (isFdroidFlavour()) {
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
        b.aboutCrashLog.setOnClickListener(this)

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
            b.aboutCrashLog -> {
                if (isAtleastR()) {
                    promptCrashLogAction()
                } else {
                    showNoLogDialog()
                }
            }
            b.aboutMail -> {
                sendEmailIntent(requireContext())
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

    private fun showNoLogDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.about_bug_no_log_dialog_title)
        builder.setMessage(R.string.about_bug_no_log_dialog_message)
        builder.setPositiveButton(
            getString(R.string.about_bug_no_log_dialog_positive_btn)) { _, _ ->
            sendEmailIntent(requireContext())
        }
        builder.setNegativeButton(
            getString(R.string.about_bug_no_log_dialog_negative_btn)) { dialog, _ ->
            dialog.dismiss()
        }
        builder.create().show()

    }

    private fun openAppInfo() {
        val packageName = requireContext().packageName
        try {
            val intent = Intent(ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            showToastUiCentered(requireContext(), getString(R.string.app_info_error),
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
            showToastUiCentered(requireContext(), getString(R.string.vpn_profile_error),
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
            sendEmailIntent(requireContext())
        }.setCancelable(true).create().show()
    }

    // ref: https://developer.android.com/guide/components/intents-filters
    private fun emailBugReport() {
        // ACTION_SEND_MULTIPLE is to send multiple text files in email.
        val emailIntent = Intent(Intent.ACTION_SEND_MULTIPLE)
        emailIntent.type = "plain/text"
        emailIntent.putExtra(Intent.EXTRA_EMAIL, arrayOf(getString(R.string.about_mail_to)))
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_mail_bugreport_subject))
        emailIntent.putExtra(Intent.EXTRA_TEXT, getString(R.string.about_mail_bugreport_text))
        // Get the bug_report.txt file
        val file0 = File(
            requireContext().filesDir.canonicalPath + File.separator + Constants.BUG_REPORT_FILE)
        // Get the bug_report_1.txt file
        val file1 = File(
            requireContext().filesDir.canonicalPath + File.separator + Constants.PREV_REPORT_FILE)
        // list of files added as attachments
        val uris: ArrayList<Uri> = ArrayList()
        getFileUri(file0)?.let { uris.add(it) }
        getFileUri(file1)?.let { uris.add(it) }
        emailIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        emailIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        emailIntent.flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        startActivity(
            Intent.createChooser(emailIntent, getString(R.string.about_mail_bugreport_share_title)))
    }

    private fun getFileUri(file: File): Uri? {
        if (isFileAvailable(file)) {
            return FileProvider.getUriForFile(requireContext().applicationContext,
                                              FILE_PROVIDER_NAME, file)

        }
        return null
    }

    private fun isFileAvailable(file: File): Boolean {
        return file.isFile && file.exists()
    }

    private fun promptCrashLogAction() {
        val builder: AlertDialog.Builder = AlertDialog.Builder(requireContext())
        builder.setTitle(getString(R.string.about_bug_report))
        // No layouts added in the alert dialog.
        // Created scroll view and added textview to it.
        val scrollView = ScrollView(requireContext())
        val textView = TextView(requireContext())
        textView.setPadding(30, 30, 30, 30)
        textView.typeface = Typeface.MONOSPACE
        scrollView.addView(textView)
        builder.setView(scrollView)
        val file0 = File(
            requireContext().filesDir.canonicalPath + File.separator + Constants.BUG_REPORT_FILE)
        if (!isFileAvailable(file0)) {
            showToastUiCentered(requireContext(), getString(R.string.log_file_not_available),
                                Toast.LENGTH_SHORT)
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            var inputString: String
            withContext(Dispatchers.IO) {
                val inputStream: InputStream = file0.inputStream()
                inputString = inputStream.bufferedReader().use { it.readText() }
            }
            textView.text = inputString
        }

        val width = (resources.displayMetrics.widthPixels * 0.75).toInt()
        val height = (resources.displayMetrics.heightPixels * 0.75).toInt()

        builder.setPositiveButton(
            getString(R.string.about_bug_report_dialog_positive_btn)) { _, _ ->
            emailBugReport()
        }
        builder.setNegativeButton(
            getString(R.string.about_bug_report_dialog_negative_btn)) { dialog, _ ->
            dialog.dismiss()
        }

        val alert: AlertDialog = builder.create()
        alert.window?.setLayout(width, height)
        alert.show()
    }

}
