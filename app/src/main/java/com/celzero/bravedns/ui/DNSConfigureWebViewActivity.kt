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

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.res.Configuration
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.webkit.*
import android.webkit.WebView.RENDERER_PRIORITY_BOUND
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
import androidx.webkit.WebViewFeature
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.database.DoHEndpointRepository
import com.celzero.bravedns.databinding.ActivityFaqWebviewLayoutBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.CONFIGURE_BLOCKLIST_URL
import com.celzero.bravedns.util.Constants.Companion.CONFIGURE_BLOCKLIST_URL_PARAMETER
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_DOH
import com.celzero.bravedns.util.Constants.Companion.THEME_DARK
import com.celzero.bravedns.util.Constants.Companion.THEME_LIGHT
import com.celzero.bravedns.util.Constants.Companion.THEME_SYSTEM_DEFAULT
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DOWNLOAD
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.getCurrentTheme
import dnsx.Dnsx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koin.android.ext.android.inject
import settings.Settings
import xdns.Xdns
import java.io.File
import java.io.IOException

class DNSConfigureWebViewActivity : AppCompatActivity(R.layout.activity_faq_webview_layout) {
    private val b by viewBinding(ActivityFaqWebviewLayoutBinding::bind)
    private val maxProgressBar = 100
    private var receivedStamp: String = ""
    private var blocklistsCount: MutableLiveData<Int> = MutableLiveData()
    private var receivedIntentFrom: Int = 0

    private val doHEndpointRepository by inject<DoHEndpointRepository>()
    private val persistentState by inject<PersistentState>()
    private val appMode by inject<AppMode>()

    companion object {
        const val LOCAL = 1
        const val REMOTE = 2
        var enqueue: Long = 0
    }


    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        receivedIntentFrom = intent.getIntExtra(Constants.LOCATION_INTENT_EXTRA, 0)
        val stamp = intent.getStringExtra(Constants.STAMP_INTENT_EXTRA)

        Log.i(LOG_TAG_VPN, "Is local configure? ${isLocal()}, with stamp as $stamp")

        try {
            initWebView()
            setWebClient()

            loadUrl(constructUrl(stamp))
        } catch (e: Exception) {
            Log.e(LOG_TAG_DNS, e.message, e)
            showDialogOnError(null)
        }

        blocklistsCount.observe(this, {
            if (isLocal()) {
                persistentState.numberOfLocalBlocklists = it
            } else {
                persistentState.numberOfRemoteBlocklists = it
            }
        })

