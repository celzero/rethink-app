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
class FavIconDownloader(val context: Context, val url: String) : Runnable {

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
        val extractURL = url.substringAfter(Constants.FAV_ICON_URL).dropLast(4)
        val cacheKey = Utilities.getETldPlus1(extractURL).toString()
        val glideURL = "${Constants.FAV_ICON_URL}${cacheKey}.ico"
        updateImage(url, glideURL, true)
    }


    private fun updateImage(url: String, subUrl: String, retry: Boolean) {
        val futureTarget: FutureTarget<File> = GlideApp.with(
            context.applicationContext).downloadOnly().diskCacheStrategy(
            DiskCacheStrategy.AUTOMATIC).load(url).submit(SIZE_ORIGINAL, SIZE_ORIGINAL)
        try {
            val file = futureTarget.get()
            if (DEBUG) Log.d(LOG_TAG_DNS_LOG,
                             "Glide - success() -$url, $subUrl, ${file.absoluteFile}")
        } catch (e: Exception) {
            // In case of failure the FutureTarget will throw an exception.
            // Will initiate the download of fav icon for the top level domain.
            if (retry) {
                if (DEBUG) Log.d(LOG_TAG_DNS_LOG, "Glide - onLoadFailed() -$url")
                updateImage(subUrl, "", false)
            }
            Log.e(LOG_TAG_DNS_LOG,
                  "Glide - Got ExecutionException waiting for background downloadOnly ${e.message}",
                  e)
        } finally {
            GlideApp.with(context.applicationContext).clear(futureTarget)
        }
    }
}
