@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.firewallapps
import com.celzero.bravedns.ui.compose.controls.*
import android.app.Activity
import android.app.Dialog
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import com.celzero.bravedns.ui.compose.themeColor
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.activity.AppListActivity
import com.celzero.bravedns.ui.compose.wireguard.WgControlState
import com.celzero.bravedns.ui.compose.wireguard.setWgContent
import com.celzero.bravedns.util.UIUtils

class FirewallFilterState {
    val fsApply=WgControlState();val fsClear=WgControlState()
    var parent by mutableIntStateOf(0)
    var categories by mutableStateOf<List<String>>(emptyList())
    val selectedCategories=mutableStateListOf<String>()
    var sort by mutableStateOf(AppListActivity.SortOption.NAME)
    var onParent:(Int)->Unit={};var onCategory:(String,Boolean)->Unit={_,_->};var onSort:(AppListActivity.SortOption)->Unit={}
    fun selectParent(id:Int){if(parent==id)return;parent=id;onParent(id)}
    fun selectCategory(value:String,selected:Boolean){if(selectedCategories.contains(value)==selected)return;if(selected)selectedCategories.add(value)else selectedCategories.remove(value);onCategory(value,selected)}
    fun selectSort(value:AppListActivity.SortOption){if(sort==value)return;sort=value;onSort(value)}
}
@Composable fun FirewallFilterContent(b:FirewallFilterState) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(10.dp).padding(bottom = 40.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp, bottom = 20.dp).size(60.dp, 3.dp).background(themeColor(R.attr.border)))
        Text(stringResource(R.string.fapps_filter_filter_heading),modifier=Modifier.padding(horizontal = 15.dp, vertical = 10.dp), color=themeColor(R.attr.secondaryTextColor), fontSize=14.sp)
        FlowRow(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 5.dp, bottom = 2.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(R.string.lbl_all,R.string.fapps_filter_parent_installed,R.string.fapps_filter_parent_system).forEachIndexed{i,label->
                OriginalChip("item_chip_filter:(anonymous)", b.parent==i, {b.selectParent(i)}, label={Text(stringResource(label))}, modifier=Modifier.testTag("firewall_parent_$i"))
            }
        }
        Text(stringResource(R.string.fapps_filter_categories_heading),modifier=Modifier.padding(horizontal = 15.dp, vertical = 10.dp), color=themeColor(R.attr.secondaryTextColor), fontSize=14.sp)
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            b.categories.forEach{category->OriginalChip("item_chip_filter:(anonymous)", category in b.selectedCategories, {b.selectCategory(category,category !in b.selectedCategories)}, label={Text(category)}, modifier=Modifier.testTag("firewall_category_$category"))}
        }
        Text(stringResource(R.string.fapps_filter_sort_heading),modifier=Modifier.padding(horizontal = 15.dp, vertical = 10.dp), color=themeColor(R.attr.secondaryTextColor), fontSize=14.sp)
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            val labels=listOf(R.string.fapps_filter_sort_name,R.string.fapps_filter_sort_package,R.string.fapps_filter_sort_uid)
            AppListActivity.SortOption.entries.forEachIndexed{i,sort->OriginalChip("item_chip_filter:(anonymous)", b.sort==sort, {b.selectSort(sort)}, label={Text(stringResource(labels[i]))}, modifier=Modifier.testTag("firewall_sort_${sort.name}"))}
        }
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
            OriginalButton("bottom_sheet_firewall_sort_filter:fs_clear", {b.fsClear.performClick()}, Modifier.testTag("firewall_filter_clear")){Text(stringResource(R.string.fapps_filter_clear_btn))}
            OriginalButton("bottom_sheet_firewall_sort_filter:fs_apply", {b.fsApply.performClick()}, Modifier.testTag("firewall_filter_apply")){Text(stringResource(R.string.lbl_apply))}
        }
    }
}
fun showFirewallInfoDialog(activity:Activity) {
    val dialog=Dialog(activity,R.style.App_Dialog_NoDim)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    dialog.setWgContent(activity){
        Surface(shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(20.dp)) {
                Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState())) {
                    Text(activity.getString(R.string.fapps_info_dialog_message),Modifier.padding(10.dp),fontSize=14.sp)
                    HorizontalDivider()
                    listOf(R.drawable.ic_firewall_wifi_on_grey to R.string.fapps_info_unmetered_msg,
                        R.drawable.ic_firewall_data_on_grey to R.string.fapps_info_metered_msg,
                        R.drawable.ic_firewall_bypass_off to R.string.fapps_info_bypass_msg,
                        R.drawable.ic_bypass_dns_firewall_off to R.string.fapps_info_bypass_dns_firewall_msg,
                        R.drawable.ic_firewall_exclude_off to R.string.fapps_info_exclude_msg,
                        R.drawable.ic_firewall_lockdown_off to R.string.fapps_info_isolate_msg).forEach{(icon,text)->
                        Row(Modifier.padding(top=10.dp),verticalAlignment=Alignment.CenterVertically){Icon(painterResource(icon),null,Modifier.padding(5.dp));Text(stringResource(text),Modifier.padding(5.dp),fontSize=14.sp)}
                    }
                }
                OriginalButton("@dialog_positive", {dialog.dismiss()}, Modifier.align(Alignment.End)){Text(stringResource(R.string.fapps_info_dialog_positive_btn))}
            }
        }
    }
    dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);dialog.window?.setDimAmount(0f)
    UIUtils.capDialogWidth(dialog);dialog.show()
}
