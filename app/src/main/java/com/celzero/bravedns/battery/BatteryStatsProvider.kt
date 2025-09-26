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
package com.celzero.bravedns.battery

import Logger.LOG_TAG_UI
import android.content.Context
import android.os.Build
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.facebook.battery.metrics.composite.CompositeMetrics
import com.facebook.battery.metrics.composite.CompositeMetricsCollector
import com.facebook.battery.metrics.core.StatefulSystemMetricsCollector
import com.facebook.battery.metrics.cpu.CpuFrequencyMetrics
import com.facebook.battery.metrics.cpu.CpuFrequencyMetricsCollector
import com.facebook.battery.metrics.cpu.CpuMetrics
import com.facebook.battery.metrics.cpu.CpuMetricsCollector
import com.facebook.battery.metrics.healthstats.HealthStatsMetrics
import com.facebook.battery.metrics.healthstats.HealthStatsMetricsCollector
import com.facebook.battery.metrics.network.NetworkMetrics
import com.facebook.battery.metrics.network.NetworkMetricsCollector
import com.facebook.battery.metrics.time.TimeMetrics
import com.facebook.battery.metrics.time.TimeMetricsCollector
import com.facebook.battery.reporter.composite.CompositeMetricsReporter
import com.facebook.battery.reporter.core.SystemMetricsReporter
import com.facebook.battery.reporter.cpu.CpuFrequencyMetricsReporter
import com.facebook.battery.reporter.cpu.CpuMetricsReporter
import com.facebook.battery.reporter.healthstats.HealthStatsMetricsReporter
import com.facebook.battery.reporter.network.NetworkMetricsReporter
import com.facebook.battery.reporter.time.TimeMetricsReporter
import com.facebook.battery.serializer.composite.CompositeMetricsSerializer
import com.facebook.battery.serializer.cpu.CpuFrequencyMetricsSerializer
import com.facebook.battery.serializer.cpu.CpuMetricsSerializer
import com.facebook.battery.serializer.healthstats.HealthStatsMetricsSerializer
import com.facebook.battery.serializer.network.NetworkMetricsSerializer
import com.facebook.battery.serializer.time.TimeMetricsSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


/**
 * Reflection based wrapper for Facebook Battery-Metrics. This avoids compile-time
 * dependency on the library classes (so build works even if the artifact isn't resolved
 * in the analysis environment) while still invoking it at runtime when present.
 */
object BatteryStatsProvider {

    private lateinit var mMetricsCollector: CompositeMetricsCollector
    private lateinit var mMetricsReporter: CompositeMetricsReporter
    private lateinit var mMetricsSerializer: CompositeMetricsSerializer

    private val mEvent: SystemMetricsReporter.Event = BatteryEvent()
    private lateinit var mStatefulCollector: StatefulSystemMetricsCollector<CompositeMetrics, CompositeMetricsCollector>

    fun init(ctx: Context) {
        // Note -- Creating a collector instance that's shared across the application can be fairly
        //         useful. You can set it up and hook up all the individual metrics collectors,
        //         tweaking them once.
        val collectorBuilder =
            CompositeMetricsCollector.Builder()
                .addMetricsCollector<TimeMetrics?>(TimeMetrics::class.java, TimeMetricsCollector())
                .addMetricsCollector<CpuFrequencyMetrics?>(
                    CpuFrequencyMetrics::class.java,
                    CpuFrequencyMetricsCollector()
                )
                .addMetricsCollector<CpuMetrics?>(CpuMetrics::class.java, CpuMetricsCollector())
                .addMetricsCollector<NetworkMetrics?>(
                    NetworkMetrics::class.java,
                    NetworkMetricsCollector(ctx)
                )
        if (Build.VERSION.SDK_INT >= 24) {
            collectorBuilder.addMetricsCollector<HealthStatsMetrics?>(
                HealthStatsMetrics::class.java, HealthStatsMetricsCollector(ctx)
            )
        }
        mMetricsCollector = collectorBuilder.build()


        // Note -- The Reporter and Serializer mimic the collector; they were mainly split out into
        //         separate modules to keep it simple to include only what you really needed.
        mMetricsReporter =
            CompositeMetricsReporter()
                .addMetricsReporter<TimeMetrics?>(TimeMetrics::class.java, TimeMetricsReporter())
                .addMetricsReporter<CpuMetrics?>(CpuMetrics::class.java, CpuMetricsReporter())
                .addMetricsReporter<CpuFrequencyMetrics?>(
                    CpuFrequencyMetrics::class.java,
                    CpuFrequencyMetricsReporter()
                )
                .addMetricsReporter<NetworkMetrics?>(
                    NetworkMetrics::class.java,
                    NetworkMetricsReporter()
                )
        if (Build.VERSION.SDK_INT >= 24) {
            mMetricsReporter.addMetricsReporter(
                HealthStatsMetrics::class.java, HealthStatsMetricsReporter()
            )
        }

        mMetricsSerializer =
            CompositeMetricsSerializer()
                .addMetricsSerializer<TimeMetrics?>(
                    TimeMetrics::class.java,
                    TimeMetricsSerializer()
                )
                .addMetricsSerializer<CpuMetrics?>(CpuMetrics::class.java, CpuMetricsSerializer())
                .addMetricsSerializer<CpuFrequencyMetrics?>(
                    CpuFrequencyMetrics::class.java,
                    CpuFrequencyMetricsSerializer()
                )
                .addMetricsSerializer<NetworkMetrics?>(
                    NetworkMetrics::class.java,
                    NetworkMetricsSerializer()
                )
        if (Build.VERSION.SDK_INT >= 24) {
            mMetricsSerializer.addMetricsSerializer(
                HealthStatsMetrics::class.java, HealthStatsMetricsSerializer()
            )
        }


        // Note -- The stateful collector is a useful abstraction that maintains state about when it
        //         was last triggered, making it simple to observe changes since the last call.
        //         It's a very simple piece of code to reduce boilerplate, you should check out the
        //         underlying source code.
        mStatefulCollector = StatefulSystemMetricsCollector(mMetricsCollector)
    }

    suspend fun formattedStats(): String {
        return try {
            BatteryStatsLogger.readLogFile() + "\n" + mStatefulCollector.latestDiffAndReset?.metrics.toString()
        } catch (e: Exception) {
            if (DEBUG) Logger.e(LOG_TAG_UI, "err getting formatted stats: ${e.message}", e)
            ""
        }
    }

    fun logMetrics(tag: String?) = CoroutineScope(Dispatchers.IO).launch {
        try {
            // Note -- this gets the difference from the last call / initialization of the StatefulCollector
            val update: CompositeMetrics? = mStatefulCollector.getLatestDiffAndReset()

            // Check out the Event class in this folder: it should be able to wrap most analytics
            // implementations comfortably; this one simply logs everything to logcat.
            mEvent.acquireEvent(null, "BatteryMetrics")
            if (mEvent.isSampled) {
                mEvent.add("dimension", tag)
                mMetricsReporter.reportTo(update, mEvent)
                mEvent.logAndRelease()
            }
        } catch (e: Exception) {
            // Log and move on -- we don't want battery metrics to crash the app
            Logger.w("BatteryStatsProvider", "err collecting battery metrics: ${e.message}" )
        }
    }
}
