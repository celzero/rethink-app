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

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a system event log entry.
 * Events are stored with indexing on key fields for efficient querying.
 *
 * Indices are created on:
 * - timestamp: For time-based queries and purging old logs
 * - eventType: For filtering by event type
 * - severity: For filtering by severity level
 * - source: For filtering by event source
 */
@Entity(
    tableName = "Events",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["eventType"]),
        Index(value = ["severity"]),
        Index(value = ["source"])
    ]
)
data class Event(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val timestamp: Long,
    val eventType: EventType,
    val severity: Severity,
    val message: String,
    val details: String? = null,
    val source: EventSource,
    val userAction: Boolean = false
)

