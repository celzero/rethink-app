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
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.load.engine.executor.GlideExecutor
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.module.AppGlideModule
import okhttp3.OkHttpClient
import java.io.InputStream
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
        val threadCount = Math.min(Runtime.getRuntime().availableProcessors(), 1)
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
        val client: OkHttpClient =
            OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .connectTimeout(3, TimeUnit.SECONDS)
                .build()

        val factory: OkHttpUrlLoader.Factory = OkHttpUrlLoader.Factory(client)
        registry.replace(GlideUrl::class.java, java.io.InputStream::class.java, factory)
        // handle com.bumptech.glide.Registry$NoModelLoaderAvailableException:
        // Failed to find any ModelLoaders registered for model class: class kotlin.Unit
        registry.replace(Unit::class.java, InputStream::class.java, UnitModelLoaderFactory())
    }

    // handle com.bumptech.glide.Registry$NoModelLoaderAvailableException for kotlin.Unit
    inner class UnitModelLoaderFactory : ModelLoaderFactory<Unit, InputStream> {
        override fun build(multiFactory: MultiModelLoaderFactory): ModelLoader<Unit, InputStream> {
            return object : ModelLoader<Unit, InputStream> {
                override fun handles(model: Unit): Boolean {
                    return true
                }

                override fun buildLoadData(
                    model: Unit,
                    width: Int,
                    height: Int,
                    options: com.bumptech.glide.load.Options
                ): ModelLoader.LoadData<InputStream>? {
                    return null
                }
            }
        }

        override fun teardown() {
            // no-op
        }
    }
}
