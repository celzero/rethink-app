/*
Copyright 2021 RethinkDNS and its authors

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
package com.celzero.bravedns.glide

import android.content.Context
import android.os.Process
import android.util.Log
import com.bumptech.glide.request.FutureTarget
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS_LOG
import com.celzero.bravedns.util.Utilities
import java.io.File

/**
 * FavIconDownloader - Downloads the favicon of the DNS requests and store in the Glide cache. The
 * fav icon will later be retrieved by the DNS query list / by the Bottom sheet in the DNS log
 * screen.
 *
 * The runnable will be executed only if the show fav icon setting is turned on. In Settings -> DNS
 * -> Show fav icon (_TRUE_).
 */
class FavIconDownloader(val context: Context, private val url: String) : Runnable {

    companion object {
        // base-url for fav icon download
        const val FAV_ICON_DUCK_URL = "https://icons.duckduckgo.com/ip2/"
        const val FAV_ICON_NEXTDNS_BASE_URL = "https://favicons.nextdns.io/"
        const val FAV_ICON_SIZE = "@1x.png"

        fun getDomainUrlFromFdqnDuckduckgo(url: String): String {
            // Convert an FQDN like "www.example.co.uk." to an eTLD + 1 like "example.co.uk".
            val domainUrl = Utilities.getETldPlus1(url).toString()
            return constructFavUrlDuckDuckGo(domainUrl)
        }

        // Add duckduckgo format to download the favicon.
        // eg., https://icons.duckduckgo.com/ip2/google.com.ico
        fun constructFavUrlDuckDuckGo(url: String): String {
            return "${FAV_ICON_DUCK_URL}${url}.ico"
        }

        // Add nextdns format to download the favicon.
        // eg., https://favicons.nextdns.io/some.subdomain.rethinkdns.com@1x.png
        fun constructFavIcoUrlNextDns(url: String): String {
            return "$FAV_ICON_NEXTDNS_BASE_URL$url$FAV_ICON_SIZE"
        }
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
        // url will have . at end of the file, which needs to be removed.
        val fdqnUrl = url.dropLast(1)
        fetchFromNextDns(fdqnUrl)
        // val url = constructFavIcoUrlNextDns(fdqnUrl)
        // val url = constructFavUrl(fdqnUrl)
        // updateImage(fdqnUrl, getDomainUrlFromFdqn(fdqnUrl), true)
    }


    private fun fetchFromNextDns(url: String) {
        val subUrl = constructFavIcoUrlNextDns(url)
        val futureTarget: FutureTarget<File> =
            GlideApp.with(context.applicationContext)
                .downloadOnly()
                .load(subUrl)
                .submit(SIZE_ORIGINAL, SIZE_ORIGINAL)
        try {
            futureTarget.get()
            if (DEBUG) Log.d(LOG_TAG_DNS_LOG, "Glide, load success from nextdns for url: $url")
        } catch (e: Exception) {
            // on exception, initiate the download of fav icon from duckduckgo
            Log.i(LOG_TAG_DNS_LOG, "Glide, load failure from nextdns $subUrl")
            updateImage(constructFavUrlDuckDuckGo(url), getDomainUrlFromFdqnDuckduckgo(url), true)
        } finally {
            GlideApp.with(context.applicationContext).clear(futureTarget)
        }
    }

    // ref: https://github.com/bumptech/glide/issues/2972
    // https://github.com/bumptech/glide/issues/509
    private fun updateImage(subUrl: String, url: String, retry: Boolean) {
        val futureTarget: FutureTarget<File> =
            GlideApp.with(context.applicationContext)
                .downloadOnly()
                .load(subUrl)
                .submit(SIZE_ORIGINAL, SIZE_ORIGINAL)
        try {
            futureTarget.get()
            if (DEBUG) Log.d(LOG_TAG_DNS_LOG, "Glide, downloaded from duckduckgo $subUrl, $url")
        } catch (e: Exception) {
            // In case of failure the FutureTarget will throw an exception.
            // Will initiate the download of fav icon for the top level domain.
            if (retry) {
                if (DEBUG) Log.d(LOG_TAG_DNS_LOG, "Glide, download failed from duckduckgo $subUrl")
                updateImage(url, "", false)
            }
            Log.e(LOG_TAG_DNS_LOG, "Glide, no fav icon available for the url: $subUrl")
        } finally {
            GlideApp.with(context.applicationContext).clear(futureTarget)
        }
    }
}
