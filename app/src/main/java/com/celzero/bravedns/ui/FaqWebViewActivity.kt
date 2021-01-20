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
import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityFaqWebviewLayoutBinding
import com.celzero.bravedns.databinding.ActivityQueryDetailBinding
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG


class FaqWebViewActivity : AppCompatActivity(R.layout.activity_faq_webview_layout) {
    private val b by viewBinding(ActivityFaqWebviewLayoutBinding::bind)

    @SuppressLint("SetJavaScriptEnabled") override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b.configureWebview.settings.domStorageEnabled = true
        b.configureWebview.settings.allowContentAccess = true
        b.configureWebview.settings.allowFileAccess = true
        b.configureWebview.settings.javaScriptEnabled = true
        b.configureWebview.settings.allowFileAccessFromFileURLs = true
        b.configureWebview.settings.allowUniversalAccessFromFileURLs = true
        b.configureWebview.settings.setSupportZoom(true)
        b.configureWebview.webViewClient = WebViewClient()
        b.configureWebview.clearCache(true)
        b.configureWebview.clearHistory()
        b.configureWebview.isClickable = true
        b.configureWebview.webChromeClient = WebChromeClient()

        val url = intent.getStringExtra("url")
        if (url == null) {
            b.configureWebview.loadUrl(this.resources.getString(R.string.faq_web_link))
        } else {
            b.configureWebview.loadUrl(url)
        }
        /**
         *  faqWebView.evaluateJavascript(
        "(function() { return ('<html>'+document.getElementsByTagName('html')[0].innerHTML+'</html>'); })();",
        ValueCallback<String?> { html ->
        Log.d("HTML", html)
        // code here
        })
         */

    }

    override fun onDestroy() {
        if (DEBUG) Log.d(LOG_TAG, "onDestroy")
        b.configureWebview.destroy()
        super.onDestroy()
    }

}