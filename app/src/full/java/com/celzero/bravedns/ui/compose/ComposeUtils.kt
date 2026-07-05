package com.celzero.bravedns.ui.compose

import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.core.graphics.drawable.toBitmap

@Composable
fun rememberDrawablePainter(drawable: Drawable?): Painter? {
    return remember(drawable) { drawable?.toBitmap()?.asImageBitmap()?.let { BitmapPainter(it) } }
}
