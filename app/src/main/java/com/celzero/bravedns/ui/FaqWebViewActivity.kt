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
import android.content.res.Configuration
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityFaqWebviewLayoutBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities.Companion.getCurrentTheme
import org.koin.android.ext.android.inject


class FaqWebViewActivity : AppCompatActivity(R.layout.activity_faq_webview_layout) {
    private val b by viewBinding(ActivityFaqWebviewLayoutBinding::bind)
    private val persistentState by inject<PersistentState>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getCurrentTheme(this))
        super.onCreate(savedInstanceState)
        b.configureWebview.settings.domStorageEnabled = true
        b.configureWebview.settings.allowContentAccess = true
        b.configureWebview.settings.allowFileAccess = true
        b.configureWebview.settings.javaScriptEnabled = true
        b.configureWebview.settings.setSupportZoom(true)
        b.configureWebview.webViewClient = WebViewClient()
        b.configureWebview.clearCache(true)
        b.configureWebview.clearHistory()
        b.configureWebview.isClickable = true
        b.configureWebview.webChromeClient = WebChromeClient()

        val url = intent.getStringExtra(Constants.URL_INTENT_EXTRA)
        if (url == null) {
            b.configureWebview.loadUrl(this.resources.getString(R.string.faq_web_link))
        } else {
            b.configureWebview.loadUrl(url)
        }
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onDestroy() {
        b.configureWebview.destroy()
        super.onDestroy()
    }

}
