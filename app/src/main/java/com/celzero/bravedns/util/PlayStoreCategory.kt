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

// Hash codes of Play Store category names used for efficient lookups, not magic numbers
@Suppress("MagicNumber")
enum class PlayStoreCategory(val rawValue: Int) {
    OTHER(0),
    ART_AND_DESIGN(1798113474),
    AUTO_AND_VEHICLES(-201031457),
    BEAUTY(1955267708),
    BOOKS_AND_REFERENCE(652448174),
    BUSINESS(-364204096),
    COMICS(1993477496),
    COMMUNICATION(2080958390),
    DATING(2009386219),
    EDUCATION(-1799129208),
    ENTERTAINMENT(-678717592),
    EVENTS(2056967449),
    FINANCE(-135275590),
    FOOD_AND_DRINK(-267698865),
    HEALTH_AND_FITNESS(704829917),
    HOUSE_AND_HOME(-908401466),
    LIBRARIES_AND_DEMO(-1893543311),
    LIFESTYLE(-1796047851),
    MAPS_AND_NAVIGATION(1381037124),
    MEDICAL(1658758769),
    MUSIC_AND_AUDIO(702385524),
    NEWS_AND_MAGAZINES(1916976715),
    PARENTING(561964760),
    PERSONALIZATION(1779216900),
    PHOTOGRAPHY(-470332035),
    PRODUCTIVITY(-953829166),
    SHOPPING(438165864),
    SOCIAL(-1843721363),
    SPORTS(-1842431105),
    TOOLS(80007611),
    TRAVEL_AND_LOCAL(856995806),
    VIDEO_PLAYERS(289768878),
    WEATHER(1941423060),
    GAMES("GAMES".hashCode());

    companion object {
        private val map = entries.associateBy(PlayStoreCategory::rawValue)
        const val GENERAL_GAMES_CATEGORY_NAME = "GAMES"

        fun fromCategoryName(name: String): PlayStoreCategory {
            return map[name.hashCode()] ?: OTHER
        }
    }
}
