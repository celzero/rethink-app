/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.dialog
import android.app.Activity
import android.os.Bundle
import android.view.Window
import androidx.appcompat.app.AppCompatDialog
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.compose.rules.CustomLanIpContent
import com.celzero.bravedns.ui.compose.rules.CustomLanIpState
import com.celzero.bravedns.ui.compose.wireguard.setWgContent

class CustomLanIpDialog(private val activity: Activity, private val persistentState: PersistentState, themeId: Int) : AppCompatDialog(activity, themeId) {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val state = CustomLanIpState(context, persistentState, ::dismiss)
        setWgContent(activity) { CustomLanIpContent(state.binding) }
        setCancelable(true)
    }
}
