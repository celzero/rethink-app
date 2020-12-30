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
import com.celzero.bravedns.R
import com.celzero.bravedns.database.DoHEndpointRepository
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.HttpRequestHelper
import com.celzero.bravedns.util.Utilities
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


class DNSConfigureWebViewActivity : AppCompatActivity() {

    private var dnsConfigureWebView: WebView?= null
    private lateinit var progressBar: ProgressBar
    private val MAX_PROGRESS = 100
    private var stamp : String? = ""
    private var url : String = Constants.CONFIGURE_BLOCKLIST_URL_REMOTE //"https://bravedns.com/configure?v=app"
    private var receivedStamp : String = ""
    private var blockListsCount : MutableLiveData<Int> = MutableLiveData()
    private lateinit var context : Context
    private lateinit var downloadManager : DownloadManager
    private var timeStamp : Long = 0L
    private var receivedIntentFrom : Int = 0
    private val doHEndpointRepository by inject<DoHEndpointRepository>()
    private val persistentState by inject<PersistentState>()

    companion object{
        const val LOCAL = 1
        const val REMOTE = 2
        var enqueue: Long = 0

    }


    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_faq_webview_layout)
        dnsConfigureWebView = findViewById(R.id.configure_webview)
        progressBar = findViewById(R.id.progressBar)
        context = this
        downloadManager = context.getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        receivedIntentFrom = intent.getIntExtra("location", 0)
        stamp = intent.getStringExtra("stamp")
        try{
            initWebView()
            setWebClient()
            if (stamp != null && stamp!!.isNotEmpty()) {
                if(receivedIntentFrom == LOCAL) {
                    url = Constants.CONFIGURE_BLOCKLIST_URL_LOCAL + persistentState.localBlockListDownloadTime + "#" + stamp
                }else{
                    url = Constants.CONFIGURE_BLOCKLIST_URL_REMOTE + persistentState.remoteBlockListDownloadTime + "#" + stamp
                    checkForDownload()
                }
            }
            Log.d(LOG_TAG, "Webview:  - url - $url")
        }catch (e: Exception){
            Log.i(LOG_TAG, "Webview: Exception: ${e.message}", e)
            showDialogOnError(null)
        }
        loadUrl(url)

        blockListsCount.observe(this, {
            if (receivedIntentFrom == LOCAL) {
                persistentState.numberOfLocalBlocklists = it!!
            }else{
                persistentState.numberOfRemoteBlocklists = it!!
            }

        })

        if (DEBUG) Log.d(LOG_TAG, "Webview: Download remote file - filetag")


    }

    override fun onDestroy() {
        if(DEBUG) Log.d(LOG_TAG, "Webview: onDestroy")

        if(receivedIntentFrom == LOCAL){
            updateLocalStamp()
        }else if(receivedIntentFrom == REMOTE) {
            updateDoHEndPoint()
        }
        dnsConfigureWebView?.removeJavascriptInterface("JSInterface")
        dnsConfigureWebView?.removeAllViews()
        /*dnsConfigureWebView?.clearCache(true)
        dnsConfigureWebView?.clearFormData()*/
        dnsConfigureWebView?.clearHistory()
        dnsConfigureWebView?.destroy()
        dnsConfigureWebView = null

        /*val intent = Intent()
        intent.putExtra("stamp", receivedStamp)
        setResult(Activity.RESULT_OK, intent)
        finish()*/
        super.onDestroy()
    }

    private fun updateDoHEndPoint() {
        //if(receivedStamp.isNotEmpty()) {
        Log.i(LOG_TAG, "Webview: Remote stamp has been updated from web view - $receivedStamp")
        if (receivedStamp.isEmpty() || receivedStamp == "https://basic.bravedns.com/") {
            return
        }
        doHEndpointRepository.removeConnectionStatus()
        doHEndpointRepository.updateConnectionURL(receivedStamp)

        object : CountDownTimer(500, 1000) {
            override fun onTick(millisUntilFinished: Long) {
            }

            override fun onFinish() {
                HomeScreenActivity.GlobalVariable.appMode?.setDNSMode(Settings.DNSModePort)
                persistentState.dnsType = 1
                persistentState.connectionModeChange = receivedStamp
                HomeScreenActivity.GlobalVariable.dnsType.postValue(1)
            }
        }.start()

        Toast.makeText(this, "Configured URL has been updated successfully", Toast.LENGTH_SHORT).show()
    }

    private fun updateLocalStamp(){
        //if(receivedStamp.isNotEmpty()) {
            Log.i(LOG_TAG, "Webview: Local stamp has been set from webview - $receivedStamp")
            if(receivedStamp.isEmpty() || receivedStamp == "https://basic.bravedns.com/"){
                val localStamp = persistentState.getLocalBlockListStamp()
                if(localStamp?.isNotEmpty()!!){
                    return
                }
                persistentState.setLocalBlockListStamp("")
                return
            }
            //PersistentState.setNumberOfLocalBlockLists(this, blockListsCount.value!!)
            val stamp = Xdns.getBlocklistStampFromURL(receivedStamp)
            if(DEBUG) Log.d(LOG_TAG, "Split stamp - $stamp")
            val context = this
            val path: String = this.filesDir.canonicalPath
            if (HomeScreenActivity.GlobalVariable.appMode?.getBraveDNS() == null) {
                GlobalScope.launch(Dispatchers.IO) {
                    val braveDNS = Dnsx.newBraveDNSLocal(path + Constants.FILE_TD_FILE, path + Constants.FILE_RD_FILE, path + Constants.FILE_BASIC_CONFIG, path + Constants.FILE_TAG_NAME)
                    HomeScreenActivity.GlobalVariable.appMode?.setBraveDNSMode(braveDNS)
                    persistentState.setLocalBlockListStamp(stamp)
                    if(DEBUG) Log.d(LOG_TAG, "Webview: Local brave dns set call from web view -stamp: $stamp")
                }
            }else{
                GlobalScope.launch(Dispatchers.IO) {
                    //val braveDNS = Dnsx.newBraveDNSLocal(path + Constants.FILE_TD_FILE, path + Constants.FILE_RD_FILE, path + Constants.FILE_BASIC_CONFIG, path + Constants.FILE_TAG_NAME)
                    //HomeScreenActivity.GlobalVariable.appMode?.setBraveDNSMode(braveDNS)
                    persistentState.setLocalBlockListStamp(stamp)
                    if (DEBUG) Log.d(LOG_TAG, "Webview: Local brave dns set call from web view -stamp: $stamp")
                }
            }
            Toast.makeText(this, "Local blocklists updated successfully", Toast.LENGTH_SHORT).show()
        //}
    }

    private fun setWebClient() {
        dnsConfigureWebView?.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(
                view: WebView,
                newProgress: Int
            ) {
                super.onProgressChanged(view, newProgress)
                progressBar.progress = newProgress
                if (newProgress < MAX_PROGRESS && progressBar.visibility == ProgressBar.GONE) {
                    progressBar.visibility = ProgressBar.VISIBLE
                }
                if (newProgress == MAX_PROGRESS) {
                    progressBar.visibility = ProgressBar.GONE
                }
            }

        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Check if the key event was the Back button and if there's history
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if(DEBUG) Log.d(LOG_TAG, "Webview: WebView- onBack pressed")
            if(!dnsConfigureWebView?.url!!.contains("bravedns.com/configure")){
                //return super.onKeyDown(keyCode, event)
                dnsConfigureWebView?.goBack()
            } else if (receivedStamp.isEmpty()) {
                showDialogForExitWebView()
                //Toast.makeText(this, "Blocklist not set", Toast.LENGTH_SHORT).show()
            }else{
                return super.onKeyDown(keyCode, event)
            }
            //return false
            if(DEBUG) Log.d(LOG_TAG, "Webview: URL : ${dnsConfigureWebView?.url}")
        }
        // If it wasn't the Back key or there's no web page history, exit the activity)
        return false
    }

    private fun showDialogForExitWebView() {
        //dnsConfigureWebView.reload()
        val count = persistentState.numberOfLocalBlocklists
        val builder = AlertDialog.Builder(this)
        //set title for alert dialog
        builder.setTitle(R.string.webview_no_stamp_change_title)
        //set message for alert dialog
        val desc = if(receivedIntentFrom == LOCAL) {
            getString(R.string.webview_no_stamp_change_desc, count.toString())
        }else{
            getString(R.string.webview_no_stamp_change_remote)
        }
        builder.setMessage(desc)
        builder.setIcon(android.R.drawable.ic_dialog_alert)
        builder.setCancelable(true)
        //performing positive action
        builder.setPositiveButton("Quit") { dialogInterface, which ->
            dialogInterface.dismiss()
            finish()
        }

        //performing negative action
        builder.setNegativeButton("Go Back") { dialogInterface, which ->
            dialogInterface.dismiss()
        }

        builder.setNeutralButton("Reload"){ dialogInterface: DialogInterface, i: Int ->
            dnsConfigureWebView?.reload()
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.setCancelable(true)
        alertDialog.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        dnsConfigureWebView?.settings!!.javaScriptEnabled = true
        dnsConfigureWebView?.settings!!.loadWithOverviewMode = true
        dnsConfigureWebView?.settings!!.useWideViewPort = true
        //dnsConfigureWebView?.settings!!.domStorageEnabled = true
        //dnsConfigureWebView?.settings!!.cacheMode = LOAD_NO_CACHE
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            dnsConfigureWebView?.setRendererPriorityPolicy(RENDERER_PRIORITY_BOUND, true)
        }
        context  = this
        dnsConfigureWebView?.webViewClient = object : WebViewClient() {
            override
            fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                Log.i(LOG_TAG, "Webview: SSL error received from webview. Finishing the webview with toast. ${error?.primaryError}, ${handler?.obtainMessage()}")
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
                        Log.e(
                            LOG_TAG,
                            ("Webview: System killed the WebView rendering process Recreating...")
                        )

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
                Log.e(LOG_TAG, "Webview: The WebView rendering process crashed!")

                // In this example, the app itself crashes after detecting that the
                // renderer crashed. If you choose to handle the crash more gracefully
                // and allow your app to continue executing, you should 1) destroy the
                // current WebView instance, 2) specify logic for how the app can
                // continue executing, and 3) return "true" instead.
                return false
            }

        }

        dnsConfigureWebView?.addJavascriptInterface(JSInterface(), "JSInterface")
    }

    private fun loadUrl(pageUrl: String) {
        try {
            dnsConfigureWebView?.post {
                run {
                    dnsConfigureWebView?.loadUrl(pageUrl)
                }
            }

            //dnsConfigureWebView?.loadUrl(pageUrl)
        }catch (e: Exception){
            Log.e(LOG_TAG, "Web view: Issue while loading url: ${e.message}", e)
            showDialogOnError(null)
        }
    }


    private fun showDialogOnError(handler: SslErrorHandler?) {
        //dnsConfigureWebView.reload()
        val builder = AlertDialog.Builder(this)
        //set title for alert dialog
        builder.setTitle(R.string.webview_error_title)
        //set message for alert dialog
        builder.setMessage(R.string.webview_error_message)
        builder.setIcon(android.R.drawable.ic_dialog_alert)
        builder.setCancelable(true)
        //performing positive action
        builder.setPositiveButton("Reload") { dialogInterface, which ->
            if(handler != null){
                handler.proceed()
            }else {
                dnsConfigureWebView?.reload()
            }
        }

        //performing negative action
        builder.setNegativeButton("Cancel") { dialogInterface, which ->
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

        @JavascriptInterface
        fun setDnsUrl(stamp: String, count: Long) {
            if (stamp.isEmpty()) {
                receivedStamp = "https://basic.bravedns.com/"
            }
            receivedStamp = stamp

            blockListsCount.postValue(count.toInt())
            if(DEBUG) Log.d(LOG_TAG, "Webview: - Stamp value - $receivedStamp, $count")
            finish()
        }

        @JavascriptInterface
        fun dismiss(reason: String){
            Log.i(LOG_TAG, "Webview:  dismiss with reason - $reason")
            finish()
        }
    }

    private fun handleDownloadFiles() {
        val timeStamp = persistentState.remoteBlockListDownloadTime
        if (timeStamp == 0L) {
            checkForDownload()
        }
    }

    private fun registerReceiverForDownloadManager() {
        this.registerReceiver(onComplete, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private var onComplete: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctxt: Context, intent: Intent) {
            if (DEBUG) Log.d(LOG_TAG, "Webview: Intent on receive ")
            try {
                val action = intent.action
                if (DEBUG) Log.d(LOG_TAG, "Webview: Download status: $action")
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == action) {
                    if (DEBUG) Log.d(LOG_TAG, "Webview: Download status: $action")
                    //val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0)
                    val query = DownloadManager.Query()
                    query.setFilterById(enqueue)
                    val c: Cursor = downloadManager.query(query)!!
                    if (DEBUG) Log.d(LOG_TAG, "Webview: Download status: before cursor - $query")
                    if (c.moveToFirst()) {
                        if (DEBUG) Log.d(LOG_TAG, "Webview: Download status: moveToFirst cursor - $query")
                        //val columnIndex: Int = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        val status = HttpRequestHelper.checkStatus(c)
                        if (DEBUG) Log.d(LOG_TAG, "Webview: Download status: $status,${HomeScreenActivity.GlobalVariable.filesDownloaded}")
                        if (status == "STATUS_SUCCESSFUL") {
                            val from = File(ctxt.getExternalFilesDir(null).toString() + Constants.DOWNLOAD_PATH + Constants.FILE_TAG_NAME)
                            val to = File(ctxt.filesDir.canonicalPath + Constants.FILE_TAG_NAME)
                            val fileDownloaded = from.copyTo(to, true)
                            if(fileDownloaded.exists()) {
                                Utilities.deleteOldFiles(ctxt)
                            }
                            //val destDir = File(ctxt.filesDir.canonicalPath)
                            //Utilities.moveTo(from, to, destDir)
                            persistentState.remoteBraveDNSDownloaded = true
                        } else {
                            Log.e(LOG_TAG, "Webview: Error downloading filetag.json file: $status")
                        }
                    }else{
                        Log.e(LOG_TAG, "Webview: Download status: Error downloading filetag.json file: ${c.count}")
                    }
                    c.close()
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Webview: Error downloading filetag.json file: ${e.message}", e)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            //Register or UnRegister your broadcast receiver here
            this.unregisterReceiver(onComplete)
        } catch (e: IllegalArgumentException) {
            Log.w(LOG_TAG,"Unregister receiver exception")
        }
    }

    private fun downloadBlockListFiles() {
        registerReceiverForDownloadManager()
        if(timeStamp == 0L)
            timeStamp = persistentState.remoteBlockListDownloadTime
        val url = Constants.JSON_DOWNLOAD_BLOCKLIST_LINK + "/" + timeStamp
        if(DEBUG) Log.d(LOG_TAG, "Webview: download filetag file with url: $url")
        val uri: Uri = Uri.parse(url)
        val request = DownloadManager.Request(uri)
        request.setDestinationInExternalFilesDir(this, Constants.DOWNLOAD_PATH, Constants.FILE_TAG_NAME)
        Log.i(LOG_TAG, "Webview: Path - ${this.filesDir.canonicalPath}${Constants.DOWNLOAD_PATH}${Constants.FILE_TAG_NAME}")
        enqueue = downloadManager.enqueue(request)
    }

    private fun checkForDownload() {
        val blockListTimeStamp = persistentState.remoteBlockListDownloadTime
        val appVersionCode = persistentState.appVersion
        val url = "${Constants.REFRESH_BLOCKLIST_URL}$blockListTimeStamp&${Constants.APPEND_VCODE}$appVersionCode"
        Log.d(LOG_TAG, "Webview: Check for local download, url - $url")
        run(url)
    }

    private fun run(url: String) {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(LOG_TAG, "Webview: onFailure -  ${call.isCanceled()}, ${call.isExecuted()}")
            }

            override fun onResponse(call: Call, response: Response) {
                val stringResponse = response.body!!.string()
                //creating json object
                val jsonObject = JSONObject(stringResponse)
                val version = jsonObject.getInt("version")
                if (DEBUG) Log.d(LOG_TAG, "Webview: client onResponse for refresh blocklist files-  $version")
                if (version == 1) {
                    val updateValue = jsonObject.getBoolean("update")
                    timeStamp = jsonObject.getLong("latest")
                    persistentState.remoteBlockListDownloadTime = timeStamp
                    if (DEBUG) Log.d(LOG_TAG, "Webview: onResponse -  $updateValue, $timeStamp")
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