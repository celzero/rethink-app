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
package com.celzero.bravedns.wireguard

import java.util.Optional
import java.util.regex.Pattern

class Attribute private constructor(val key: String, val value: String) {

    companion object {
        private val LINE_PATTERN = Pattern.compile("(\\w+)\\s*=\\s*([^\\s#][^#]*)")
        private val LIST_SEPARATOR = Pattern.compile("\\s*,\\s*")

        fun join(values: Iterable<*>): String {
            val it = values.iterator()
            if (!it.hasNext()) {
                return ""
            }
            val sb = StringBuilder()
            sb.append(it.next())
            while (it.hasNext()) {
                sb.append(",")
                sb.append(it.next())
            }
            return sb.toString()
        }

        fun parse(line: CharSequence?): Optional<Attribute> {
            val matcher = LINE_PATTERN.matcher(line ?: "")
            return if (!matcher.matches()) Optional.empty()
            else Optional.of(Attribute(matcher.group(1) ?: "", matcher.group(2) ?: ""))
        }

        fun split(value: CharSequence?): Array<String> {
            return LIST_SEPARATOR.split(value ?: "")
        }
    }
}
