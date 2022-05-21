/*
 * Copyright 2021 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ConfigureRethinkPlusAdapter
import com.celzero.bravedns.automaton.RethinkBlocklistsManager
import com.celzero.bravedns.data.FileTag
import com.celzero.bravedns.databinding.ActivityConfigureRethinkplusBinding
import com.celzero.bravedns.download.AppDownloadManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.Constants.Companion.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.koin.android.ext.android.inject
import java.io.IOException

class ConfigureRethinkPlusActivity : AppCompatActivity(R.layout.activity_configure_rethinkplus),
                                     SearchView.OnQueryTextListener {

    private val b by viewBinding(ActivityConfigureRethinkplusBinding::bind)
    private val persistentState by inject<PersistentState>()
    private var layoutManager: RecyclerView.LayoutManager? = null
    private var recylcerAdapter: ConfigureRethinkPlusAdapter? = null

    private val fileTags: MutableList<FileTag> = ArrayList()
    private val filters = MutableLiveData<Filters>()

    companion object {
        private const val EMPTY_SUBGROUP = "others"
    }

    class Filters {
        var query: String = ""
        var groups: MutableSet<String> = mutableSetOf()
        var subGroups: MutableSet<String> = mutableSetOf()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)
        init()
        initClickListeners()
        initObserver()
    }

    private fun initObserver() {
        filters.observe(this) {
            recylcerAdapter?.filter?.filter(it.query)
        }
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    fun filterObserver(): MutableLiveData<Filters> {
        return filters
    }

    private fun init() {
        val type = intent.getIntExtra(Constants.BLOCKLIST_LOCATION_INTENT_EXTRA, 0)
        val stamp = intent.getStringExtra(Constants.BLOCKLIST_STAMP_INTENT_EXTRA)
        Log.d(LoggerConstants.LOG_TAG_DNS, "Rethink Type: $type, with the stamp: $stamp")
        readJson(type)
        b.rethinkPlusSearchView.setOnQueryTextListener(this)
    }

    private fun initClickListeners() {
        b.rethinkPlusSearchFilterIcon.setOnClickListener {
            openFilterBottomSheet()
        }
    }

    private fun openFilterBottomSheet() {
        val bottomSheetFragment = RethinkPlusFilterBottomSheetFragment(this, fileTags)
        bottomSheetFragment.show(supportFragmentManager, bottomSheetFragment.tag)
    }

    private fun readJson(type: Int) {
        try {
            val dir = if (type == AppDownloadManager.DownloadType.REMOTE.id) {
                Utilities.remoteBlocklistFile(this, REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                              persistentState.remoteBlocklistTimestamp)
            } else {
                Utilities.localBlocklistFile(this, LOCAL_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                                             persistentState.localBlocklistTimestamp)
            } ?: return

            val file = Utilities.blocklistFile(dir.absolutePath,
                                               Constants.ONDEVICE_BLOCKLIST_FILE_TAG) ?: return
            Log.d(LoggerConstants.LOG_TAG_DNS, "file directory path: ${dir.absolutePath}, ${file.absolutePath}")
            val jsonString = file.bufferedReader().use { it.readText() }
            val entries: JsonObject = Gson().fromJson(jsonString, JsonObject::class.java)

            entries.entrySet().forEach {
                val t = Gson().fromJson(it.value, FileTag::class.java)
                // add subg tag as "others" if its empty
                if (t.subg.isEmpty()) {
                    t.subg = EMPTY_SUBGROUP
                }
                t.simpleViewTag = RethinkBlocklistsManager.getSimpleBlocklistDetails(t.uname, t.subg)
                fileTags.add(t)
            }

            fileTags.sortBy { it.group }

            setFileTagAdapter()
        } catch (ioException: IOException) {
            Log.e(LoggerConstants.LOG_TAG_DNS,
                  "Failure reading json file for timestamp: ${persistentState.remoteBlocklistTimestamp}",
                  ioException)
            showFailureDialog()
        }
    }

    private fun setFileTagAdapter() {
        recylcerAdapter = ConfigureRethinkPlusAdapter(this, fileTags)
        recylcerAdapter?.updateFileTag(fileTags)

        layoutManager = LinearLayoutManager(this)
        b.recyclerConfigureRethinkPlus.layoutManager = layoutManager
        b.recyclerConfigureRethinkPlus.adapter = recylcerAdapter
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        addQueryToFilters(query)
        return false
    }

    override fun onQueryTextChange(query: String): Boolean {
        addQueryToFilters(query)
        return false
    }

    private fun addQueryToFilters(query: String) {
        val a = filterObserver()
        if (a.value == null) {
            val temp = Filters()
            temp.query = query
            filters.postValue(temp)
            return
        }

        // asserting, as there is a null check
        a.value!!.query = query
        filters.postValue(a.value)
    }

    private fun showFailureDialog() {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Failure")
        builder.setMessage("Issue loading configuration files")
        builder.setCancelable(false)
        builder.setPositiveButton("Quit") { _, _ ->
            finish()
        }
        builder.create().show()
    }
}
