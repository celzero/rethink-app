/*
 * Copyright 2023 RethinkDNS and its authors
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
import androidx.room.PrimaryKey

@Entity(tableName = "AlertRegistry")
class AlertRegistry {
    @PrimaryKey(autoGenerate = true) var id: Int = 0
    var alertTitle: String = ""
    var alertType: String = ""
    var alertCount: Int = 0
    var alertTime: Long = 0L
    var alertMessage: String = ""
    var alertCategory: String = ""
    var alertSeverity: String = ""
    var alertActions: String = ""
    var alertStatus: String = ""
    var alertSolution: String = ""
    var isRead: Boolean = false
    var isDeleted: Boolean = false
    var isCustom: Boolean = false
    var isNotified: Boolean = false

    override fun equals(other: Any?): Boolean {
        if (other !is AlertRegistry) return false
        if (id != other.id) return false
        return true
    }

    override fun hashCode(): Int {
        return this.id.hashCode()
    }

    constructor(
        id: Int,
        alertTitle: String,
        alertType: String,
        alertCount: Int,
        alertTime: Long,
        alertMessage: String,
        alertCategory: String,
        alertSeverity: String,
        alertActions: String,
        alertStatus: String,
        alertSolution: String,
        isRead: Boolean,
        isDeleted: Boolean,
        isCustom: Boolean,
        isNotified: Boolean
    ) {
        // Room auto-increments id when its set to zero.
        // A non-zero id overrides and sets caller-specified id instead.
        this.id = id
        this.alertTitle = alertTitle
        this.alertType = alertType
        this.alertCount = alertCount
        this.alertTime = alertTime
        this.alertMessage = alertMessage
        this.alertCategory = alertCategory
        this.alertSeverity = alertSeverity
        this.alertActions = alertActions
        this.alertStatus = alertStatus
        this.alertSolution = alertSolution
        this.isRead = isRead
        this.isDeleted = isDeleted
        this.isCustom = isCustom
        this.isNotified = isNotified
    }
}
