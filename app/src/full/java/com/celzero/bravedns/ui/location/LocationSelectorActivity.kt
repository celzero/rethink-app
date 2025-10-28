/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.location
/*
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ActivityLocationSelectorBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities.isAtleastQ
import androidx.core.graphics.drawable.toDrawable
import org.koin.android.ext.android.inject
import kotlin.getValue

class LocationSelectorActivity : AppCompatActivity(R.layout.activity_location_selector), CountryAdapter.OnServerSelectionChangeListener {

    private val b by viewBinding(ActivityLocationSelectorBinding::bind)
    private val persistentState by inject<PersistentState>()
    private lateinit var countryAdapter: CountryAdapter
    private val countries = mutableListOf<Country>()
    private var currentSelectionCount = 0

    companion object {
        private const val MAX_SELECTIONS = 5
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)


        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        setupRecyclerView()
        loadSampleData()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupRecyclerView() {
        b.rvCountries.layoutManager = LinearLayoutManager(this)
        countryAdapter = CountryAdapter(countries, this)
        b.rvCountries.adapter = countryAdapter
    }

    override fun onServerSelectionChanged(server: ServerLocation, isSelected: Boolean) {
        if (isSelected && currentSelectionCount >= MAX_SELECTIONS) {
            Toast.makeText(this, "Maximum 5 servers can be selected", Toast.LENGTH_SHORT).show()
            return
        }

        if (isSelected && currentSelectionCount == 0) {
            Toast.makeText(this, "At least one server should be selected", Toast.LENGTH_SHORT).show()
            return
        }

        server.isSelected = isSelected

        val previousCount = currentSelectionCount
        currentSelectionCount = getTotalSelectedServers()

        animateCountChange(previousCount, currentSelectionCount)

        // Find and update specific items instead of full refresh
        countries.forEachIndexed { index, country ->
            if (country.servers.contains(server)) {
                countryAdapter.notifyItemChanged(index)
                return@forEachIndexed
            }
        }
    }

    private fun getTotalSelectedServers(): Int {
        return countries.sumOf { country ->
            country.servers.count { it.isSelected }
        }
    }

    private fun animateCountChange(fromCount: Int, toCount: Int) {
        // Scale animation for the count text
        val scaleX = ObjectAnimator.ofFloat(b.tvSelectedCount, "scaleX", 1.0f, 1.2f, 1.0f)
        val scaleY = ObjectAnimator.ofFloat(b.tvSelectedCount, "scaleY", 1.0f, 1.2f, 1.0f)
        scaleX.duration = 400
        scaleY.duration = 400

        // Animate the number change
        val countAnimator = ValueAnimator.ofInt(fromCount, toCount).apply {
            duration = 300
            addUpdateListener { animation ->
                val animatedValue = animation.animatedValue as Int
                val text = "$animatedValue server${if (animatedValue != 1) "s" else ""} selected"
                b.tvSelectedCount.text = text
            }
        }

        scaleX.start()
        scaleY.start()
        countAnimator.start()
    }

    private fun loadSampleData() {
        countries.clear()
        //val winProxyServers = RpnProxyManager.getWinServers()
        // Sample data for United States
        val usServers = listOf(
            ServerLocation("us-ny", "New York", "25ms"),
            ServerLocation("us-ca", "California", "35ms"),
            ServerLocation("us-tx", "Texas", "40ms"),
            ServerLocation("us-fl", "Florida", "45ms")
        )
        countries.add(Country("us", "United States", "flag_us", usServers))

        // Sample data for United Kingdom
        val ukServers = listOf(
            ServerLocation("uk-london", "London", "15ms"),
            ServerLocation("uk-manchester", "Manchester", "20ms"),
            ServerLocation("uk-edinburgh", "Edinburgh", "22ms")
        )
        countries.add(Country("uk", "United Kingdom", "flag_uk", ukServers))

        // Sample data for Germany
        val deServers = listOf(
            ServerLocation("de-berlin", "Berlin", "18ms"),
            ServerLocation("de-munich", "Munich", "20ms"),
            ServerLocation("de-frankfurt", "Frankfurt", "16ms"),
            ServerLocation("de-hamburg", "Hamburg", "19ms")
        )
        countries.add(Country("de", "Germany", "flag_de", deServers))

        // Sample data for Japan
        val jpServers = listOf(
            ServerLocation("jp-tokyo", "Tokyo", "12ms"),
            ServerLocation("jp-osaka", "Osaka", "15ms")
        )
        countries.add(Country("jp", "Japan", "flag_jp", jpServers))

        // Sample data for Australia
        val auServers = listOf(
            ServerLocation("au-sydney", "Sydney", "30ms"),
            ServerLocation("au-melbourne", "Melbourne", "32ms"),
            ServerLocation("au-perth", "Perth", "45ms")
        )
        countries.add(Country("au", "Australia", "flag_au", auServers))

        // Sample data for Canada
        val caServers = listOf(
            ServerLocation("ca-toronto", "Toronto", "28ms"),
            ServerLocation("ca-vancouver", "Vancouver", "35ms")
        )
        countries.add(Country("ca", "Canada", "flag_ca", caServers))

        countryAdapter.notifyItemRangeInserted(0, countries.size)
    }
}
*/