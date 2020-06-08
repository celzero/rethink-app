package com.celzero.bravedns.adapter

import android.content.pm.ApplicationInfo

data class Apk(val appInfo: String,
               val appName: String,
               val packageName: String,
               val version: String? = "")