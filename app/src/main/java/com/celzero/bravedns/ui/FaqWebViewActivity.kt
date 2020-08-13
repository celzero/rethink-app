package com.celzero.bravedns.ui

import android.os.Bundle
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
        faqWebView.getSettings().setDomStorageEnabled(true)
        faqWebView.getSettings().setSaveFormData(true)
        faqWebView.getSettings().setAllowContentAccess(true)
        faqWebView.getSettings().setAllowFileAccess(true)
        faqWebView.getSettings().setJavaScriptEnabled(true);
        faqWebView.getSettings().setAllowFileAccessFromFileURLs(true)
        faqWebView.getSettings().setAllowUniversalAccessFromFileURLs(true)
        faqWebView.getSettings().setSupportZoom(true)
        faqWebView.setWebViewClient(WebViewClient())
        faqWebView.clearCache(true)
        faqWebView.clearHistory()
        faqWebView.setClickable(true)
        faqWebView.setWebChromeClient(WebChromeClient())
        val url = intent.getStringExtra("url")
        if(url == null) {
            faqWebView.loadUrl(this.resources.getString(R.string.faq_web_link))
        }else{
            faqWebView.loadUrl(url)
        }
    }

}