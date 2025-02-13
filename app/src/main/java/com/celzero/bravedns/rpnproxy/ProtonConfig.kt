package com.celzero.bravedns.rpnproxy

import com.google.gson.annotations.SerializedName

data class ProtonConfig(
    @SerializedName("Ed25519PrivateKey")
    val ed25519PrivateKey: String,
    @SerializedName("UID")
    val uid: String,
    @SerializedName("SessionAccessToken")
    val sessionAccessToken: String,
    @SerializedName("SessionRefreshToken")
    val sessionRefreshToken: String,
    @SerializedName("UserID")
    val userID: String,
    @SerializedName("UserAccessToken")
    val userAccessToken: String,
    @SerializedName("UserRefreshToken")
    val userRefreshToken: String,
    @SerializedName("CertSerialNumber")
    val certSerialNumber: String,
    @SerializedName("CertExpTime")
    val certExpTime: Long,
    @SerializedName("CertRefreshTime")
    val certRefreshTime: Long,
    @SerializedName("CreateTimestamp")
    val createTimestamp: Long,
    @SerializedName("RegionalWgConfs")
    val regionalWgConfs: List<RegionalWgConf>
)
