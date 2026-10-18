/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.purchase
import android.graphics.Typeface
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
@Composable internal fun SubscriptionCelebration(title: String, message: String, parties: List<Party>) {
    val font = remember { FontFamily(Typeface.create("serif-monospace", Typeface.NORMAL)) }
    Box(Modifier.fillMaxSize().padding(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 20.dp)) {
            Text(title, Modifier.fillMaxWidth(), fontSize = 24.sp, fontFamily = font, textAlign = TextAlign.Center, color = themeColor(R.attr.primaryTextColor))
            Text(message, Modifier.fillMaxWidth().padding(top = 8.dp), fontSize = 16.sp, fontFamily = font, textAlign = TextAlign.Center, color = themeColor(R.attr.primaryLightColorText))
        }
        KonfettiView(modifier = Modifier.fillMaxSize(), parties = parties)
    }
}
