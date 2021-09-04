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
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.FutureTarget
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS_LOG
import com.celzero.bravedns.util.Utilities
import java.io.File

/**
 * FavIconDownloader - Downloads the favicon of the DNS requests and store in the
 * Glide cache. The fav icon will later be retrieved by the DNS query list / by the
 * Bottom sheet in the DNS log screen.
 *
 * The runnable will be executed only if the show fav icon setting is turned on.
 * In Settings -> DNS -> Show fav icon (_TRUE_).
 */
class FavIconDownloader(val context: Context, private val url: String) : Runnable {

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
        // url will have . at end of the file, which needs to be removed.
        val fdqnUrl = url.dropLast(1)
        val url = constructFavUrl(fdqnUrl)
        updateImage(url, getDomainUrlFromFdqn(fdqnUrl), true)
    }

    private fun getDomainUrlFromFdqn(url: String): String {
        // Convert an FQDN like "www.example.co.uk." to an eTLD + 1 like "example.co.uk".
        val domainUrl = Utilities.getETldPlus1(url).toString()
        return constructFavUrl(domainUrl)
    }

    // Add duckduckgo format to download the favicon.
    // eg., https://icons.duckduckgo.com/ip2/google.com.ico
    private fun constructFavUrl(url: String): String {
        return "${Constants.FAV_ICON_URL}${url}.ico"
    }


    private fun updateImage(subUrl: String, url: String, retry: Boolean) {
        val futureTarget: FutureTarget<File> = GlideApp.with(
            context.applicationContext).downloadOnly().diskCacheStrategy(
            DiskCacheStrategy.AUTOMATIC).load(subUrl).submit(SIZE_ORIGINAL, SIZE_ORIGINAL)
        try {
            val file = futureTarget.get()
            if (DEBUG) Log.d(LOG_TAG_DNS_LOG,
                             "Glide - success() -$subUrl, $url")
        } catch (e: Exception) {
            // In case of failure the FutureTarget will throw an exception.
            // Will initiate the download of fav icon for the top level domain.
            if (retry) {
                if (DEBUG) Log.d(LOG_TAG_DNS_LOG, "Glide - onLoadFailed() -$subUrl")
                updateImage(url, "", false)
            }
            Log.e(LOG_TAG_DNS_LOG,
                  "Glide - Got ExecutionException waiting for background downloadOnly")
        } finally {
            GlideApp.with(context.applicationContext).clear(futureTarget)
        }
    }
}
