package com.celzero.bravedns.ui.compose.blocklists
import androidx.compose.runtime.*
class BlocklistControl {
    var text by mutableStateOf("")
    var visible by mutableStateOf(true)
    var isEnabled by mutableStateOf(true)
    var isClickable by mutableStateOf(true)
    var isIndeterminate by mutableStateOf(true)
    var progress by mutableStateOf(0)
    var onClick by mutableStateOf<() -> Unit>({})
}
data class BlocklistPrompt(val title: String, val message: String, val positive: String, val confirm: () -> Unit, val negative: String, val cancel: () -> Unit, val neutral: String? = null, val neutralAction: () -> Unit = {})
class BlocklistConfigurationState {
    var prompt by mutableStateOf<BlocklistPrompt?>(null)
    var selectedTags by mutableStateOf<Set<Int>>(emptySet())
    var selectedAdvanced by mutableStateOf(false)
    var showAdvanced by mutableStateOf(false)
    var query by mutableStateOf("")
    var selectionFilter by mutableStateOf(0)
    val lbAdvSearchFilterIcon = BlocklistControl()
    val lbAdvancedFilterLabelTv = BlocklistControl()
    val lbBlocklistApplyBtn = BlocklistControl()
    val lbBlocklistCancelBtn = BlocklistControl()
    val lbCancelDownloadBtn = BlocklistControl()
    val lbConfigureLayout = BlocklistControl()
    val lbDownloadBtn = BlocklistControl()
    val lbDownloadLayout = BlocklistControl().apply { visible = false }
    val lbDownloadProgress = BlocklistControl().apply { visible = false }
    val lbDownloadProgressRemote = BlocklistControl().apply { visible = false }
}
