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
package com.celzero.bravedns.util

enum class NotificationActionType(val action: Int) {
    PAUSE_STOP(0), DNS_FIREWALL(1), NONE(2);

    companion object {
        fun getNotificationActionType(type: Int): NotificationActionType {
            return when (type) {
                PAUSE_STOP.action -> PAUSE_STOP
                DNS_FIREWALL.action -> DNS_FIREWALL
                NONE.action -> NONE
                else -> NONE
            }
        }
    }
}
