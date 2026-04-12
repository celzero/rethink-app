/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.data

enum class PowerProfileActivationAction {
    DISABLE_PROFILE,
    START_PROTECTION_AND_ENABLE,
    ENABLE_PROFILE,
    IGNORE
}

object PowerProfileActivationPolicy {
    fun resolve(
        profile: PowerProfileDefinition,
        isActive: Boolean,
        hasTunnel: Boolean
    ): PowerProfileActivationAction {
        if (isActive) return PowerProfileActivationAction.DISABLE_PROFILE
        if (!profile.readyForActivation) return PowerProfileActivationAction.IGNORE
        if (profile.localBlocklistTagIds.isNotEmpty() && !hasTunnel) {
            return PowerProfileActivationAction.START_PROTECTION_AND_ENABLE
        }

        return PowerProfileActivationAction.ENABLE_PROFILE
    }
}
