/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.statistics

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.compose.runtime.*
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import com.bumptech.glide.request.transition.Transition
import com.celzero.bravedns.glide.FavIconDownloader
import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_DNS

/** Retains trailing-period cleanup and all three cache-only favicon fallbacks. */
@Composable
internal fun statisticsFavIcon(context: Context, rawQuery: String?): Drawable? {
    var icon by remember(rawQuery) { mutableStateOf<Drawable?>(null) }
    DisposableEffect(context, rawQuery) {
        val query = rawQuery?.dropLastWhile { it == '.' }
        val target = object : CustomTarget<Drawable>(64, 64) {
            override fun onLoadFailed(errorDrawable: Drawable?) { icon = null }
            override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) { icon = resource }
            override fun onLoadCleared(placeholder: Drawable?) { icon = null }
        }
        var requested = false
        if (query != null && FavIconDownloader.isUrlAvailableInFailedCache(query) == null) {
            requested = true
            val nextDnsUrl = FavIconDownloader.constructFavIcoUrlNextDns(query)
            val duckDuckGoUrl = FavIconDownloader.constructFavUrlDuckDuckGo(query)
            val subDomainUrl = FavIconDownloader.getDomainUrlFromFdqnDuckduckgo(query)
            try {
                val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
                var request = Glide.with(context.applicationContext).load(nextDnsUrl)
                    .onlyRetrieveFromCache(true).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .transition(DrawableTransitionOptions.withCrossFade(factory))
                val errorRequest = cachedDuckduckgoIcon(context, duckDuckGoUrl, subDomainUrl)
                if (errorRequest != null) request = request.error(errorRequest)
                request.into(target)
            } catch (_: Exception) {
                Logger.d(LOG_TAG_DNS, "err loading icon, load flag instead")
                cachedDuckduckgoIcon(context, duckDuckGoUrl, subDomainUrl)?.into(target)
            }
        }
        onDispose { if (requested) Glide.with(context.applicationContext).clear(target) }
    }
    return icon
}
private fun cachedDuckduckgoIcon(context: Context, url: String, subDomainURL: String): RequestBuilder<Drawable>? = try {
    val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
    Glide.with(context.applicationContext).load(url).onlyRetrieveFromCache(true)
        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
        .error(Glide.with(context.applicationContext).load(subDomainURL).onlyRetrieveFromCache(true))
        .transition(DrawableTransitionOptions.withCrossFade(factory))
} catch (_: Exception) { null }
