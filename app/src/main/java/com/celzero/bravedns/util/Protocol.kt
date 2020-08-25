package com.celzero.bravedns.util

enum class Protocol(val protocolType : Int) {
    IP (0),
    ICMP (1),
    IGMP (2),
    IPIP (4),
    TCP (6),
    EGP (8),
    PUP (12),
    UDP (17),
    IDP (22),
    TP (29),
    DCCP (33),
    IPV6 (41),
    RSVP (46),
    GRE (47),
    ESP (50),
    AH (51),
    MTP (92),
    BEETPH (94),
    ENCAP (98),
    PIM (103),
    COMP (108),
    SCTP (132),
    UDPLITE (136),
    RAW (255),
    OTHER (-1);

    companion object {
        private val map = Protocol.values().associateBy(Protocol::protocolType)

        fun getProtocolName(protocolType: Int): Protocol {
            return map[protocolType.hashCode()] ?: OTHER
        }
    }


}