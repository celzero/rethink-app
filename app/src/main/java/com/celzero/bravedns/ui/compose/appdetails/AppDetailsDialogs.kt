@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.appdetails
import com.celzero.bravedns.ui.compose.controls.*
import com.celzero.bravedns.ui.compose.fields.OriginalTextField
import com.celzero.bravedns.ui.compose.fields.OriginalFieldStyle
import android.app.Activity
import android.app.Dialog
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.themeColor
import com.celzero.bravedns.ui.compose.wireguard.setWgContent
import com.celzero.bravedns.util.UIUtils

class AppNotesDraft(initial:String) {
    var value by mutableStateOf(TextFieldValue(initial,TextRange(initial.length)))
    val text:CharSequence get()=value.text
    fun setText(text:String){value=TextFieldValue(text)}
    fun setSelection(index:Int){value=value.copy(selection=TextRange(index))}
}
fun filterAppNotes(old:TextFieldValue,next:TextFieldValue):TextFieldValue {
    if(next.text.length<=500)return next
    var start=0;while(start<minOf(old.text.length,next.text.length)&&old.text[start]==next.text[start])start++
    var suffix=0;while(suffix<old.text.length-start&&suffix<next.text.length-start&&old.text[old.text.lastIndex-suffix]==next.text[next.text.lastIndex-suffix])suffix++
    var keep=(500-start-suffix).coerceAtLeast(0).coerceAtMost(next.text.length-start-suffix)
    if(keep>0&&Character.isHighSurrogate(next.text[start+keep-1]))keep--
    val value=old.text.take(start)+next.text.substring(start,start+keep)+old.text.takeLast(suffix)
    return next.copy(text=value,selection=TextRange(minOf(next.selection.start,start+keep),minOf(next.selection.end,start+keep)),composition=null)
}
private fun nativeDialog(activity:Activity):Dialog=Dialog(activity,R.style.App_Dialog_NoDim).apply {
    requestWindowFeature(Window.FEATURE_NO_TITLE)
    window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window?.setDimAmount(0f)
    UIUtils.capDialogWidth(this)
}
fun showAppNotesDialog(activity:Activity,draft:AppNotesDraft,onSave:()->Unit,onCancel:()->Unit,onDismiss:()->Unit):Dialog {
    val dialog=nativeDialog(activity)
    dialog.setOnDismissListener{onDismiss()}
    dialog.setWgContent(activity){AppNotesContent(draft,{onSave();dialog.dismiss()},{onCancel();dialog.dismiss()})}
    dialog.show();return dialog
}
@Composable fun AppNotesContent(draft:AppNotesDraft,onSave:()->Unit,onCancel:()->Unit) {
    Surface(shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(24.dp).imePadding()) {
            Text(stringResource(R.string.lbl_notes),style=MaterialTheme.typography.headlineSmall)
            OriginalTextField(draft.value,{draft.value=filterAppNotes(draft.value,it)},Modifier.fillMaxWidth().weight(1f,false).testTag("app_notes_input"), style = OriginalFieldStyle.Notes,minLines=4,maxLines=10,placeholder={Text(stringResource(R.string.hint_notes))})
            Text(stringResource(R.string.ctbs_note_char_counter,draft.text.length,500),Modifier.align(Alignment.End))
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                OriginalButton("@dialog_negative", onCancel, Modifier.testTag("app_notes_cancel")){Text(stringResource(R.string.lbl_cancel))}
                OriginalButton("@dialog_positive", onSave, Modifier.testTag("app_notes_save")){Text(stringResource(R.string.lbl_save))}
            }
        }
    }
}
fun showAppDetailsPackageDialog(activity:Activity,apps:List<String>,onItem:(Int)->Unit) {
    val dialog=nativeDialog(activity);dialog.setCancelable(false);dialog.setCanceledOnTouchOutside(false)
    dialog.setWgContent(activity) {
        Surface(shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp)) {
                Text(stringResource(R.string.about_settings_app_info),style=MaterialTheme.typography.headlineSmall)
                Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState())){apps.forEachIndexed{i,name->Text(name,Modifier.fillMaxWidth().clickable{onItem(i)}.padding(vertical=12.dp))}}
                OriginalButton("@dialog_positive", {dialog.dismiss()}, Modifier.align(Alignment.End)){Text(stringResource(R.string.ada_noapp_dialog_positive))}
            }
        }
    };dialog.show()
}
