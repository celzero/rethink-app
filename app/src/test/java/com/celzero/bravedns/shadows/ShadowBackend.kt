/*
 * Copyright 2024 RethinkDNS and its authors
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
package com.celzero.bravedns.shadows

import com.celzero.firestack.backend.IpTree
import com.celzero.firestack.backend.RadixTree
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric shadow for [com.celzero.firestack.backend.Backend].
 *
 * [Backend] is a Go/JNI class whose static initializer calls [System.loadLibrary] for the
 * native "gojni" binary.  Unit tests run on the JVM without the native library, so any code
 * path that touches [Backend] would throw [UnsatisfiedLinkError].
 *
 * This shadow:
 *  1. Replaces the static initializer so the native library is never loaded.
 *  2. Provides pure-Java no-op implementations of the factory methods that
 *     [IpRulesManager] and [DomainRulesManager] call during their object-initializer blocks
 *     ([Backend.newIpTree] and [Backend.newRadixTree]).
 *
 * Usage: annotate a test class with
 *     @Config(sdk = [28], shadows = [ShadowBackend::class])
 */
@Implements(com.celzero.firestack.backend.Backend::class)
class ShadowBackend {

    companion object {

        /** Suppress the native-library load that happens in Backend's static initializer. */
        @Implementation
        @JvmStatic
        fun `__staticInitializer__`() {
            // intentionally empty — no native library loading in tests
        }

        /**
         * Returns a no-op [IpTree] instead of going to native code.
         * [IpRulesManager] stores this in its `iptree` field.
         */
        @Implementation
        @JvmStatic
        fun newIpTree(): IpTree = NoOpIpTree()

        /**
         * Returns a no-op [RadixTree] instead of going to native code.
         * [DomainRulesManager] stores two of these (trie + trustedTrie).
         */
        @Implementation
        @JvmStatic
        fun newRadixTree(): RadixTree = NoOpRadixTree()
    }
}

// ---------------------------------------------------------------------------
// No-op implementations of the firestack tree interfaces.
// ---------------------------------------------------------------------------

/**
 * A no-op implementation of [IpTree] that satisfies the interface contract without
 * doing any real work. Sufficient for tests that never exercise the trie lookup paths.
 */
private class NoOpIpTree : IpTree {
    override fun add(key: String, value: String) {}
    override fun clear() {}
    override fun del(key: String): Boolean = false
    override fun delAll(prefix: String): Int = 0
    override fun esc(key: String, value: String): Boolean = false
    override fun escLike(key: String, value: String): Int = 0
    override fun get(key: String): String? = null
    override fun getAll(key: String): String? = null
    override fun getAny(key: String): String? = null
    override fun getLike(key: String, value: String): String? = null
    override fun has(key: String): Boolean = false
    override fun hasAny(key: String): Boolean = false
    override fun len(): Long = 0L
    override fun routes(key: String): String? = null
}

/**
 * A no-op implementation of [RadixTree] analogous to [NoOpIpTree].
 */
private class NoOpRadixTree : RadixTree {
    override fun add(key: String): Boolean = false
    override fun clear() {}
    override fun del(key: String): Boolean = false
    override fun delAll(prefix: String): Int = 0
    override fun get(key: String): String? = null
    override fun getAny(key: String): String? = null
    override fun has(key: String): Boolean = false
    override fun hasAny(key: String): Boolean = false
    override fun len(): Long = 0L
    override fun set(key: String, value: String) {}
}
