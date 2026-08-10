package com.celzero.bravedns.sponsor.provider

import android.content.Context
import android.content.Intent
import com.celzero.bravedns.ui.activity.FragmentHostActivity
import com.celzero.bravedns.sponsor.ui.SponsorFragment

class GooglePlaySponsorProvider : SponsorProvider {

    override fun openSponsor(context: Context) {
        val intent = FragmentHostActivity.createIntent(
            context = context,
            fragmentClass = SponsorFragment::class.java
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
