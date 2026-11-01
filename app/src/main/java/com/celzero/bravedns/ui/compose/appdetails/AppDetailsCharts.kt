/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.appdetails

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.graphics.drawable.toBitmap
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.stats.CountryInsightsMapper
import com.celzero.bravedns.util.WorldMapPaths

/** Native glyph rendering; never creates a legacy ImageView. */
@Composable
fun DrawableImage(drawable: Drawable?, modifier: Modifier = Modifier) {
    drawable ?: return
    val bitmap = remember(drawable) { drawable.toBitmap(96, 96).asImageBitmap() }
    Image(bitmap, null, modifier)
}

/** Actual legacy DonutChartView renormalizes top slices to a complete ring. */
internal fun appDonutSweeps(fractions: List<Float>): List<Float> {
    val total = fractions.sumOf { it.toDouble() }.toFloat()
    return if (total <= 0f) fractions.map { 0f } else fractions.map { it / total * 360f }
}

/** Shared offline map: existing quantized assets and country math remain byte-identical. */
@Composable
internal fun AppCountryMap(items: List<AppConnection>) {
    val base = themeColor(R.attr.colorSurfaceContainerHighest)
    val highlight = themeColor(R.attr.accentGood)
    val outline = themeColor(R.attr.secondaryTextColor)
    val stats = remember(items) { items.mapNotNull { item -> CountryInsightsMapper.toCountryCode(item.flag)?.let { it to item.count } }.toMap() }
    val maximum = stats.values.maxOrNull() ?: 0
    val resources = LocalResources.current
    val summary = stats.entries.take(3).joinToString(", ") { resources.getString(R.string.ci_desc, it.key, it.value.toString()) }
    val description = if (summary.isEmpty()) stringResource(R.string.cd_stats_country_map)
        else stringResource(R.string.two_argument_colon, stringResource(R.string.cd_stats_country_map), summary)
    val paths = remember {
        WorldMapPaths.PATHS.mapNotNull { (code, encoded) ->
            val path = Path()
            var hadRing = false
            encoded.split("|").forEach { ring ->
                val points = ring.trim().split(" ")
                if (points.size >= 4) {
                    var first = true
                    points.forEach { point ->
                        val xy = point.split(",")
                        if (xy.size == 2) {
                            val x = xy[0].toFloat() / WorldMapPaths.QUANT_SCALE
                            val y = xy[1].toFloat() / WorldMapPaths.QUANT_SCALE
                            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                        }
                    }
                    path.close(); hadRing = true
                }
            }
            if (hadRing) code to path else null
        }
    }
    Canvas(Modifier.fillMaxWidth().aspectRatio(WorldMapPaths.MAP_WIDTH.toFloat() / WorldMapPaths.MAP_HEIGHT)
        .semantics { contentDescription = description }) {
        val scale = minOf(size.width / WorldMapPaths.MAP_WIDTH, size.height / WorldMapPaths.MAP_HEIGHT)
        val dx = (size.width - WorldMapPaths.MAP_WIDTH * scale) / 2f
        val dy = (size.height - WorldMapPaths.MAP_HEIGHT * scale) / 2f
        drawContext.canvas.save()
        drawContext.canvas.translate(dx, dy)
        drawContext.canvas.scale(scale, scale)
        paths.forEach { (code, path) ->
            val value = stats[code]
            val intensity = if (maximum <= 0 || value == null) -1f else (value.toFloat() / maximum).coerceIn(0f, 1f)
            val color = if (intensity < 0) base else highlight.copy(alpha = (60 + intensity * 195).toInt() / 255f)
            drawPath(path, color)
            drawPath(path, outline, style = Stroke(1f / scale))
        }
        drawContext.canvas.restore()
    }
}
