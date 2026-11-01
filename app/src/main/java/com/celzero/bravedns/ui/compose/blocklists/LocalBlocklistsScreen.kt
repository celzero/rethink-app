package com.celzero.bravedns.ui.compose.blocklists

import com.celzero.bravedns.ui.compose.controls.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.settings.NoDimDialogWindow
import com.celzero.bravedns.ui.compose.themeColor

@Composable fun LocalBlocklistsScreen(b: LocalBlocklistsState) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(top = 10.dp, bottom = 30.dp).size(60.dp, 3.dp).background(themeColor(R.attr.border)))
        SelectionContainer { Row(Modifier.padding(5.dp).semantics { heading() }, verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_local_blocklist), null, tint = Color.Unspecified)
            Text(b.lbbsHeading.text.ifEmpty { stringResource(R.string.lbbs_heading) }, Modifier.padding(start = 5.dp), fontSize = 20.sp, color = themeColor(R.attr.primaryLightColorText))
        } }
        Spacer(Modifier.height(20.dp))
        LocalAction("enable", b.lbbsEnable, b.lbbsEnable.text.ifEmpty { stringResource(R.string.lbl_disabled) }, R.drawable.ic_cross_accent)
        LocalAction("configure", b.lbbsConfigure, stringResource(R.string.lbbs_configure), R.drawable.ic_right_arrow_white)
        if (b.lbbsDownloadLl.visible) {
            LocalAction("check", b.lbbsCheckDownload, stringResource(R.string.lbbs_update_check), R.drawable.ic_update_small, b.lbbsCheckDownloadProgress.visible, b.lbbsCheckDownloadImg.visible)
            LocalAction("download", b.lbbsDownload, stringResource(R.string.lbbs_download), R.drawable.ic_update_small, b.lbbsDownloadProgress.visible, b.lbbsDownloadImg.visible)
            LocalAction("redownload", b.lbbsRedownload, stringResource(R.string.lbbs_redownload), R.drawable.ic_update_small, b.lbbsRedownloadProgress.visible, b.lbbsRedownloadImg.visible)
        }
        LocalAction("search", b.lbbsSearch, stringResource(R.string.lbbs_search), R.drawable.ic_search)
        LocalAction("copy", b.lbbsCopy, stringResource(R.string.lbbs_copy), R.drawable.ic_copy)
        LocalAction("delete", b.lbbsDelete, stringResource(R.string.lbl_delete), R.drawable.ic_delete_small)
        SelectionContainer { Text(b.lbbsVersion.text.ifEmpty { stringResource(R.string.lbbs_heading) }, Modifier.padding(20.dp), fontSize = 12.sp, color = themeColor(R.attr.primaryLightColorText)) }
    }
    b.prompt?.let { prompt ->
        NoDimDialogWindow(onDismissRequest = { if (prompt.cancelable) b.prompt = null }, properties = DialogProperties(dismissOnBackPress = prompt.cancelable, dismissOnClickOutside = prompt.cancelable)) {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = themeColor(R.attr.background)) {
                Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                    Text(prompt.title, style = MaterialTheme.typography.headlineSmall, color = themeColor(R.attr.primaryTextColor))
                    Text(prompt.message, Modifier.padding(vertical = 16.dp), color = themeColor(R.attr.primaryTextColor))
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OriginalButton("@dialog_negative", onClick = { b.prompt = null; prompt.cancel() }, Modifier.testTag("local-dialog-cancel")) { Text(stringResource(R.string.lbl_cancel), color = themeColor(R.attr.secondaryTextColor)) }
                        OriginalButton("@dialog_positive", onClick = { b.prompt = null; prompt.confirm() }, Modifier.testTag("local-dialog-confirm")) { Text(prompt.positive, color = themeColor(androidx.appcompat.R.attr.colorPrimary)) }
                    }
                }
            }
        }
    }
}

@Composable private fun LocalAction(key: String, control: LocalBlocklistControl, label: String, icon: Int, progress: Boolean = false, imageVisible: Boolean = true) {
    if (!control.visible) return
    Row(Modifier.fillMaxWidth().padding(10.dp).alpha(control.alpha).testTag("local-$key").clickable(enabled = control.isEnabled, onClick = control.onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 20.sp, color = control.color?.let(::Color) ?: themeColor(R.attr.primaryTextColor))
        if (imageVisible) Icon(painterResource(control.drawable ?: icon), null, tint = Color.Unspecified)
        if (progress) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
    }
}
