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
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Priority
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.data.DataFetcher
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.engine.executor.GlideExecutor
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.module.AppGlideModule
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Defines the options to use when initializing Glide within an application. As of now, limit for
 * memory cache and disk cache is added. By default, Glide uses LRUCache for both memory and disk.
 * Ref - https://bumptech.github.io/glide/doc/configuration.html
 */
@GlideModule
class RethinkGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        val memoryCacheSizeBytes = 1024 * 1024 * 30L // 30 MB
        builder.setMemoryCache(LruResourceCache(memoryCacheSizeBytes))

        // 350M disk cache is enough to store 35K favicons of 10KB size on average.
        // 35K is a good guesstimate of unique domains a device may hit over 30 days
        // Lowering disk-cache means an increase in data use (some reported, 600MB+)
        val diskCacheSizeBytes = 1024 * 1024 * 350L // 350 MB
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, diskCacheSizeBytes))
        // default disk cache executors thread count is 1, which is not enough for disk cache
        // operations. Increasing the thread count to available processors.
        val diskCacheBuilder = GlideExecutor.newDiskCacheBuilder()
        val threadCount = Math.max(Runtime.getRuntime().availableProcessors(), 1)
        diskCacheBuilder.setThreadCount(threadCount)
        diskCacheBuilder.setName("g-disk-cache")
        builder.setDiskCacheExecutor(diskCacheBuilder.build())

        val srcCacheBuilder = GlideExecutor.newSourceBuilder()
        srcCacheBuilder.setThreadCount(threadCount * 2)
        srcCacheBuilder.setName("g-source-cache")
        builder.setSourceExecutor(srcCacheBuilder.build())

        builder.setLogLevel(Log.ERROR)
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        super.registerComponents(context, glide, registry)

        val client: OkHttpClient = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 404) {
                    // avoid crashing on 404 by returning a success-like response.
                    // Glide's OkHttpStreamFetcher (v5.0.5) crashes on 404 with a FileSystemException.
                    // ref: https://github.com/celzero/rethink-app/issues/1404

                    val rewrittenResponse = response.newBuilder()
                        .code(200)
                        .body("".toResponseBody(null))
                        .build()
                    // Close the original body first to prevent a resource leak
                    response.body.close()
                    return@addInterceptor rewrittenResponse
                }
                response
            }
            .build()
        registry.replace(GlideUrl::class.java, InputStream::class.java, OkHttpUrlLoader.Factory(client))

        // Prevent NoModelLoaderAvailableException when Glide.with(...).load(Unit) is called.
        //
        // WHY prepend() and not replace():
        //   replace() only replaces an *existing* registration. kotlin.Unit is not a built-in
        //   Glide type so replace() silently does nothing, leaving Unit completely unregistered.
        //   Glide then throws NoModelLoaderAvailableException at ResourceCacheGenerator when it
        //   tries to build cache keys, before buildLoadData() is ever called.
        //
        //   prepend() inserts at the front of the loader chain unconditionally, so the
        //   NoOpUnitLoader is found first and returns a stable LoadData (backed by a no-op
        //   DataFetcher that calls onLoadFailed) Glide treats that as a clean cache miss and
        //   falls through to the placeholder / error drawable without crashing.
        registry.prepend(Unit::class.java, InputStream::class.java, NoOpUnitLoaderFactory())
    }

    inner class NoOpUnitLoaderFactory : ModelLoaderFactory<Unit, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<Unit, InputStream> =
            NoOpUnitLoader()
        override fun teardown() = Unit
    }

    inner class NoOpUnitLoader : ModelLoader<Unit, InputStream> {
        override fun handles(model: Unit): Boolean = true

        override fun buildLoadData(
            model: Unit,
            width: Int,
            height: Int,
            options: Options
        ): ModelLoader.LoadData<InputStream> =
            // A stable, content-free cache key so Glide can generate cache keys without crashing,
            // paired with a fetcher that immediately reports "no data" via onLoadFailed.
            ModelLoader.LoadData(NoOpKey, NoOpFetcher)
    }

    private object NoOpKey : Key {
        override fun updateDiskCacheKey(messageDigest: MessageDigest) {
            messageDigest.update("NoOpUnitKey".toByteArray(Charsets.UTF_8))
        }
        override fun equals(other: Any?) = other is NoOpKey
        override fun hashCode() = javaClass.hashCode()
    }

    private object NoOpFetcher : DataFetcher<InputStream> {
        override fun loadData(priority: Priority, callback: DataFetcher.DataCallback<in InputStream>) {
            callback.onLoadFailed(Exception("Unit model, no image to load"))
        }
        override fun cleanup() = Unit
        override fun cancel()  = Unit
        override fun getDataClass(): Class<InputStream> = InputStream::class.java
        override fun getDataSource(): DataSource = DataSource.LOCAL
    }
}
