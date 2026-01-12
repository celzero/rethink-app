/*
 * Copyright 2023 RethinkDNS and its authors
 *
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
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
package com.celzero.bravedns.wireguard.util

import android.content.Context
import android.os.RemoteException
import com.celzero.bravedns.R
import com.celzero.bravedns.wireguard.BadConfigException
import com.celzero.bravedns.wireguard.InetEndpoint
import com.celzero.bravedns.wireguard.InetNetwork
import com.celzero.bravedns.wireguard.ParseException
import java.net.InetAddress

object ErrorMessages {
    private val BCE_REASON_MAP =
        mapOf(
            BadConfigException.Reason.INVALID_KEY to R.string.bad_config_reason_invalid_key,
            BadConfigException.Reason.INVALID_NUMBER to R.string.bad_config_reason_invalid_number,
            BadConfigException.Reason.INVALID_VALUE to R.string.bad_config_reason_invalid_value,
            BadConfigException.Reason.MISSING_ATTRIBUTE to
                R.string.bad_config_reason_missing_attribute,
            BadConfigException.Reason.MISSING_SECTION to R.string.bad_config_reason_missing_section,
            BadConfigException.Reason.SYNTAX_ERROR to R.string.bad_config_reason_syntax_error,
            BadConfigException.Reason.UNKNOWN_ATTRIBUTE to
                R.string.bad_config_reason_unknown_attribute,
            BadConfigException.Reason.UNKNOWN_SECTION to R.string.bad_config_reason_unknown_section
        )
    private val KFE_FORMAT_MAP =
        mapOf(
            BadConfigException.Reason.INVALID_KEY to R.string.key_length_explanation_base64,
        )
    private val KFE_TYPE_MAP =
        mapOf(
            BadConfigException.Reason.INVALID_KEY to R.string.key_contents_error,
        )
    private val PE_CLASS_MAP =
        mapOf(
            InetAddress::class.java to R.string.parse_error_inet_address,
            InetEndpoint::class.java to R.string.parse_error_inet_endpoint,
            InetNetwork::class.java to R.string.parse_error_inet_network,
            Int::class.java to R.string.parse_error_integer
        )

    operator fun get(context: Context, throwable: Throwable?): String {
        if (throwable == null) return context.getString(R.string.unknown_error)
        val rootCause = rootCause(throwable)
        return when {
            rootCause is BadConfigException -> {
                val reason = getBadConfigExceptionReason(context, rootCause)
                val ctx =
                    if (rootCause.location == BadConfigException.Location.TOP_LEVEL) {
                        context.getString(
                            R.string.bad_config_context_top_level,
                            rootCause.section.name
                        )
                    } else {
                        context.getString(
                            R.string.bad_config_context,
                            rootCause.section.name,
                            rootCause.location.name
                        )
                    }
                val explanation = getBadConfigExceptionExplanation(context, rootCause)
                context.getString(R.string.bad_config_error, reason, ctx) + explanation
            }
            rootCause is NullPointerException -> {
                // Provide specific error for NPE instead of generic error
                val message = rootCause.message
                if (message != null && message.isNotEmpty()) {
                    context.getString(R.string.import_error, "Invalid configuration: $message")
                } else {
                    context.getString(R.string.import_error, "Configuration file contains missing or invalid required fields")
                }
            }
            rootCause is IllegalArgumentException -> {
                // Handle illegal argument exceptions with their message
                val message = rootCause.message
                if (message != null && message.isNotEmpty()) {
                    message
                } else {
                    context.getString(R.string.import_error, "Invalid configuration format")
                }
            }
            rootCause.localizedMessage != null -> {
                rootCause.localizedMessage!!
            }
            else -> {
                val errorType = rootCause.javaClass.simpleName
                context.getString(R.string.generic_error, errorType)
            }
        }
    }

    private fun getBadConfigExceptionExplanation(
        context: Context,
        bce: BadConfigException
    ): String {
        if (bce.cause is ParseException) {
            val pe = bce.cause as ParseException?
            if (pe!!.localizedMessage != null) return ": ${pe.localizedMessage}"
        } else if (bce.location == BadConfigException.Location.LISTEN_PORT) {
            return context.getString(R.string.bad_config_explanation_udp_port)
        } else if (bce.location == BadConfigException.Location.MTU) {
            return context.getString(R.string.bad_config_explanation_positive_number)
        } else if (bce.location == BadConfigException.Location.PERSISTENT_KEEPALIVE) {
            return context.getString(R.string.bad_config_explanation_pka)
        } else if (bce.cause is BadConfigException) {
            val kfe = bce.cause as BadConfigException?
            return if (kfe!!.reason == BadConfigException.Reason.INVALID_KEY)
                context.getString(KFE_FORMAT_MAP.getValue(kfe.reason))
            else context.getString(KFE_TYPE_MAP.getValue(kfe.reason))
        } else {
            return ""
        }
        return ""
    }

    private fun getBadConfigExceptionReason(context: Context, bce: BadConfigException): String {
        if (bce.cause is ParseException) {
            val pe = bce.cause as ParseException?
            val type =
                (if (PE_CLASS_MAP.containsKey(pe!!.parsingClass)) PE_CLASS_MAP[pe.parsingClass]
                    else R.string.parse_error_generic)
                    ?.let { context.getString(it) }
            return context.getString(R.string.parse_error_reason, type, pe.text)
        }
        return context.getString(BCE_REASON_MAP.getValue(bce.reason), bce.text)
    }

    private fun rootCause(throwable: Throwable): Throwable {
        var cause = throwable
        while (cause.cause != null) {
            if (cause is BadConfigException) break
            val nextCause = cause.cause!!
            if (nextCause is RemoteException) break
            cause = nextCause
        }
        return cause
    }
}
