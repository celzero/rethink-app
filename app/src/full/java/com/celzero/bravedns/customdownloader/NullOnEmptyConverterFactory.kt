/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.customdownloader

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_IAB
import okhttp3.MediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.reflect.Type

/**
 * Retrofit [Converter.Factory] that guards against non-JSON server responses before
 * they reach [retrofit2.converter.gson.GsonConverterFactory].
 *
 * ### Cases handled
 *
 * | Scenario | Action |
 * |---|---|
 * | `content-length == 0` | return `null` immediately nobody to parse |
 * | Chunked response whose body is empty or whitespace-only | return `null` |
 * | Body starts with `<` (HTML/XML error page) | return `null`, log warning |
 * | Body is whitespace-only after trim | return `null` |
 * | Body starts with `{` or `[` (valid JSON) | forward to Gson delegate unchanged |
 * | Any other non-JSON content | return `null`, log warning |
 *
 * ### Registration order (CRITICAL)
 *
 * ```kotlin
 * Retrofit.Builder()
 *     .addConverterFactory(SafeResponseConverterFactory())  // MUST be first
 *     .addConverterFactory(GsonConverterFactory.create(lenientGson))
 *     .build()
 * ```
 *
 * ### Null-safety at call sites
 * All [retrofit2.http.POST] / [retrofit2.http.GET] methods in [IBillingServerApi] already
 * declare `Response<JsonObject?>?` return types, so a `null` body from this factory is
 * handled gracefully by existing null-checks at every call site.
 */
class SafeResponseConverterFactory : Converter.Factory() {

    companion object {
        private const val TAG = "SafeResponseConverter"
    }

    override fun responseBodyConverter(
        type: Type,
        annotations: Array<Annotation>,
        retrofit: Retrofit
    ): Converter<ResponseBody, *> {
        val delegate = retrofit.nextResponseBodyConverter<Any>(this, type, annotations)
        return Converter<ResponseBody, Any?> { body ->
            if (body.contentLength() == 0L) {
                Logger.d(LOG_IAB, "$TAG: empty body (content-length=0), returning null")
                return@Converter null
            }

            // bufferedSource().peek() gives us a non-destructive view so the delegate
            // can still read the full body afterward.
            val source    = body.source()
            source.request(Long.MAX_VALUE) // buffer everything
            val buffered  = source.buffer.clone() // snapshot
            val rawString = buffered.readUtf8().trim()

            if (rawString.isEmpty()) {
                Logger.d(LOG_IAB, "$TAG: empty body after buffering, returning null")
                return@Converter null
            }

            val firstChar = rawString[0]
            when {
                firstChar == '{' || firstChar == '[' -> {
                    val mediaType: MediaType? = body.contentType()
                    val freshBody = rawString.toResponseBody(mediaType)
                    delegate.convert(freshBody)
                }
                firstChar == '<' -> {
                    // HTML / XML error page (e.g. proxy error, 502 page)
                    Logger.w(LOG_IAB, "$TAG: HTML/XML body received, not JSON, returning null. " +
                        "Preview: ${rawString.take(120)}")
                    null
                }
                else -> {
                    // Plain text, "error: ...", or any other non-JSON content
                    Logger.w(LOG_IAB, "$TAG: non-JSON body received, returning null. " +
                        "Preview: ${rawString.take(120)}")
                    null
                }
            }
        }
    }
}
