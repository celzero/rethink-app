package com.celzero.bravedns.database

import androidx.room.DatabaseView

@DatabaseView(
    "select appName, appCategory, isInternetAllowed, whiteListUniv1, isExcluded from AppInfo")
data class AppInfoView(var appName: String = "", var appCategory: String = "",
                       var isInternetAllowed: Boolean = true, var whiteListUniv1: Boolean = false,
                       var isExcluded: Boolean = false)
