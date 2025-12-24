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
package com.celzero.bravedns.database

/**
 * Enum representing the severity level of logged events.
 * Used to categorize events by their importance and impact.
 */
enum class Severity {
    LOW,        // Minor events, normal background activity
    MEDIUM,     // Noteworthy events, could need attention
    HIGH,       // Significant issues impacting functionality
    CRITICAL    // Severe failures requiring urgent attention
}
