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

import com.facebook.battery.reporter.core.SystemMetricsReporter

class BatteryEvent : SystemMetricsReporter.Event {
    private var stringBuilder = StringBuilder()
    override fun isSampled(): Boolean {
        return true
    }

    override fun acquireEvent(moduleName: String?, eventName: String?) {
        stringBuilder = StringBuilder()
        stringBuilder.append("Event:").append(moduleName).append(":").append(eventName).append(" {")
    }

    override fun add(key: String?, value: String?) {
        stringBuilder.append("$key:$value,")
    }

    override fun add(key: String?, value: Int) {
        stringBuilder.append("$key:$value,")
    }

    override fun add(key: String?, value: Long) {
        stringBuilder.append("$key:$value,")
    }

    override fun add(key: String?, value: Double) {
        stringBuilder.append("$key:$value,")
    }

    override fun logAndRelease() {
        stringBuilder.append("}")
        BatteryStatsLogger.writeOnce(stringBuilder.toString())
    }
}
