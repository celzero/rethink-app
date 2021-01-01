package com.celzero.bravedns

import android.app.Activity
import android.util.Log
import com.celzero.bravedns.service.AppUpdater
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.util.Constants
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

/*
 * Copyright (C) 2021 Daniel Wolf (Ch4t4r)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * You can contact the developer at daniel.wolf@frostnerd.com.
 */
class NonStoreAppUpdater(val baseURL:String, private val persistentState: PersistentState):AppUpdater {

    override fun checkForAppUpdate(isUserInitiated: Boolean, activity: Activity, listener: AppUpdater.InstallStateListener) {
        val url = baseURL + BuildConfig.VERSION_CODE

        val client = OkHttpClient()
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(Constants.LOG_TAG, "onFailure -  ${call.isCanceled()}, ${call.isExecuted()}")
                if(isUserInitiated) {
                    listener.onUpdateCheckFailed()
                }
                call.cancel()
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val stringResponse = response.body!!.string()
                    //creating json object
                    val jsonObject = JSONObject(stringResponse)
                    val responseVersion = jsonObject.getInt("version")
                    val updateValue = jsonObject.getBoolean("update")
                    val latestVersion = jsonObject.getInt("latest")
                    persistentState.lastAppUpdateCheck = System.currentTimeMillis()
                    Log.i(Constants.LOG_TAG, "Server response for the new version download is true, version number-  $latestVersion")
                    if (responseVersion == 1) {
                        if (updateValue) {
                            listener.onUpdateAvailable()
                        } else {
                            listener.onUpToDate()
                        }
                    }
                    response.close()
                    client.connectionPool.evictAll()
                } catch (e: Exception) {
                    if (isUserInitiated) {
                        listener.onUpdateCheckFailed()
                    }
                }
            }
        })
    }

    override fun completeUpdate() {
    }

    override fun unregisterListener(listener: AppUpdater.InstallStateListener) {
    }
}
