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
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
import androidx.webkit.WebViewFeature
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.databinding.ActivityFaqWebviewLayoutBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.CONFIGURE_BLOCKLIST_URL
import com.celzero.bravedns.util.Constants.Companion.CONFIGURE_BLOCKLIST_URL_PARAMETER
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DOWNLOAD
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_VPN
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities.Companion.isAtleastO
import com.celzero.bravedns.util.Utilities.Companion.remoteBlocklistDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koin.android.ext.android.inject
import xdns.Xdns
import java.io.File
import java.io.IOException

class DNSConfigureWebViewActivity : AppCompatActivity(R.layout.activity_faq_webview_layout) {
    private val b by viewBinding(ActivityFaqWebviewLayoutBinding::bind)
    private val maxProgressBar = 100
    private var receivedIntentFrom: Int = 0
    private var receivedStamp: String = ""
    private val persistentState by inject<PersistentState>()
    private val appMode by inject<AppMode>()

    companion object {
        const val LOCAL = 1
        const val REMOTE = 2
        const val BLOCKLIST_REMOTE_FOLDER_NAME = "remote_blocklist"
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
            showErrorDialog(null)
        }

        setWebViewTheme()
    }

    private fun constructUrl(stamp: String?): String {
        if (stamp.isNullOrBlank()) {
            return CONFIGURE_BLOCKLIST_URL
        }

        return if (isLocal()) {
            val url = CONFIGURE_BLOCKLIST_URL.toHttpUrlOrNull()?.newBuilder()
            url?.addQueryParameter(CONFIGURE_BLOCKLIST_URL_PARAMETER,
                                   persistentState.localBlocklistTimestamp.toString())
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
    @SuppressLint("RequiresFeature")
    private fun setWebViewTheme() {

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            setWebViewForceDarkStrategy()
            return
        }

        when (persistentState.theme) {
            Themes.SYSTEM_DEFAULT.id -> {
                if (isDarkThemeOn()) {
                    WebSettingsCompat.setForceDark(b.configureWebview.settings,
                                                   WebSettingsCompat.FORCE_DARK_ON)
                } else {
                    WebSettingsCompat.setForceDark(b.configureWebview.settings,
                                                   WebSettingsCompat.FORCE_DARK_OFF)
                }
            }
            Themes.DARK.id -> {
                WebSettingsCompat.setForceDark(b.configureWebview.settings,
                                               WebSettingsCompat.FORCE_DARK_ON)
            }
            Themes.LIGHT.id -> {
                WebSettingsCompat.setForceDark(b.configureWebview.settings,
                                               WebSettingsCompat.FORCE_DARK_OFF)
            }
            Themes.TRUE_BLACK.id -> {
                WebSettingsCompat.setForceDark(b.configureWebview.settings,
                                               WebSettingsCompat.FORCE_DARK_ON)
            }
            else -> {
                WebSettingsCompat.setForceDark(b.configureWebview.settings,
                                               WebSettingsCompat.FORCE_DARK_ON)
            }
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
        b.configureWebview.removeJavascriptInterface("JSInterface")
        b.webviewPlaceholder.removeView(b.configureWebview)
        b.configureWebview.removeAllViews()
        b.configureWebview.clearHistory()
        b.configureWebview.destroy()
        super.onDestroy()
    }

    private fun updateDoHEndPoint(stamp: String, count: Int) {
        Log.i(LOG_TAG_DNS, "rethinkdns+ stamp updated to: $stamp")

        CoroutineScope(Dispatchers.IO).launch {
            appMode.updateRethinkDnsPlusStamp(stamp, count)
        }

        Toast.makeText(this, getString(R.string.webview_toast_configure_success),
                       Toast.LENGTH_SHORT).show()
    }

    private fun updateLocalBlocklistStamp(stamp: String, count: Int) {
        Log.i(LOG_TAG_DNS, "local blocklist stamp set to: $stamp")

        persistentState.localBlocklistStamp = Xdns.getBlocklistStampFromURL(stamp)
        persistentState.numberOfLocalBlocklists = count

        Toast.makeText(this, getString(R.string.wv_local_blocklist_toast),
                       Toast.LENGTH_SHORT).show()
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

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.i(LOG_TAG_DNS,
                      "${message.message()} from line " + "${message.lineNumber()} of ${message.sourceId()}")
                return true
            }

        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Check if the key event was the Back button and if there's changes
        if (keyCode != KeyEvent.KEYCODE_BACK) return false

        if (b.configureWebview.url?.contains(Constants.BRAVE_CONFIGURE_BASE_STAMP) == false) {
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

        builder.create().show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        b.configureWebview.settings.javaScriptEnabled = true
        b.configureWebview.settings.loadWithOverviewMode = true
        b.configureWebview.settings.useWideViewPort = true
        if (isAtleastO()) {
            b.configureWebview.setRendererPriorityPolicy(RENDERER_PRIORITY_BOUND, true)
        }
        b.configureWebview.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?,
                                            error: SslError?) {
                Log.i(LOG_TAG_DNS,
                      "SSL error received from webview. ${error?.primaryError}, ${handler?.obtainMessage()}")
                showErrorDialog(handler)
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?,
                                             errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                showErrorDialog(null)
            }

            override fun onRenderProcessGone(view: WebView?,
                                             detail: RenderProcessGoneDetail?): Boolean {
                if (isAtleastO()) {
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

                // the app itself crashes after detecting that the
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
            showErrorDialog(null)
        }
    }

    private fun showErrorDialog(handler: SslErrorHandler?) {
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

        builder.create().show()
    }

    inner class JSInterface {

        @JavascriptInterface
        fun setDnsUrl(stamp: String, count: Long) {
            receivedStamp = stamp
            if (receivedStamp.isEmpty() || receivedStamp == Constants.BRAVE_BASE_STAMP) {
                return
            }

            if (isLocal()) {
                updateLocalBlocklistStamp(receivedStamp, count.toInt())
            } else {
                updateDoHEndPoint(receivedStamp, count.toInt())
            }

            if (DEBUG) Log.d(LOG_TAG_DNS, "stamp, blocklist-count: $receivedStamp, $count")
            finish()
        }

        @JavascriptInterface
        fun dismiss(reason: String) {
            Log.i(LOG_TAG_DNS, "dismiss with reason: $reason")
            finish()
        }

        @JavascriptInterface
        fun setBlocklistMetadata(timestamp: String, fileContent: String) {
            if (DEBUG) Log.d(LOG_TAG_DOWNLOAD,
                             "Content received from webview for blocklist download with timestamp: $timestamp, string length: ${fileContent.length}")

            if (isLocal()) return // do not save filetag.json in on-device mode

            if (fileContent.isEmpty()) return

            val t = parseLong(timestamp)

            if (persistentState.remoteBlocklistTimestamp >= t) return

            try {
                val filetag = makeFile(t) ?: return

                filetag.writeText(fileContent)
                persistentState.remoteBlocklistTimestamp = t
            } catch (e: IOException) {
                Log.w(LOG_TAG_DOWNLOAD, "could not create filetag.json at version $timestamp", e)
            }
        }
    }

    private fun parseLong(s: String): Long {
        return try {
            s.toLong()
        } catch (ignored: NumberFormatException) {
            Long.MIN_VALUE
        }
    }

    private fun makeFile(timestamp: Long): File? {
        val dir = remoteBlocklistDir(this, BLOCKLIST_REMOTE_FOLDER_NAME, timestamp) ?: return null

        if (!dir.exists()) {
            dir.mkdirs()
        }
        val filePath = File(dir.absolutePath + Constants.FILE_TAG_NAME)
        if (!filePath.exists()) {
            filePath.createNewFile()
        }
        return filePath
    }

}