        setWebViewTheme()
    }

    private fun constructUrl(stamp: String?): String {
        if (stamp.isNullOrBlank()) {
            return CONFIGURE_BLOCKLIST_URL
        }

        return if (isLocal()) {
            val url = CONFIGURE_BLOCKLIST_URL.toHttpUrlOrNull()?.newBuilder()
            url?.addQueryParameter(CONFIGURE_BLOCKLIST_URL_PARAMETER,
                                   persistentState.blocklistDownloadTime.toString())
            url?.fragment(stamp)
            url.toString()
        } else {
            val url = CONFIGURE_BLOCKLIST_URL.toHttpUrlOrNull()?.newBuilder()
            url?.fragment(stamp)
            url.toString()
        }
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun isLocal(): Boolean {
        return (receivedIntentFrom == LOCAL)
    }

    private fun isRemote(): Boolean {
        return (receivedIntentFrom == REMOTE)
    }

    /**
     * Sets the theme for the webview based on the application theme.
     * FORCE_DARK_ON - For Dark and True black theme
     * FORCE_DARK_OFF - Light mode.
     */
    private fun setWebViewTheme() {

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            setWebViewForceDarkStrategy()
            return
        }

        if (persistentState.theme == THEME_SYSTEM_DEFAULT) {
            if (isDarkThemeOn()) {
                WebSettingsCompat.setForceDark(b.configureWebview.settings,
                                               WebSettingsCompat.FORCE_DARK_ON)
            } else {
                WebSettingsCompat.setForceDark(b.configureWebview.settings,
                                               WebSettingsCompat.FORCE_DARK_OFF)
            }
        } else if (persistentState.theme == THEME_LIGHT) {
            WebSettingsCompat.setForceDark(b.configureWebview.settings,
                                           WebSettingsCompat.FORCE_DARK_OFF)
        } else if (persistentState.theme == THEME_DARK) {
            WebSettingsCompat.setForceDark(b.configureWebview.settings,
                                           WebSettingsCompat.FORCE_DARK_ON)
        } else {
            WebSettingsCompat.setForceDark(b.configureWebview.settings,
                                           WebSettingsCompat.FORCE_DARK_ON)
        }

        setWebViewForceDarkStrategy()
    }

    private fun setWebViewForceDarkStrategy() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            WebSettingsCompat.setForceDarkStrategy(b.configureWebview.settings,
                                                   DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING)
        }
    }

    override fun onDestroy() {
        if (isLocal()) {
            updateLocalStamp()
        } else if (isRemote()) {
            updateDoHEndPoint()
        }
        b.configureWebview.removeJavascriptInterface("JSInterface")
        b.webviewPlaceholder.removeView(b.configureWebview)
        b.configureWebview.removeAllViews()
        b.configureWebview.clearHistory()
        b.configureWebview.destroy()
        super.onDestroy()
    }

    private fun updateDoHEndPoint() {
        Log.i(LOG_TAG_DNS, "Remote stamp has been updated from web view - $receivedStamp")
        if (receivedStamp.isEmpty() || receivedStamp == Constants.BRAVE_BASE_STAMP) {
            return
        }
        doHEndpointRepository.removeConnectionStatus()
        doHEndpointRepository.updateConnectionURL(receivedStamp)

        Utilities.delay(1000) {
            appMode.onNewDnsConnected(PREF_DNS_MODE_DOH, Settings.DNSModePort)
        }

        Toast.makeText(this, getString(R.string.webview_toast_configure_success),
                       Toast.LENGTH_SHORT).show()
    }

    private fun updateLocalStamp() {
        Log.i(LOG_TAG_DNS, "Local stamp has been set from webview - $receivedStamp")
        if (receivedStamp.isEmpty() || receivedStamp == Constants.BRAVE_BASE_STAMP) {
            return
        }

        val stamp = Xdns.getBlocklistStampFromURL(receivedStamp)
        persistentState.localBlocklistStamp = stamp

        setBraveDns()
        Toast.makeText(this, getString(R.string.wv_local_blocklist_toast),
                       Toast.LENGTH_SHORT).show()
    }

    private fun setBraveDns() {
        val path: String = this.filesDir.canonicalPath + File.separator + persistentState.blocklistDownloadTime

        if (appMode.getBraveDNS() != null) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val braveDNS = Dnsx.newBraveDNSLocal(path + Constants.FILE_TD_FILE,
                                                     path + Constants.FILE_RD_FILE,
                                                     path + Constants.FILE_BASIC_CONFIG,
                                                     path + Constants.FILE_TAG_NAME)

                appMode.setBraveDNS(braveDNS)
            } catch (e: Exception) {
                Log.e(LOG_TAG_DNS, "Local brave dns set exception :${e.message}", e)
                appMode.setBraveDNS(null)
            }
        }
    }

    private fun setWebClient() {
        b.configureWebview.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                b.progressBar.progress = newProgress
                if (newProgress < maxProgressBar && b.progressBar.visibility == ProgressBar.GONE) {
                    b.progressBar.visibility = ProgressBar.VISIBLE
                }
                if (newProgress == maxProgressBar) {
                    b.progressBar.visibility = ProgressBar.GONE
                }
            }

        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Check if the key event was the Back button and if there's changes
        if (keyCode != KeyEvent.KEYCODE_BACK) return false

        if (b.configureWebview.url?.contains(Constants.BRAVE_CONFIGURE_BASE_STAMP) == true) {
            b.configureWebview.goBack()
        } else if (receivedStamp.isEmpty()) {
            showDialogForExitWebView()
        } else {
            return super.onKeyDown(keyCode, event)
        }
        if (DEBUG) Log.d(LOG_TAG_DNS, "URL : ${b.configureWebview.url}")
        // If it wasn't the Back key or there's configure, exit the activity)
        return false
    }

    private fun showDialogForExitWebView() {
        val count = persistentState.numberOfLocalBlocklists
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.webview_no_stamp_change_title)
        val desc = if (receivedIntentFrom == LOCAL) {
            getString(R.string.webview_no_stamp_change_desc, count.toString())
        } else {
            getString(R.string.webview_no_stamp_change_remote)
        }
        builder.setMessage(desc)
        builder.setCancelable(true)
        builder.setPositiveButton(
            getString(R.string.webview_configure_dialog_positive)) { dialogInterface, _ ->
            dialogInterface.dismiss()
            finish()
        }

        builder.setNegativeButton(
            getString(R.string.webview_configure_dialog_negative)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }

        builder.setNeutralButton(
            getString(R.string.webview_configure_dialog_neutral)) { _: DialogInterface, _: Int ->
            b.configureWebview.reload()
        }
        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(true)
        alertDialog.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        b.configureWebview.settings.javaScriptEnabled = true
        b.configureWebview.settings.loadWithOverviewMode = true
        b.configureWebview.settings.useWideViewPort = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            b.configureWebview.setRendererPriorityPolicy(RENDERER_PRIORITY_BOUND, true)
        }
        b.configureWebview.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?,
                                            error: SslError?) {
                Log.i(LOG_TAG_DNS,
                      "SSL error received from webview. ${error?.primaryError}, ${handler?.obtainMessage()}")
                showDialogOnError(handler)
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?,
                                             errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                showDialogOnError(null)
            }

            override fun onRenderProcessGone(view: WebView?,
                                             detail: RenderProcessGoneDetail?): Boolean {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    if (!detail?.didCrash()!!) {
                        // Renderer was killed because the system ran out of memory.
                        // The app can recover gracefully by creating a new WebView instance
                        // in the foreground.
                        Log.w(LOG_TAG_DNS,
                              ("System killed the WebView rendering process Recreating..."))

                        view?.also { webView ->
                            webView.destroy()
                        }

                        // By this point, the instance variable "mWebView" is guaranteed
                        // to be null, so it's safe to reinitialize it.

                        return true // The app continues executing.
                    }
                }

                // Renderer crashed because of an internal error, such as a memory
                // access violation.
                Log.w(LOG_TAG_DNS, "The WebView rendering process crashed!")

                // In this example, the app itself crashes after detecting that the
                // renderer crashed. If you choose to handle the crash more gracefully
                // and allow your app to continue executing, you should 1) destroy the
                // current WebView instance, 2) specify logic for how the app can
                // continue executing, and 3) return "true" instead.
                return false
            }

        }

        b.configureWebview.addJavascriptInterface(JSInterface(), "JSInterface")
    }

    private fun loadUrl(pageUrl: String) {
        try {
            b.configureWebview.post {
                run {
                    b.configureWebview.loadUrl(pageUrl)
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG_DNS, "Issue while loading url: ${e.message}", e)
            showDialogOnError(null)
        }
    }


    private fun showDialogOnError(handler: SslErrorHandler?) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.webview_error_title)
        builder.setMessage(R.string.webview_error_message)
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.webview_configure_dialog_neutral)) { _, _ ->
            if (handler != null) {
                handler.proceed()
            } else {
                b.configureWebview.reload()
            }
        }

        builder.setNegativeButton(
            getString(R.string.webview_configure_dialog_negative)) { dialogInterface, _ ->
            if (handler != null) {
                handler.cancel()
            } else {
                dialogInterface.dismiss()
                finish()
            }
        }

        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(true)
        alertDialog.show()
    }

    inner class JSInterface {

        @JavascriptInterface
        fun setDnsUrl(stamp: String, count: Long) {
            if (stamp.isEmpty()) {
                receivedStamp = Constants.BRAVE_BASE_STAMP
            }
            receivedStamp = stamp

            blocklistsCount.postValue(count.toInt())
            if (DEBUG) Log.d(LOG_TAG_DNS, "Stamp value - $receivedStamp, $count")
            finish()
        }

        @JavascriptInterface
        fun dismiss(reason: String) {
            Log.i(LOG_TAG_DNS, "dismiss with reason - $reason")
            finish()
        }

        @JavascriptInterface
        fun setBlocklistMetadata(timestamp: String, fileContent: String) {
            if (DEBUG) Log.d(LOG_TAG_DOWNLOAD,
                             "Content received from webview for blocklist download with timestamp: $timestamp, string length: ${fileContent.length}")

            if (persistentState.remoteBraveDNSDownloaded.toString() == timestamp) return
            if (fileContent.isEmpty()) return

            if (persistentState.remoteBlocklistDownloadTime.toString() != timestamp) {
                val filePath = getFilePath(timestamp)
                Log.i(LOG_TAG_DOWNLOAD,
                      "file path to store the remote blocklist's json file: $filePath")
                if (filePath.isNotEmpty()) {
                    File(filePath).writeText(fileContent)
                }
            }
        }
    }

    private fun getFilePath(timestamp: String): String {
        return try {
            val dir = File(this.filesDir.canonicalPath + File.separator + timestamp)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val filePath = File(dir.absolutePath + Constants.FILE_TAG_NAME)
            if (!filePath.exists()) {
                filePath.createNewFile()
            }
            this.filesDir.canonicalPath + File.separator + timestamp + Constants.FILE_TAG_NAME
        } catch (e: IOException) {
            Log.w(LOG_TAG_DOWNLOAD, "failure creating folder/file to store the filetag json file")
            ""
        }
    }

}
