/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.shadows

import android.content.res.Resources
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Lenient [Resources] shadow for tests whose sandbox has no app resource table
 * (Robolectric without merged resources). The real implementation throws
 * [Resources.NotFoundException] for app resource ids, which crashes service
 * `onCreate` paths that build notifications. This shadow returns a stable
 * placeholder instead. Only for tests that never assert on resource contents.
 */
@Implements(Resources::class)
class LenientResourcesShadow {

    @Implementation
    fun getText(id: Int): CharSequence {
        return "res-0x" + Integer.toHexString(id)
    }

    @Implementation
    fun getText(id: Int, def: CharSequence): CharSequence {
        return def
    }
}
