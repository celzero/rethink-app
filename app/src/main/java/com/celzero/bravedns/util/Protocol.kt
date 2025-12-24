/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.util

// https://android.googlesource.com/platform/development/+/da84168fb2f5eb5ca012c3f430f701bc64472f34/ndk/platforms/android-21/include/linux/in.h
// Protocol numbers are defined by IANA and Linux kernel, not magic numbers
@Suppress("MagicNumber")
enum class Protocol(val protocolType: Int) {
    IP(0),
    ICMP(1),
    IGMP(2),
    IPIP(4),
    TCP(6),
    EGP(8),
    PUP(12),
    UDP(17),
    IDP(22),
    TP(29),
    DCCP(33),
    IPV6(41),
    RSVP(46),
    GRE(47),
    ESP(50),
    AH(51),
    ICMPV6(58),
    MTP(92),
    BEETPH(94),
    ENCAP(98),
    PIM(103),
    COMP(108),
    SCTP(132),
    UDPLITE(136),
    RAW(255),
    OTHER(-1);

    companion object {
        private val map = entries.associateBy(Protocol::protocolType)

        fun getProtocolName(protocolType: Int): Protocol {
            return map[protocolType] ?: OTHER
        }
    }
}
