/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.chrome

import com.celzero.bravedns.ui.compose.resources.obtainOrderedStyledAttributes

import com.celzero.bravedns.ui.compose.controls.*
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.NoDimDialogWindow
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.home.HomeTourAnchors
import com.celzero.bravedns.ui.compose.home.homeTourAnchor
import com.google.android.material.R as MaterialR
import kotlin.math.roundToInt

internal data class RootNavigationItem(val id: Int, val icon: Int, val label: Int)
internal val rootNavigationItems = listOf(
    RootNavigationItem(R.id.homeScreenFragment, R.drawable.ic_home_black_24dp, R.string.txt_home),
    RootNavigationItem(R.id.summaryStatisticsFragment, R.drawable.ic_statistics, R.string.title_statistics),
    RootNavigationItem(R.id.rethinkPlus, R.drawable.ic_rethink_plus_sparkle, R.string.rpn_title),
    RootNavigationItem(R.id.configureFragment, R.drawable.ic_settings, R.string.lbl_configure),
    RootNavigationItem(R.id.aboutFragment, R.drawable.ic_about, R.string.title_about),
)

@Composable
fun HomeBottomNavigation(
    selectedItem: Int,
    anchors: HomeTourAnchors,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    onSelect: (Int) -> Unit,
) {
    NavigationBar(
        modifier = modifier.testTag("root_navigation").homeTourAnchor(R.id.nav_view, anchors),
        // Frost deliberately makes the shared nav token translucent. The anchored
        // bar must still paint a solid backdrop, including its system inset strip.
        containerColor = themeColor(R.attr.navBarColor).copy(alpha = 1f),
        tonalElevation = 0.dp,
        // Keep the system strip inside the opaque NavigationBar surface. Padding an
        // outer, unpainted wrapper left a gap through which destinations could scroll.
        windowInsets = windowInsets,
    ) {
        rootNavigationItems.forEach { item ->
            NavigationBarItem(
                selected = selectedItem == item.id,
                onClick = { onSelect(item.id) },
                icon = { Icon(painterResource(item.icon), null, Modifier.size(24.dp)) },
                label = {
                    Text(stringResource(item.label), maxLines = 1,
                        style = rootNavigationLabelStyle(selectedItem == item.id))
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = themeColor(R.attr.colorOnSecondaryContainer),
                    selectedTextColor = themeColor(com.google.android.material.R.attr.colorOnSurface),
                    indicatorColor = themeColor(R.attr.colorSecondaryContainer),
                    unselectedIconColor = themeColor(R.attr.colorOnSurfaceVariant),
                    unselectedTextColor = themeColor(R.attr.colorOnSurfaceVariant),
                ),
                modifier = Modifier.testTag("root_nav_${item.id}").homeTourAnchor(item.id, anchors),
            )
        }
    }
}

/**
 * Material 1.14 NavigationBarItemView applies its own label policy after TextAppearance:
 * it removes font scaling, adds BOLD to the active face and disables font padding.
 * Keep this local to the nav labels; other app typography must continue to scale.
 */
@Composable
private fun rootNavigationLabelStyle(selected: Boolean): TextStyle {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    return remember(context, density, configuration, selected) {
        rootNavigationLabelTextStyle(context, density, selected)
    }
}

internal fun rootNavigationLabelTextStyle(context: Context, density: Density, selected: Boolean): TextStyle {
    val widget = context.obtainOrderedStyledAttributes(MaterialR.style.Widget_Material3_BottomNavigationView,
        intArrayOf(MaterialR.attr.itemTextAppearanceActive, MaterialR.attr.itemTextAppearanceInactive,
            MaterialR.attr.itemTextAppearanceActiveBoldEnabled, MaterialR.attr.labelFontScalingEnabled))
    val appearance: Int
    val activeBold: Boolean
    val scaleLabels: Boolean
    try {
        appearance = widget.getResourceId(if (selected) 0 else 1, MaterialR.style.TextAppearance_Material3_LabelMedium)
        activeBold = widget.getBoolean(2, true)
        scaleLabels = widget.getBoolean(3, false)
    } finally { widget.recycle() }
    val attributes = context.obtainOrderedStyledAttributes(appearance,
        intArrayOf(android.R.attr.textSize, android.R.attr.fontFamily, android.R.attr.textStyle, android.R.attr.letterSpacing))
    try {
        val size = TypedValue()
        check(attributes.getValue(0, size)) { "Navigation label TextAppearance has no text size" }
        // MaterialResources.getUnscaledTextSize rounds raw SP × physical density to pixels.
        val pixels = if (!scaleLabels && size.complexUnit == TypedValue.COMPLEX_UNIT_SP) {
            (TypedValue.complexToFloat(size.data) * context.resources.displayMetrics.density).roundToInt().toFloat()
        } else attributes.getDimension(0, 0f)
        val family = attributes.getString(1) ?: "sans-serif"
        val flags = attributes.getInt(2, Typeface.NORMAL)
        val base = Typeface.create(family, flags)
        val face = if (selected) Typeface.create(base, if (activeBold) Typeface.BOLD else Typeface.NORMAL) else base
        val fallbackWeight = if (selected && activeBold) {
            if (family.contains("medium")) 800 else 700
        } else if (family.contains("medium")) 500 else 400
        val nativeWeight = if (Build.VERSION.SDK_INT >= 28) face.weight else 0
        return TextStyle(
            fontSize = with(density) { pixels.toSp() },
            fontFamily = FontFamily(face),
            fontWeight = FontWeight(nativeWeight.takeIf { it in 1..1000 } ?: fallbackWeight),
            fontStyle = if (face.isItalic) FontStyle.Italic else FontStyle.Normal,
            // Android's letterSpacing is em, so it follows the unscaled label size.
            letterSpacing = attributes.getFloat(3, 0f).em,
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )
    } finally { attributes.recycle() }
}

/** Local window state: the original AlertDialogs were not persisted through recreation. */
class HomeChromeDialog(
    val title: String,
    val message: String,
    val positiveLabel: String,
    val negativeLabel: String? = null,
    val dismissible: Boolean = true,
    private val onPositive: () -> Unit = {},
    private val onNegative: () -> Unit = {},
) {
    private var closed = false
    fun positive(onDismiss: () -> Unit) = finish(onPositive, onDismiss)
    fun negative(onDismiss: () -> Unit) = finish(onNegative, onDismiss)
    fun dismiss(onDismiss: () -> Unit) { if (dismissible) finish({}, onDismiss) }
    private fun finish(action: () -> Unit, onDismiss: () -> Unit) {
        if (closed) return
        closed = true
        action() // Preserve the original button listener before dismissal.
        onDismiss()
    }
}

@Composable
fun HomeChromeDialogContent(dialog: HomeChromeDialog, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { dialog.dismiss(onDismiss) },
        title = { Text(dialog.title) },
        text = { NoDimDialogWindow(); Text(dialog.message) },
        confirmButton = {
            OriginalButton("@dialog_positive", onClick = { dialog.positive(onDismiss) }, modifier = Modifier.testTag("root_dialog_positive")) {
                Text(dialog.positiveLabel)
            }
        },
        dismissButton = if (dialog.negativeLabel == null) null else ({
            OriginalButton("@dialog_negative", onClick = { dialog.negative(onDismiss) }, modifier = Modifier.testTag("root_dialog_negative")) {
                Text(dialog.negativeLabel)
            }
        }),
        properties = DialogProperties(dismissOnBackPress = dialog.dismissible, dismissOnClickOutside = dialog.dismissible),
    )
}
