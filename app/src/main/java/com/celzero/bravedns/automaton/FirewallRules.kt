package com.celzero.bravedns.automaton

import android.content.Context
import android.content.pm.PackageInfo

class FirewallRules(packageInfo: PackageInfo, wifi_blocked :Boolean , other_blocked : Boolean, changed : Boolean, context : Context) {

    var packageInfo : PackageInfo ?= null
    var name : String ?= null
    var system : Boolean = false
    var disabled : Boolean = false
    var wifi_blocked : Boolean = false
    var other_blocked : Boolean = false
    var changed : Boolean = false
    var screen_off_blocked : Boolean = false





}