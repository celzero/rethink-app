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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ConfigureRethinkPlusAdapter
import com.celzero.bravedns.customdownloader.BlocklistDownloadInterface
import com.celzero.bravedns.customdownloader.RetrofitManager
import com.celzero.bravedns.data.FileTag
import com.celzero.bravedns.databinding.ActivityConfigureRethinkplusBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants.Companion.FILETAG_TEMP_DOWNLOAD_URL
import com.celzero.bravedns.util.Themes
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.koin.android.ext.android.inject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.converter.gson.GsonConverterFactory

class ConfigureRethinkPlusActivity : AppCompatActivity(R.layout.activity_configure_rethinkplus),
                                     SearchView.OnQueryTextListener {

    private val b by viewBinding(ActivityConfigureRethinkplusBinding::bind)
    private val persistentState by inject<PersistentState>()
    private var layoutManager: RecyclerView.LayoutManager? = null
    private var recylcerAdapter: ConfigureRethinkPlusAdapter? = null

    private val fileTags: MutableList<FileTag> = ArrayList()
    private val filters = MutableLiveData<Filters>()

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
        downloadFileTagIfNeeded()
        b.rethinkPlusSearchView.setOnQueryTextListener(this)
        recylcerAdapter = ConfigureRethinkPlusAdapter(this, fileTags)
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

    private fun downloadFileTagIfNeeded() {
        val retrofit = RetrofitManager.getBlocklistBaseBuilder().addConverterFactory(
            GsonConverterFactory.create()).build()
        val retrofitInterface = retrofit.create(BlocklistDownloadInterface::class.java)
        val request = retrofitInterface.downloadRemoteBlocklistFile(FILETAG_TEMP_DOWNLOAD_URL)

        request.enqueue(object : Callback<JsonObject?> {
            override fun onResponse(call: Call<JsonObject?>, response: Response<JsonObject?>) {
                if (response.isSuccessful) {
                    parseJson(response.body())
                } else {
                    // no-op
                }
            }

            override fun onFailure(call: Call<JsonObject?>, t: Throwable) {
                // TODO: handle the failure cases
            }

        })
    }

    private fun parseJson(jsonObject: JsonObject?) {
        jsonObject ?: return

        jsonObject.entrySet().forEach {
            val t = Gson().fromJson(it.value, FileTag::class.java)
            fileTags.add(t)
        }

        fileTags.sortBy { it.group }

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
        a.value?.query = query
        filters.postValue(a.value)
    }
}
