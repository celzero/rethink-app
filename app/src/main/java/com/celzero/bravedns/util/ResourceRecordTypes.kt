/*
Copyright 2022 RethinkDNS and its authors

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
@file:Suppress("MagicNumber")
package com.celzero.bravedns.util

// From https://www.iana.org/assignments/dns-parameters/dns-parameters.xhtml#dns-parameters-4
// additional ref: https://www.netmeister.org/blog/dns-rrs.html
enum class ResourceRecordTypes(val value: Int, val desc: String) {
    A(1, "IPv4"),
    NS(2, "Authoritative Name Server"),
    CNAME(5, "Canonical Name"),
    SOA(6, "Start of a zone of Authority"),
    MB(7, "Mailbox Domain Name"),
    MG(8, "Mail Group-member"),
    MR(9, "Mail Rename Domain Name"),
    WKS(11, "Well-known Service description"),
    PTR(12, "Domain Name Pointer"),
    HINFO(13, "Host Information"),
    MINFO(14, "Mailbox"),
    MX(15, "Mail Exchange"),
    TXT(16, "Text Strings"),
    RP(17, "Responsible Person"),
    AFSDB(18, "FS Database Location"),
    X25(19, "X.25 PSDN"),
    ISDN(20, "ISDN"),
    RT(21, "Route Through"),
    NSAP(22, "NSAP"),
    NSAPPTR(23, "Domain Name Pointer"),
    SIG(24, "Security Signature"),
    KEY(25, "Security Key"),
    PX(26, "X.400 Mail Mapping Information"),
    GPOS(27, "Geographical Position"),
    AAAA(28, "IPv6"),
    LOC(29, "Location Information"),
    EID(31, "Endpoint Identifier"),
    NIMLOC(32, "Nimrod Locator"),
    SRV(33, "Server Selection"),
    ATMA(34, "ATM"),
    NAPTR(35, "Naming Authority Pointer"),
    KX(36, "Key Exchanger"),
    CERT(37, "CERT"),
    DNAME(39, "DNAME"),
    SINK(40, "SINK"),
    OPT(41, "Options"),
    APL(42, "APL"),
    DS(43, "Delegation Signer"),
    SSHFP(44, "SSH Key Fingerprint"),
    IPSECKEY(45, "IPSECKEY"),
    RRSIG(46, "RRSIG"),
    NSEC(47, "NSEC"),
    DNSKEY(48, "DNSKEY"),
    DHCID(49, "DHCID"),
    NSEC3(50, "NSEC3"),
    NSEC3PARAM(51, "NSEC3PARAM"),
    TLSA(52, "TLSA"),
    SMIMEA(53, "S/MIME cert association"),
    HIP(55, "Host Identity Protocol"),
    NINFO(56, "NINFO"),
    RKEY(57, "RKEY"),
    TALINK(58, "Trust Anchor LINK"),
    CDS(59, "Child DS"),
    CDNSKEY(60, "DNSKEY(s)"),
    OPENPGPKEY(61, "OpenPGP Key"),
    CSYNC(62, "Child-To-Parent Synchronization"),
    ZONEMD(63, "Message Digest Over Zone Data"),
    SVCB(64, "General Service Binding"),
    HTTPS(65, "HTTP Service Binding"),
    EUI48(108, "IPv6 EUI-48"),
    EUI64(109, "IPv6 EUI-64"),
    TKEY(249, "Transaction Key"),
    TSIG(250, "Transaction Signature"),
    IXFR(251, "incremental transfer"),
    AXFR(252, "Transfer entire zone"),
    MAILB(253, "Mailbox-related RRs"),
    URI(256, "URI"),
    CAA(257, "Certification Authority Restriction"),
    AVC(258, "Application Visibility and Control"),
    DOA(259, "Digital Object Architecture"),
    AMTRELAY(260, "Automatic Multicast Tunneling Relay"),
    TA(32768, "DNSSEC Trust Authorities"),
    UNKNOWN(-1, "Unknown");

    companion object {
        private val map = entries.associateBy(ResourceRecordTypes::value)

        fun getTypeName(value: Int): ResourceRecordTypes {
            return map[value.hashCode()] ?: UNKNOWN
        }

        fun mayContainIP(value: Int): Boolean {
            return when (value) {
                A.value,
                AAAA.value,
                CNAME.value,
                HTTPS.value,
                SVCB.value -> true
                else -> false
            }
        }

        fun mayContainIp(desc: String): Boolean {
            return when (desc) {
                A.desc,
                AAAA.desc,
                CNAME.desc,
                HTTPS.desc,
                SVCB.desc -> true
                else -> false
            }
        }

        fun getHandledTypes(): Set<String> {
            val list =
                enumValues<ResourceRecordTypes>().filter {
                    it == A || it == AAAA || it == CNAME || it == HTTPS || it == SVCB
                }
            return list.map { it.desc }.toSet()
        }
    }
}
