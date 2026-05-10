/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.streams

/**
 * Registry of the streaming apps Rethink TV recognises out of the box.
 *
 * Why a hard-coded list rather than scanning every installed app?
 *
 *   * Android TV devices typically have 10–40 apps installed; showing
 *     them all in a flat list makes the "which of these is a
 *     streamer?" question worse, not better.
 *   * The user's mental model is "Netflix", not
 *     "com.netflix.ninja". Mapping package names to friendly labels
 *     here lets the UI say "Netflix" without depending on the OS to
 *     have a usable app label.
 *   * Streamers are the single biggest source of "but VPN broke
 *     playback" support tickets. Foregrounding them in their own tab
 *     is the most useful TV-specific affordance we can add.
 *
 * Apps not in this map are still reachable from the (future) full
 * "All apps" screen — they're just not surfaced on this tab.
 *
 * Maintenance: when a new streaming service ships an Android TV app,
 * add its package name here. The set is intentionally small and
 * curated rather than community-sourced; we'd rather miss a niche
 * service than mislabel something. Phone-only package names are
 * included alongside TV-specific ones because some streamers ship a
 * single APK for both form factors (e.g. Netflix's
 * `com.netflix.mediaclient` runs on phones and tablets while the
 * TV-only flavour is `com.netflix.ninja`).
 */
internal object KnownStreamers {

    /**
     * Package name → user-facing service name.
     *
     * Multiple package names can map to the same service when the
     * vendor ships separate APKs for phone / tablet / TV / Fire TV.
     */
    val packageToName: Map<String, String> = mapOf(
        // Netflix
        "com.netflix.ninja" to "Netflix",
        "com.netflix.mediaclient" to "Netflix",

        // Disney+
        "com.disney.disneyplus" to "Disney+",

        // Amazon Prime Video
        "com.amazon.amazonvideo.livingroom" to "Prime Video",
        "com.amazon.avod.thirdpartyclient" to "Prime Video",
        "com.amazon.avod" to "Prime Video",

        // YouTube (TV + main; ad-blocking on YouTube is the marquee
        // use case for this whole project, so we list every YouTube
        // SKU we know about).
        "com.google.android.youtube.tv" to "YouTube",
        "com.google.android.youtube.tvkids" to "YouTube Kids",
        "com.google.android.youtube" to "YouTube",
        "com.amazon.firetv.youtube" to "YouTube (Fire TV)",
        "com.google.android.apps.youtube.music" to "YouTube Music",

        // Apple TV+
        "com.apple.atve.androidtv.appletv" to "Apple TV",
        "com.apple.atve.amazon.appletv" to "Apple TV",

        // Hulu / Max / HBO
        "com.hulu.livingroomplus" to "Hulu",
        "com.hulu.plus" to "Hulu",
        "com.hbo.hbonow" to "HBO Max",
        "com.wbd.stream" to "Max",

        // Paramount / Peacock / Starz
        "com.cbs.ott" to "Paramount+",
        "com.peacocktv.peacockandroid" to "Peacock",
        "com.starz.android.starzplay" to "Starz",

        // Self-hosted libraries
        "com.plexapp.android" to "Plex",
        "org.jellyfin.androidtv" to "Jellyfin",
        "tv.emby.embyatv" to "Emby",

        // Music / live / anime
        "com.spotify.tv.android" to "Spotify",
        "com.spotify.music" to "Spotify",
        "tv.twitch.android.app" to "Twitch",
        "com.crunchyroll.crunchyroid" to "Crunchyroll",
        "com.google.android.videos" to "Google TV",
    )

    fun friendlyName(packageName: String): String? = packageToName[packageName]

    fun isStreamer(packageName: String): Boolean =
        packageToName.containsKey(packageName)
}
