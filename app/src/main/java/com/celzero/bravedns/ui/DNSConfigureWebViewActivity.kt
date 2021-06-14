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
import android.app.Activity
import android.app.DownloadManager
import android.content.*
import android.content.res.Configuration
import android.database.Cursor
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.CountDownTimer
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
import com.celzero.bravedns.database.DoHEndpointRepository
import com.celzero.bravedns.databinding.ActivityFaqWebviewLayoutBinding
import com.celzero.bravedns.download.DownloadHelper
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG_DNS
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG_DOWNLOAD
import com.celzero.bravedns.util.HttpRequestHelper
import dnsx.Dnsx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import org.koin.android.ext.android.inject
import settings.Settings
import xdns.Xdns
import java.io.File
import java.io.IOException



class DNSConfigureWebViewActivity : AppCompatActivity(R.layout.activity_faq_webview_layout) {
    private val b by viewBinding(ActivityFaqWebviewLayoutBinding::bind)
    private val maxProgressBar = 100
    private var stamp: String? = ""
    private var url: String = Constants.CONFIGURE_BLOCKLIST_URL_REMOTE //"https://bravedns.com/configure?v=app"
    private var receivedStamp: String = ""
    private var blockListsCount: MutableLiveData<Int> = MutableLiveData()
    private lateinit var context: Context
    private lateinit var downloadManager: DownloadManager
    private var timeStamp: Long = 0L
    private var receivedIntentFrom: Int = 0
    private val doHEndpointRepository by inject<DoHEndpointRepository>()
    private val persistentState by inject<PersistentState>()

    companion object {
        const val LOCAL = 1
        const val REMOTE = 2
        var enqueue: Long = 0
    }


