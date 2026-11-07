package com.celzero.bravedns.ui.compose.blocklists
import androidx.compose.runtime.*
class LocalBlocklistControl {
    var text by mutableStateOf("")
    var color by mutableStateOf<Int?>(null)
    var drawable by mutableStateOf<Int?>(null)
    var visible by mutableStateOf(true)
    var isEnabled by mutableStateOf(true)
    var alpha by mutableStateOf(1f)
    var onClick by mutableStateOf<() -> Unit>({})
}
data class LocalBlocklistPrompt(val title: String, val message: String, val positive: String, val cancelable: Boolean, val confirm: () -> Unit, val cancel: () -> Unit)
class LocalBlocklistsState {
    var prompt by mutableStateOf<LocalBlocklistPrompt?>(null)
    val lbbsCheckDownload = LocalBlocklistControl()
    val lbbsCheckDownloadImg = LocalBlocklistControl()
    val lbbsCheckDownloadProgress = LocalBlocklistControl().apply { visible = false }
    val lbbsConfigure = LocalBlocklistControl()
    val lbbsCopy = LocalBlocklistControl()
    val lbbsDelete = LocalBlocklistControl()
    val lbbsDownload = LocalBlocklistControl()
    val lbbsDownloadImg = LocalBlocklistControl()
    val lbbsDownloadLl = LocalBlocklistControl()
    val lbbsDownloadProgress = LocalBlocklistControl().apply { visible = false }
    val lbbsEnable = LocalBlocklistControl()
    val lbbsHeading = LocalBlocklistControl()
    val lbbsRedownload = LocalBlocklistControl()
    val lbbsRedownloadImg = LocalBlocklistControl()
    val lbbsRedownloadProgress = LocalBlocklistControl().apply { visible = false }
    val lbbsSearch = LocalBlocklistControl()
    val lbbsVersion = LocalBlocklistControl()
}
