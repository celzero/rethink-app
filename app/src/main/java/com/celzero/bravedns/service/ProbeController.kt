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
package com.celzero.bravedns.service

import Logger
import Logger.LOG_TAG_CONNECTION
import android.net.Network
import com.celzero.firestack.intra.Controller

class ProbeController(val networkHandle: Long, val nw: Network?): Controller {

    override fun bind4(who: String?, addrport: String?, fd: Long) {
        if (networkHandle == -1L || nw == null) {
            // no need to bind, just protect the fd
            VpnController.protectFdForConnectivityChecks(fd)
            Logger.i(LOG_TAG_CONNECTION, "bind4: no networkHandle or nw, fd: $fd, addr: $addrport")
            return
        }
        val bound = VpnController.bindToNwForConnectivityChecks(nw, fd)
        Logger.i(LOG_TAG_CONNECTION, "bind4: bound?$bound, fd: $fd, addr: $addrport, nw: $networkHandle, ${netId(networkHandle)})")
    }

    override fun bind6(who: String?, addrport: String?, fd: Long) {
        if (networkHandle == -1L || nw == null) {
            // no need to bind, just protect the fd
            VpnController.protectFdForConnectivityChecks(fd)
            Logger.i(LOG_TAG_CONNECTION, "bind6: no networkHandle or nw, fd: $fd, addr: $addrport")
            return
        }
        val bound = VpnController.bindToNwForConnectivityChecks(nw, fd)
        Logger.i(LOG_TAG_CONNECTION, "bind6: bound?$bound, fd: $fd, addr: $addrport, nw: $networkHandle, ${netId(networkHandle)})")
    }

    override fun protect(who: String?, fd: Long) {
        VpnController.protectFdForConnectivityChecks(fd)
        Logger.i(LOG_TAG_CONNECTION, "protect: who: $who fd: $fd, nw: $networkHandle, ${netId(networkHandle)})")
    }

    fun netId(nwHandle: Long?): Long {
        if (nwHandle == null) {
            return -1L
        }
        return nwHandle shr (32)
    }
}
