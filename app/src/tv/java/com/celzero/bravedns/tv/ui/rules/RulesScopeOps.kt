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
package com.celzero.bravedns.tv.ui.rules

import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager

/**
 * Bulk per-scope rule deletes for the TV rules screen; the screen
 * holds no direct rule-manager edges.
 */
internal object RulesScopeOps {

    suspend fun deleteAllDomainRules(uid: Int) = DomainRulesManager.deleteRulesByUid(uid)

    suspend fun deleteAllIpRules(uid: Int) = IpRulesManager.deleteRulesByUid(uid)
}
