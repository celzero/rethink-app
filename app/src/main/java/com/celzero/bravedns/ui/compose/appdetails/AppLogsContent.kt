@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.appdetails
import androidx.compose.ui.draw.alpha
import com.celzero.bravedns.ui.compose.controls.*
import com.celzero.bravedns.ui.compose.fields.OriginalTextField
import com.celzero.bravedns.ui.compose.fields.OriginalFieldStyle
import androidx.appcompat.widget.SearchView
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.wireguard.WgControlState

/** SearchView's changed-query equality guard, including programmatic setQuery dispatch. */
class AppSearchState {
    var query by mutableStateOf(""); private set
    var queryHint:CharSequence by mutableStateOf("")
    var isEnabled by mutableStateOf(true)
    var focusReset by mutableIntStateOf(0);private set
    private var listener:SearchView.OnQueryTextListener?=null
    fun setOnQueryTextListener(value:SearchView.OnQueryTextListener){listener=value}
    fun setQuery(value:String,submit:Boolean){
        if(query!=value){query=value;listener?.onQueryTextChange(value)}
        if(submit && value.trim().isNotEmpty())listener?.onQueryTextSubmit(value)
    }
    fun clearFocus(){focusReset++}
}
class AppLogsState {
    val awlSearch=AppSearchState()
    val awlDelete=WgControlState()
    val awlAppDetailIcon1=WgControlState()
    val awlRecyclerConnection=AppDetailListState()
    val toggleGroup=WgControlState()
    val tbRecentToggleBtn=WgControlState()
    val tbDailyToggleBtn=WgControlState()
    val tbWeeklyToggleBtn=WgControlState()
    val timeButtons get()=listOf(tbRecentToggleBtn,tbDailyToggleBtn,tbWeeklyToggleBtn)
    var selectedTime by mutableIntStateOf(2)
    var onTime:(Int)->Unit={}
    var stopScrollGeneration by mutableIntStateOf(0)
}
@Composable fun AppLogsContent(b:AppLogsState) {
    val focus=LocalFocusManager.current;val scroll=rememberLazyListState()
    LaunchedEffect(b.awlSearch.focusReset){focus.clearFocus()}
    LaunchedEffect(b.stopScrollGeneration){scroll.stopScroll()}
    Surface(color=themeColor(R.attr.background)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                DrawableImage(b.awlAppDetailIcon1.drawable,Modifier.size(32.dp))
                OriginalTextField(b.awlSearch.query,{b.awlSearch.setQuery(it,false)},Modifier.weight(1f), style = OriginalFieldStyle.AppLogSearch,enabled=b.awlSearch.isEnabled,
                    placeholder={Text(b.awlSearch.queryHint.toString(),fontSize=14.sp)},singleLine=true,
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={b.awlSearch.setQuery(b.awlSearch.query,true)}),
                    trailingIcon={if(b.awlSearch.query.isNotEmpty())IconButton({b.awlSearch.setQuery("",false)}){Icon(painterResource(androidx.appcompat.R.drawable.abc_ic_clear_material),stringResource(R.string.lbl_cancel), tint = themeColor(androidx.appcompat.R.attr.colorControlNormal))}})
                if(b.awlDelete.isVisible)IconButton({b.awlDelete.performClick()}){Icon(painterResource(R.drawable.ic_delete),stringResource(R.string.ada_delete_logs_dialog_title), Modifier.alpha(.5f), tint = Color.Unspecified)}
            }
            if(b.toggleGroup.isVisible)FlowRow(Modifier.padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy((-1).dp)) {
                b.timeButtons.forEachIndexed{i,node->OriginalChip("activity_app_wise_ip_logs:" + listOf("tb_recent_toggle_btn", "tb_daily_toggle_btn", "tb_weekly_toggle_btn")[i], b.selectedTime==i, {b.onTime(i)}, segmentIndex=i, segmentCount=3, contentColor=node.textColor?.let { androidx.compose.ui.graphics.Color(it) }, backgroundTint=node.backgroundTintList, label={Text(node.text.toString())})}
            }
            b.awlRecyclerConnection.adapter?.let { adapter ->
                LazyColumn(Modifier.weight(1f),state=scroll){items(adapter.itemCount){adapter.RowAt(it)}}
            }
        }
    }
}
