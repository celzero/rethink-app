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

package com.celzero.bravedns.util

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import okhttp3.*
import org.json.JSONObject
import java.io.IOException

class HttpRequestHelper{

    companion object{

        //fixme - Come up with a logic where onResponse should call for an interface and proceed
        //with the result returned from server.
        private fun serverCheckForUpdate(context: Context, url: String, persistentState:PersistentState) {
            val client = OkHttpClient()
            val request = Request.Builder()
                .url(url)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.d(LOG_TAG, "onFailure -  ${call.isCanceled()}, ${call.isExecuted()}")
                }

                override fun onResponse(call: Call, response: Response) {
                    val stringResponse = response.body!!.string()
                    //creating json object
                    val jsonObject = JSONObject(stringResponse)
                    val responseVersion = jsonObject.getInt("version")
                    val updateValue = jsonObject.getBoolean("update")
                    persistentState.lastAppUpdateCheck = System.currentTimeMillis()
                    Log.i(LOG_TAG, "Server response for the new version download is true, version number-  $updateValue")
                    if (responseVersion == 1) {
                        if (updateValue) {
                            // TODO handle
                        } else {
                            // TODO handle
                        }
                    }
                    response.body!!.close()
                    client.connectionPool.evictAll()
                }
            })
        }

        fun downloadBlockListFiles(context: Context) : DownloadManager{
            val downloadManager = context.getSystemService(AppCompatActivity.DOWNLOAD_SERVICE) as DownloadManager
            val uri: Uri = Uri.parse(Constants.JSON_DOWNLOAD_BLOCKLIST_LINK)
            val request = DownloadManager.Request(uri)
            request.setDestinationInExternalFilesDir(context, Constants.DOWNLOAD_PATH, Constants.FILE_TAG_NAME)
            Log.i(LOG_TAG, "Path - ${context.filesDir.canonicalPath}${Constants.DOWNLOAD_PATH}${Constants.FILE_TAG_NAME}")
            downloadManager.enqueue(request)
            return downloadManager
        }

        fun checkStatus(cursor: Cursor): String {
            //column for status
            val columnIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val status = cursor.getInt(columnIndex)
            //column for reason code if the download failed or paused
            val columnReason = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
            val reason = cursor.getInt(columnReason)
            //get the download filename
            //val filenameIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_FILENAME)
            //val filename = cursor.getString(filenameIndex)
            var statusText = ""
            var reasonText = ""
            when (status) {
                DownloadManager.STATUS_FAILED -> {
                    statusText = "STATUS_FAILED"
                    when (reason) {
                        DownloadManager.ERROR_CANNOT_RESUME -> reasonText = "ERROR_CANNOT_RESUME"
                        DownloadManager.ERROR_DEVICE_NOT_FOUND -> reasonText = "ERROR_DEVICE_NOT_FOUND"
                        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> reasonText = "ERROR_FILE_ALREADY_EXISTS"
                        DownloadManager.ERROR_FILE_ERROR -> reasonText = "ERROR_FILE_ERROR"
                        DownloadManager.ERROR_HTTP_DATA_ERROR -> reasonText = "ERROR_HTTP_DATA_ERROR"
                        DownloadManager.ERROR_INSUFFICIENT_SPACE -> reasonText = "ERROR_INSUFFICIENT_SPACE"
                        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> reasonText = "ERROR_TOO_MANY_REDIRECTS"
                        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> reasonText = "ERROR_UNHANDLED_HTTP_CODE"
                        DownloadManager.ERROR_UNKNOWN -> reasonText = "ERROR_UNKNOWN"
                    }
                }
                DownloadManager.STATUS_PAUSED -> {
                    statusText = "STATUS_PAUSED"
                    when (reason) {
                        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> reasonText = "PAUSED_QUEUED_FOR_WIFI"
                        DownloadManager.PAUSED_UNKNOWN -> reasonText = "PAUSED_UNKNOWN"
                        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> reasonText = "PAUSED_WAITING_FOR_NETWORK"
                        DownloadManager.PAUSED_WAITING_TO_RETRY -> reasonText = "PAUSED_WAITING_TO_RETRY"
                    }
                }
                DownloadManager.STATUS_PENDING -> statusText = "STATUS_PENDING"
                DownloadManager.STATUS_RUNNING -> statusText = "STATUS_RUNNING"
                DownloadManager.STATUS_SUCCESSFUL -> {
                    statusText = "STATUS_SUCCESSFUL"
                    //reasonText = "Filename:\n$filename"
                }
            }
            /*  val toast = Toast.makeText(
                  this@DownloadDataActivity,
                  """
                    $statusText
                    $reasonText
                    """.trimIndent(),
                  Toast.LENGTH_LONG
              )
              toast.setGravity(Gravity.TOP, 25, 400)
              toast.show()*/
            return statusText
        }

    }


}