package com.celzero.bravedns.rpnproxy

import com.google.gson.annotations.SerializedName

data class RegionalWgConf(
    @SerializedName("CC")
    val cc: String,
    @SerializedName("Name")
    val name: String,
    @SerializedName("ClientAddr4")
    val clientAddr4: String,
    @SerializedName("ClientAddr6")
    val clientAddr6: String,
    @SerializedName("ClientPrivKey")
    val clientPrivKey: String,
    @SerializedName("ClientPubKey")
    val clientPubKey: String,
    @SerializedName("ClientDNS4")
    val clientDNS4: String,
    @SerializedName("ClientDNS6")
    val clientDNS6: String,
    @SerializedName("PskKey")
    val pskKey: String,
    @SerializedName("ServerPubKey")
    val serverPubKey: String,
    @SerializedName("ServerIPPort4")
    val serverIpPort4: String,
    @SerializedName("ServerIPPort6")
    val serverIpPort6: String,
    @SerializedName("ServerDomainPort")
    val serverDomainPort: String,
    @SerializedName("AllowedIPs")
    val allowedIPs: String,
    @SerializedName("WgConf")
    val wgConf: String
)
