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
 * Enum representing different types of events that can be logged in the system.
 * These events cover VPN lifecycle, DNS operations, firewall actions, proxy operations,
 * UI interactions, and system-level events.
 */
enum class EventType {
    // VPN Events
    VPN_START,
    VPN_STOP,
    VPN_RESTART,
    VPN_ERROR,
    VPN_AUTOSTART,

    TUN_ERROR,
    TUN_ESTABLISHED,
    TUN_CLOSED,
    TUN_UPDATE,

    // DNS Events
    DNS_QUERY,
    DNS_FAIL,
    DNS_BLOCKED,
    DNS_SERVER_CHANGE,

    // Firewall Events
    FW_RULE_ADDED,
    FW_RULE_REMOVED,
    FW_RULE_MODIFIED,
    FW_BLOCK,
    FW_ALLOW,

    APP_REFRESH,

    // Proxy Events
    PROXY_CONNECT,
    PROXY_ERROR,
    PROXY_SWITCH,
    PROXY_REFRESH,

    // UI Events
    UI_TOGGLE,
    UI_NAVIGATION,
    UI_SETTING_CHANGED,

    // System Events
    SYSTEM_EVENT,
    WORKER_TASK
}

