package com.celzero.bravedns.ui

import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.celzero.bravedns.R


class FaqWebViewActivity  : AppCompatActivity(){

    private lateinit var faqWebView : WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_faq_webview_layout)
        faqWebView = findViewById(R.id.faq_webview)
        faqWebView.settings.domStorageEnabled = true
        faqWebView.settings.allowContentAccess = true
        faqWebView.settings.allowFileAccess = true
        faqWebView.getSettings().setJavaScriptEnabled(true);
        faqWebView.settings.allowFileAccessFromFileURLs = true
        faqWebView.settings.allowUniversalAccessFromFileURLs = true
        faqWebView.settings.setSupportZoom(true)
        faqWebView.webViewClient = WebViewClient()
        faqWebView.clearCache(true)
        faqWebView.clearHistory()
        faqWebView.isClickable = true
        faqWebView.webChromeClient = WebChromeClient()
        val url = intent.getStringExtra("url")
        if(url == null) {
            faqWebView.loadUrl(this.resources.getString(R.string.faq_web_link))
        }else{
            faqWebView.loadUrl(url)
        }
    }

    override fun onDestroy() {
        Log.d("BraveDNS","onDestroy")
        faqWebView.destroy()
        super.onDestroy()

    }

}