    @SuppressLint("SetJavaScriptEnabled") override fun onCreate(savedInstanceState: Bundle?) {
        if (persistentState.theme == 0) {
            if (isDarkThemeOn()) {
                setTheme(R.style.AppThemeTrueBlack)
            } else {
                setTheme(R.style.AppThemeWhite)
            }
        } else if (persistentState.theme == 1) {
            setTheme(R.style.AppThemeWhite)
        } else if (persistentState.theme == 2) {
            setTheme(R.style.AppTheme)
        } else {
            setTheme(R.style.AppThemeTrueBlack)
        }
        super.onCreate(savedInstanceState)
        context = this
        downloadManager = context.getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        receivedIntentFrom = intent.getIntExtra(Constants.LOCATION_INTENT_EXTRA, 0)
        stamp = intent.getStringExtra(Constants.STAMP_INTENT_EXTRA)
        try {
            initWebView()
            setWebClient()
            if(receivedIntentFrom == REMOTE){
                checkForDownload()
            }
            if (stamp != null && stamp!!.isNotEmpty()) {
                if (receivedIntentFrom == LOCAL) {
                    url = Constants.CONFIGURE_BLOCKLIST_URL_LOCAL + persistentState.localBlockListDownloadTime + "#" + stamp
                }else{
                    url = Constants.CONFIGURE_BLOCKLIST_URL_REMOTE + "#" + stamp
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG_DNS, "Webview: Exception: ${e.message}", e)
            showDialogOnError(null)
        }
        loadUrl(url)

        blockListsCount.observe(this, {
            if (receivedIntentFrom == LOCAL) {
                persistentState.numberOfLocalBlocklists = it!!
            } else {
                persistentState.numberOfRemoteBlocklists = it!!
            }
        })

        setWebViewTheme()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Sets the theme for the webview based on the application theme.
     * FORCE_DARK_ON - For Dark and True black theme
     * FORCE_DARK_OFF - Light mode.
     */
    private fun setWebViewTheme(){
        if (persistentState.theme == 0) {
            if (isDarkThemeOn()) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                    WebSettingsCompat.setForceDark(b.configureWebview.settings, WebSettingsCompat.FORCE_DARK_ON)
                }
            } else {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                    WebSettingsCompat.setForceDark(b.configureWebview.settings, WebSettingsCompat.FORCE_DARK_OFF)
                }
            }
        } else if (persistentState.theme == 1) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(b.configureWebview.settings, WebSettingsCompat.FORCE_DARK_OFF)
            }
        } else if (persistentState.theme == 2) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(b.configureWebview.settings, WebSettingsCompat.FORCE_DARK_ON)
            }
        } else {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(b.configureWebview.settings, WebSettingsCompat.FORCE_DARK_ON)
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            WebSettingsCompat.setForceDarkStrategy(b.configureWebview.settings, DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING)
        }
    }

    override fun onDestroy() {
        if (receivedIntentFrom == LOCAL) {
            updateLocalStamp()
        } else if (receivedIntentFrom == REMOTE) {
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
        Log.i(LOG_TAG_DNS, "Webview: Remote stamp has been updated from web view - $receivedStamp")
        if (receivedStamp.isEmpty() || receivedStamp == Constants.BRAVE_BASE_STAMP) {
            return
        }
        doHEndpointRepository.removeConnectionStatus()
        doHEndpointRepository.updateConnectionURL(receivedStamp)

        object : CountDownTimer(1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
            }

            override fun onFinish() {
                HomeScreenActivity.GlobalVariable.appMode?.setDNSMode(Settings.DNSModePort)
                persistentState.connectionModeChange = receivedStamp
                persistentState.setConnectedDNS(Constants.RETHINK_DNS_PLUS)
                persistentState.dnsType = Constants.PREF_DNS_MODE_DOH
            }
        }.start()

        Toast.makeText(this, getString(R.string.webview_toast_configure_success), Toast.LENGTH_SHORT).show()
    }

    private fun updateLocalStamp() {
        Log.i(LOG_TAG_DNS, "Webview: Local stamp has been set from webview - $receivedStamp")
        if (receivedStamp.isEmpty() || receivedStamp == Constants.BRAVE_BASE_STAMP) {
            return
        }
        val stamp = Xdns.getBlocklistStampFromURL(receivedStamp)
        if (DEBUG) Log.d(LOG_TAG_DNS, "Split stamp - $stamp")
        val path: String = this.filesDir.canonicalPath + "/"+ persistentState.localBlockListDownloadTime
        persistentState.localBlockListStamp = stamp
        if (HomeScreenActivity.GlobalVariable.appMode?.getBraveDNS() == null) {
            GlobalScope.launch(Dispatchers.IO) {
                if (DEBUG) Log.d(LOG_TAG_DNS, "Split stamp newBraveDNSLocal - $path")
                try {
                    val braveDNS = Dnsx.newBraveDNSLocal(path + Constants.FILE_TD_FILE, path + Constants.FILE_RD_FILE, path + Constants.FILE_BASIC_CONFIG, path + Constants.FILE_TAG_NAME)
                    HomeScreenActivity.GlobalVariable.appMode?.setBraveDNSMode(braveDNS)
                    if (DEBUG) Log.d(LOG_TAG_DNS, "Webview: Local brave dns set call from web view -stamp: $stamp")
                } catch (e: Exception) {
                    Log.e(LOG_TAG_DNS, "Local brave dns set exception :${e.message}", e)
                }
            }
        } else {
            GlobalScope.launch(Dispatchers.IO) {
                val braveDNS = HomeScreenActivity.GlobalVariable.appMode?.getBraveDNS()
                HomeScreenActivity.GlobalVariable.appMode?.setBraveDNSMode(braveDNS)
                if (DEBUG) Log.d(LOG_TAG_DNS, "Webview: Local brave dns set call from web view -stamp: $stamp")
            }
        }
        Toast.makeText(this, getString(R.string.wv_local_blocklist_toast), Toast.LENGTH_SHORT).show()
        //}
    }

    private fun getExternalFilePath(context: Context, isAbsolutePathNeeded: Boolean): String {
        return if (isAbsolutePathNeeded) {
            context.getExternalFilesDir(null).toString() + Constants.DOWNLOAD_PATH + persistentState.tempRemoteBlockListDownloadTime
        } else {
            Constants.DOWNLOAD_PATH + persistentState.tempRemoteBlockListDownloadTime
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
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!b.configureWebview.url!!.contains(Constants.BRAVE_CONFIGURE_BASE_STAMP)) {
                b.configureWebview.goBack()
            } else if (receivedStamp.isEmpty()) {
                showDialogForExitWebView()
            } else {
                return super.onKeyDown(keyCode, event)
            }
            if (DEBUG) Log.d(LOG_TAG_DNS, "Webview: URL : ${b.configureWebview.url}")
        }
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
        //performing positive action
        builder.setPositiveButton(getString(R.string.webview_configure_dialog_positive)) { dialogInterface, _ ->
            dialogInterface.dismiss()
            finish()
        }

        //performing negative action
        builder.setNegativeButton(getString(R.string.webview_configure_dialog_negative)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }

        builder.setNeutralButton(getString(R.string.webview_configure_dialog_neutral)) { _: DialogInterface, _: Int ->
            b.configureWebview.reload()
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.setCancelable(true)
        alertDialog.show()
    }

    @SuppressLint("SetJavaScriptEnabled") private fun initWebView() {
        b.configureWebview.settings.javaScriptEnabled = true
        b.configureWebview.settings.loadWithOverviewMode = true
        b.configureWebview.settings.useWideViewPort = true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            b.configureWebview.setRendererPriorityPolicy(RENDERER_PRIORITY_BOUND, true)
        }
        context = this
        b.configureWebview.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                Log.i(LOG_TAG_DNS, "Webview: SSL error received from webview. ${error?.primaryError}, ${handler?.obtainMessage()}")
                showDialogOnError(handler)
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                super.onReceivedHttpError(view, request, errorResponse)
                showDialogOnError(null)
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    if (!detail?.didCrash()!!) {
                        // Renderer was killed because the system ran out of memory.
                        // The app can recover gracefully by creating a new WebView instance
                        // in the foreground.
                        Log.w(LOG_TAG_DNS, ("Webview: System killed the WebView rendering process Recreating..."))

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
                Log.w(LOG_TAG_DNS, "Webview: The WebView rendering process crashed!")

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
            Log.w(LOG_TAG_DNS, "Web view: Issue while loading url: ${e.message}", e)
            showDialogOnError(null)
        }
    }


    private fun showDialogOnError(handler: SslErrorHandler?) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.webview_error_title)
        builder.setMessage(R.string.webview_error_message)
        builder.setCancelable(true)
        //performing positive action
        builder.setPositiveButton(getString(R.string.webview_configure_dialog_neutral)) { _, _ ->
            if (handler != null) {
                handler.proceed()
            } else {
                b.configureWebview.reload()
            }
        }

        //performing negative action
        builder.setNegativeButton(getString(R.string.webview_configure_dialog_negative)) { dialogInterface, _ ->
            if (handler != null) {
                handler.cancel()
            } else {
                dialogInterface.dismiss()
                finish()
            }
        }

        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.setCancelable(true)
        alertDialog.show()
    }

    inner class JSInterface {

        @JavascriptInterface fun setDnsUrl(stamp: String, count: Long) {
            if (stamp.isEmpty()) {
                receivedStamp = Constants.BRAVE_BASE_STAMP
            }
            receivedStamp = stamp

            blockListsCount.postValue(count.toInt())
            if (DEBUG) Log.d(LOG_TAG_DNS, "Webview: - Stamp value - $receivedStamp, $count")
            finish()
        }

        @JavascriptInterface fun dismiss(reason: String) {
            Log.i(LOG_TAG_DNS, "Webview:  dismiss with reason - $reason")
            finish()
        }
    }

    private fun registerReceiverForDownloadManager() {
        this.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private var onComplete: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctxt: Context, intent: Intent) {
            if (DEBUG) Log.d(LOG_TAG_DNS, "Webview: Intent on receive ")
            try {
                val action = intent.action
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == action) {
                    val query = DownloadManager.Query()
                    query.setFilterById(enqueue)
                    val c: Cursor = downloadManager.query(query)!!
                    if (c.moveToFirst()) {
                        val status = HttpRequestHelper.checkStatus(c)
                        if (status == Constants.DOWNLOAD_STATUS_SUCCESSFUL) {
                            val from = File(getExternalFilePath(ctxt, true)+ Constants.FILE_TAG_NAME)
                            val to = File(ctxt.filesDir.canonicalPath +"/"+persistentState.tempRemoteBlockListDownloadTime + Constants.FILE_TAG_NAME)
                            val fileDownloaded = from.copyTo(to, true)
                            if (fileDownloaded.exists()) {
                                DownloadHelper.deleteOldFiles(ctxt)
                                persistentState.remoteBraveDNSDownloaded = true
                                persistentState.remoteBlockListDownloadTime = persistentState.tempRemoteBlockListDownloadTime
                            }
                        } else {
                            Log.w(LOG_TAG_DNS, "Webview: Error downloading filetag.json file: $status")
                        }
                    } else {
                        Log.w(LOG_TAG_DNS, "Webview: Download status: Error downloading filetag.json file: ${c.count}")
                    }
                    c.close()
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG_DNS, "Webview: Error downloading filetag.json file: ${e.message}", e)
                persistentState.remoteBlockListDownloadTime = 0L
                persistentState.remoteBraveDNSDownloaded = false
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            //Register or UnRegister your broadcast receiver here
            this.unregisterReceiver(onComplete)
        } catch (e: IllegalArgumentException) {
            Log.w(LOG_TAG_DNS, "Unregister receiver exception", e)
        }
    }

    private fun downloadBlockListFiles() {
        registerReceiverForDownloadManager()
        if (timeStamp == 0L) timeStamp = persistentState.tempRemoteBlockListDownloadTime
        val url = Constants.JSON_DOWNLOAD_BLOCKLIST_LINK + "/" + timeStamp
        if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "Webview: download filetag file with url: $url")
        val uri: Uri = Uri.parse(url)
        val request = DownloadManager.Request(uri)
        request.setDestinationInExternalFilesDir(this, getExternalFilePath(this, false), Constants.FILE_TAG_NAME)
        Log.i(LOG_TAG_DOWNLOAD, "Webview: Path - ${getExternalFilePath(this, true)}${Constants.FILE_TAG_NAME}")
        enqueue = downloadManager.enqueue(request)
    }

    private fun checkForDownload() {
        val blockListTimeStamp = persistentState.remoteBlockListDownloadTime
        val appVersionCode = persistentState.appVersion
        val url = "${Constants.REFRESH_BLOCKLIST_URL}$blockListTimeStamp&${Constants.APPEND_VCODE}$appVersionCode"
        Log.i(LOG_TAG_DOWNLOAD, "Webview: Check for local download, url - $url")
        run(url)
    }

    private fun run(url: String) {
        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.i(LOG_TAG_DOWNLOAD, "Webview: onFailure -  ${call.isCanceled()}, ${call.isExecuted()}")
            }

            override fun onResponse(call: Call, response: Response) {
                val stringResponse = response.body!!.string()
                //creating json object
                val jsonObject = JSONObject(stringResponse)
                val version = jsonObject.getInt(Constants.JSON_VERSION)
                if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "Webview: client onResponse for refresh blocklist files-  $version")
                if (version == 1) {
                    val updateValue = jsonObject.getBoolean(Constants.JSON_UPDATE)
                    timeStamp = jsonObject.getLong(Constants.JSON_LATEST)
                    persistentState.tempRemoteBlockListDownloadTime = timeStamp
                    if (DEBUG) Log.d(LOG_TAG_DOWNLOAD, "Webview: onResponse -  $updateValue, $timeStamp")
                    if (updateValue) {
                        (context as Activity).runOnUiThread {
                            downloadBlockListFiles()
                        }
                    }
                }
                response.body!!.close()
                client.connectionPool.evictAll()
            }
        })
    }

}