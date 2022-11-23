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
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.module.AppGlideModule

/**
 * Defines the options to use when initializing Glide within an application. As of now, limit for
 * memory cache and disk cache is added. By default, Glide uses LRUCache for both memory and disk.
 * Ref - https://bumptech.github.io/glide/doc/configuration.html
 */
@GlideModule
class RethinkGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        val memoryCacheSizeBytes = 1024 * 1024 * 30 // 30 MB
        builder.setMemoryCache(LruResourceCache(memoryCacheSizeBytes.toLong()))

        // 350M disk cache is enough to store 35K favicons of 10KB size on average.
        // 35K is a good guesstimate of unique domains a device may hit over 30 days
        // Lowering disk-cache means an increase in data use (some reported, 600MB+)
        val diskCacheSizeBytes = 1024 * 1024 * 350 // 350 MB
        builder.setDiskCache(InternalCacheDiskCacheFactory(context, diskCacheSizeBytes.toLong()))

        builder.setLogLevel(Log.ERROR)
    }
}
