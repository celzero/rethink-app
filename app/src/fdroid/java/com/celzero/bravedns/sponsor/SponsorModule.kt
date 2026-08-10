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
package com.celzero.bravedns.sponsor

import com.celzero.bravedns.sponsor.billing.SponsorBillingManager
import com.celzero.bravedns.sponsor.billing.SponsorBillingManagerImpl
import com.celzero.bravedns.sponsor.provider.DeGoogledSponsorProvider
import com.celzero.bravedns.sponsor.provider.SponsorProvider
import org.koin.core.module.Module
import org.koin.dsl.module

object SponsorModule {
    private val sponsorModule: Module = module {
        single<SponsorBillingManager> { SponsorBillingManagerImpl() }
        single<SponsorProvider> { DeGoogledSponsorProvider() }
    }

    val modules: List<Module> = listOf(sponsorModule)
}
