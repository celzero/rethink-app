/*
 * Copyright 2023 RethinkDNS and its authors
 *
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.util

import Logger
import Logger.LOG_QR_CODE
import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Reader
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Encapsulates the logic of scanning a barcode from a file,
 *
 * @property contentResolver - Resolver to read the incoming data
 * @property reader - An instance of zxing's [Reader] class to parse the image
 */
class QrCodeFromFileScanner(
    private val contentResolver: ContentResolver,
    private val reader: Reader,
) {

    private fun scanBitmapForResult(source: Bitmap): Result {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val bBitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        return reader.decode(bBitmap, mapOf(DecodeHintType.TRY_HARDER to true))
    }

    private fun downscaleBitmap(source: Bitmap, scaledSize: Int): Bitmap {

        val originalWidth = source.width
        val originalHeight = source.height

        var newWidth = -1
        var newHeight = -1
        val multFactor: Float

        when {
            originalHeight > originalWidth -> {
                newHeight = scaledSize
                multFactor = originalWidth.toFloat() / originalHeight.toFloat()
                newWidth = (newHeight * multFactor).toInt()
            }
            originalWidth > originalHeight -> {
                newWidth = scaledSize
                multFactor = originalHeight.toFloat() / originalWidth.toFloat()
                newHeight = (newWidth * multFactor).toInt()
            }
            originalHeight == originalWidth -> {
                newHeight = scaledSize
                newWidth = scaledSize
            }
        }
        return Bitmap.createScaledBitmap(source, newWidth, newHeight, false)
    }

    private fun doScan(data: Uri): Result? {
        Logger.d(LOG_QR_CODE, "Starting to scan an image: $data")
        contentResolver.openInputStream(data).use { inputStream ->
            val originalBitmap =
                BitmapFactory.decodeStream(inputStream)
                    ?: throw IllegalArgumentException("Can't decode stream to Bitmap")

            return try {
                scanBitmapForResult(originalBitmap).also {
                    Logger.d(LOG_QR_CODE, "Found result in original image")
                }
            } catch (e: Exception) {
                Logger.e(
                    LOG_QR_CODE,
                    "Original image scan finished with error: $e, will try downscaled image"
                )
                val scaleBitmap = downscaleBitmap(originalBitmap, 500)
                scanBitmapForResult(originalBitmap).also { scaleBitmap.recycle() }
            } finally {
                originalBitmap.recycle()
            }
        }
    }

    /**
     * Attempts to parse incoming data
     *
     * @return result of the decoding operation
     * @throws NotFoundException when parser didn't find QR code in the image
     */
    suspend fun scan(data: Uri) = withContext(Dispatchers.Default) { doScan(data) }

    companion object {
        /**
         * Given a reference to a file, check if this file could be parsed by this class
         *
         * @return true if the file can be parsed, false if not
         */
        fun validContentType(contentResolver: ContentResolver, data: Uri): Boolean {
            return contentResolver.getType(data)?.startsWith("image/") == true
        }
    }
}
