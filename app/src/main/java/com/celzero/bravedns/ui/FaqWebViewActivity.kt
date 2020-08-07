package com.celzero.bravedns.ui

import android.os.Bundle
import android.os.PersistableBundle
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.celzero.bravedns.R

class FaqWebViewActivity  : AppCompatActivity(){

    private lateinit var faqWebView : WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_faq_webview_layout)
        faqWebView = findViewById(R.id.faq_webview)
        val url = intent.getStringExtra("url")
        if(url == null) {
            faqWebView.loadUrl(this.resources.getString(R.string.faq_web_link))
        }else{
            faqWebView.loadUrl(url)
        }
    }

}