/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.statistics

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.style.TextAlign
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.utilities.utilityTextStyle
import com.celzero.bravedns.ui.compose.utilities.utilityDimensionSp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.appdetails.AppConnectionsPresenter

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PagedStatisticsScreen(title: String, subtitle: String?, empty: Boolean,
    presenter: AppConnectionsPresenter?, chips: @Composable () -> Unit = {},
    extraCount: Int = 0, extraRow: @Composable (Int) -> Unit = {},
    selectableTitle: Boolean = false) {
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val titleStyle = utilityTextStyle(appearance = androidx.appcompat.R.style.TextAppearance_AppCompat_Subhead,
        size = utilityDimensionSp(context, R.dimen.extra_large_font_text_view), family = "sans-serif-smallcaps", lineExtraDp = 5f)
    Surface {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxWidth().padding(15.dp)) {
                Text(stringResource(R.string.app_name_small_case).uppercase(LocalLocale.current.platformLocale), Modifier.alpha(.5f).padding(horizontal = 7.dp), color = themeColor(R.attr.colorOnSurface), style = utilityTextStyle(appearance = R.style.TextAppearance_Heading_HomeScreen))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (selectableTitle) {
                        // Domain connection headings were selectable in the original TextView.
                        SelectionContainer(Modifier.weight(1f)) { Text(title, Modifier.padding(10.dp), color = themeColor(R.attr.primaryLightColorText), style = titleStyle) }
                    } else {
                        Text(title, Modifier.weight(1f).padding(10.dp), color = themeColor(R.attr.primaryLightColorText), style = titleStyle)
                    }
                    if (subtitle != null) Text(subtitle, Modifier.padding(horizontal = 12.dp), color = themeColor(R.attr.primaryLightColorText), style = utilityTextStyle(size = utilityDimensionSp(context, R.dimen.default_font_text_view)))
                }
                chips()
            }
            if (empty) Column(Modifier.fillMaxWidth().testTag("statistics_empty").padding(30.dp)) {
                Text(stringResource(R.string.blocklist_update_check_failure), Modifier.fillMaxWidth().padding(10.dp), textAlign = TextAlign.Center, style = utilityTextStyle(size = utilityDimensionSp(context, R.dimen.heading_font_text_view)))
                Image(painterResource(R.drawable.illustrations_no_record), null, Modifier.fillMaxWidth())
            } else LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("statistics_list"), state = listState) {
                // Pending pages have no header placeholders that could clamp a restored row index.
                presenter?.let { rows -> items(rows.itemCount) { rows.RowAt(it) } }
                items(extraCount) { extraRow(it) }
            }
        }
    }
}
