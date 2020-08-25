package com.celzero.bravedns.ui

import android.content.Intent
import android.media.Image
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.celzero.bravedns.R


class AboutFragment : Fragment(), View.OnClickListener {

    private lateinit var websiteTxt : TextView
    private lateinit var twitterTxt : TextView
    private lateinit var githubTxt : TextView
    private lateinit var  blogTxt : TextView
    private lateinit var mailTxt : TextView
    private lateinit var telegramTxt : TextView
    private lateinit var faqTxt : TextView
    private lateinit var mozillaImg : ImageView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view: View = inflater.inflate(R.layout.fragment_about, container, false)
        initView(view)
        return view
    }

    private fun initView(view: View) {
        websiteTxt = view.findViewById(R.id.about_website)
        twitterTxt = view.findViewById(R.id.about_twitter)
        githubTxt = view.findViewById(R.id.about_github)
        blogTxt = view.findViewById(R.id.about_blog)
        mailTxt = view.findViewById(R.id.about_mail)
        telegramTxt = view.findViewById(R.id.about_telegram)
        faqTxt = view.findViewById(R.id.about_faq)
        mozillaImg = view.findViewById(R.id.mozilla_img)

        websiteTxt.setOnClickListener(this)
        twitterTxt.setOnClickListener(this)
        githubTxt.setOnClickListener(this)
        blogTxt.setOnClickListener(this)
        mailTxt.setOnClickListener(this)
        telegramTxt.setOnClickListener(this)
        faqTxt.setOnClickListener(this)
        mozillaImg.setOnClickListener(this)
    }

    override fun onClick(view: View?) {
        if(view!! == telegramTxt){
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://www.telegram.me/bravedns"))
            startActivity(intent)
        }else if(view == blogTxt){
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://brave.imprint.to/"))
            startActivity(intent)
        }else if(view == faqTxt){
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bravedns.com/faq"))
            startActivity(intent)
        }else if(view == githubTxt){
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/celzero/brave-android-app"))
            startActivity(intent)
        }else if(view == mailTxt){
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("mailto:" + "hello@celzero.com"))
            intent.putExtra(Intent.EXTRA_SUBJECT, "[BraveDNS]:")
            startActivity(intent)
        }else if(view == twitterTxt){
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://twitter.com/bravedns"))
            startActivity(intent)
        }else if(view == websiteTxt){
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bravedns.com/"))
            startActivity(intent)
        }else if(view == mozillaImg){
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.mozilla.org/builders/"))
            startActivity(intent)
        }
    }

}
