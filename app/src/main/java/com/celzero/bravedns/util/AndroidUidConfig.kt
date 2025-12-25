/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.util

// https://android.googlesource.com/platform/system/core/+/4489ee0a190ad488df8ed9be545986a46c86148e/libcutils/include/private/android_filesystem_config.h
// UID values are defined by Android AOSP (Android Open Source Project), not magic numbers
@Suppress("MagicNumber")
enum class AndroidUidConfig(val uid: Int) {
    ANDROID(0), // Modified as ANDROID instead of ROOT
    DAEMON(1),
    BIN(2),
    SYSTEM(1000),
    RADIO(1001),
    BLUETOOTH(1002),
    GRAPHICS(1003),
    INPUT(1004),
    AUDIO(1005),
    CAMERA(1006),
    LOG(1007),
    COMPASS(1008),
    MOUNT(1009),
    WIFI(1010),
    ADB(1011),
    INSTALLER(1012),
    MEDIA(1013),
    DHCP(1014),
    SDCARD_RW(1015),
    VPN(1016),
    KEYSTORE(1017),
    USB(1018),
    DRM(1019),
    MDNSR(1020),
    GPS(1021),
    UNUSED1(1022),
    MEDIA_RW(1023),
    MTP(1024),
    UNUSED2(1025),
    DRMRPC(1026),
    NFC(1027),
    SDCARD_R(1028),
    CLAT(1029),
    LOOP_RADIO(1030),
    MEDIA_DRM(1031),
    PACKAGE_INFO(1032),
    SDCARD_PICS(1033),
    SDCARD_AV(1034),
    SDCARD_ALL(1035),
    LOGD(1036),
    SHARED_RELRO(1037),
    DBUS(1038),
    TLSDATE(1039),
    MEDIA_EX(1040),
    AUDIOSERVER(1041),
    METRICS_COLL(1042),
    METRICSD(1043),
    WEBSERV(1044),
    DEBUGGERD(1045),
    MEDIA_CODEC(1046),
    CAMERASERVER(1047),
    FIREWALL(1048),
    TRUNKS(1049),
    NVRAM(1050),
    DNS(1051),
    DNS_TETHER(1052),
    WEBVIEW_ZYGOTE(1053),
    VEHICLE_NETWORK(1054),
    MEDIA_AUDIO(1055),
    MEDIA_VIDEO(1056),
    MEDIA_IMAGE(1057),
    TOMBSTONED(1058),
    MEDIA_OBB(1059),
    ESE(1060),
    OTA_UPDATE(1061),
    AUTOMOTIVE_EVS(1062),
    LOWPAN(1063),
    HSM(1064),
    RESERVED_DISK(1065),
    STATSD(1066),
    INCIDENTD(1067),
    SECURE_ELEMENT(1068),
    LMKD(1069),
    LLKD(1070),
    IORAPD(1071),
    GPU_SERVICE(1072),
    NETWORK_STACK(1073),
    GSID(1074),
    FSVERITY_CERT(1075),
    CREDSTORE(1076),
    EXTERNAL_STORAGE(1077),
    EXT_DATA_RW(1078),
    EXT_OBB_RW(1079),
    CONTEXT_HUB(1080),
    SHELL(2000),
    CACHE(2001),
    DIAG(2002),
    OEM_RESERVED_START(2900),
    OEM_RESERVED_END(2999),
    NET_BT_ADMIN(3001),
    NET_BT(3002),
    INET(3003),
    NET_RAW(3004),
    NET_ADMIN(3005),
    NET_BW_STATS(3006),
    NET_BW_ACCT(3007),
    READPROC(3009),
    WAKELOCK(3010),
    UHID(3011),
    OEM_RESERVED_2_START(5000),
    OEM_RESERVED_2_END(5999),
    SYSTEM_RESERVED_START(6000),
    SYSTEM_RESERVED_END(6499),
    ODM_RESERVED_START(6500),
    ODM_RESERVED_END(6999),
    PRODUCT_RESERVED_START(7000),
    PRODUCT_RESERVED_END(7499),
    SYSTEM_EXT_RESERVED_START(7500),
    SYSTEM_EXT_RESERVED_END(7999),
    EVERYBODY(9997),
    MISC(9998),
    NOBODY(9999),
    APP(10000),
    APP_START(10000),
    APP_END(19999),
    CACHE_GID_START(20000),
    CACHE_GID_END(29999),
    EXT_GID_START(30000),
    EXT_GID_END(39999),
    EXT_CACHE_GID_START(40000),
    EXT_CACHE_GID_END(49999),
    SHARED_GID_START(50000),
    SHARED_GID_END(59999),
    OVERFLOWUID(65534),
    ISOLATED_START(90000),
    ISOLATED_END(99999),
    USER(100000),
    USER_OFFSET(100000),
    OTHER(Constants.INVALID_UID);

    companion object {
        private val map = entries.associateBy(AndroidUidConfig::uid)

        fun fromFileSystemUid(uid: Int): AndroidUidConfig {
            return map[uid] ?: OTHER
        }

        fun isUidAppRange(uid: Int): Boolean {
            return (uid >= APP_START.uid && uid <= APP_END.uid)
        }
    }
}
