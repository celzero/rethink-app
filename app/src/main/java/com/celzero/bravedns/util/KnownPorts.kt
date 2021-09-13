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

class KnownPorts {

    companion object {
        const val DNS_DEFAULT_PORT: Int = 53

        //get protocol desc based on port number
        private var portMap: HashMap<Int, String> = initPortMap()

        fun resolvePort(port: Int): String {
            return portMap[port] ?: Constants.PORT_VAL_UNKNOWN
        }

        fun isNtp(port: Int): Boolean {
            return portMap[port] == portMap[123]
        }

        fun isDns(port: Int): Boolean {
            return portMap[port] == portMap[DNS_DEFAULT_PORT]
        }

        // init hash map with reserved ports (1-1024) and protocol identifiers
        // based on: http://www.iana.org/assignments/service-names-port-numbers/service-names-port-numbers.xhtml
        private fun initPortMap(): HashMap<Int, String> {
            portMap = HashMap()
            portMap[1] = "tcpmux"
            portMap[2] = "compressnet"
            portMap[3] = "compressnet"
            portMap[5] = "rje"
            portMap[7] = "echo"
            portMap[9] = "discard"
            portMap[11] = "systat"
            portMap[13] = "daytime"
            portMap[17] = "qotd"
            portMap[18] = "msp"
            portMap[19] = "chargen"
            portMap[20] = "ftp-data"
            portMap[21] = "ftp"
            portMap[22] = "ssh"
            portMap[23] = "telnet"
            portMap[25] = "smtp"
            portMap[27] = "nsw-fe"
            portMap[29] = "msg-icp"
            portMap[31] = "msg-auth"
            portMap[33] = "dsp"
            portMap[37] = "time"
            portMap[38] = "rap"
            portMap[39] = "rlp"
            portMap[41] = "graphics"
            portMap[42] = "name"
            portMap[43] = "nicname"
            portMap[44] = "mpm-flags"
            portMap[45] = "mpm"
            portMap[46] = "mpm-snd"
            portMap[47] = "ni-ftp"
            portMap[48] = "auditd"
            portMap[49] = "tacacs"
            portMap[50] = "re-mail-ck"
            portMap[52] = "xns-time"
            portMap[53] = "dns"
            portMap[54] = "xns-ch"
            portMap[55] = "isi-gl"
            portMap[56] = "xns-auth"
            portMap[58] = "xns-mail"
            portMap[61] = "ni-mail"
            portMap[62] = "acas"
            portMap[63] = "whoispp"
            portMap[64] = "covia"
            portMap[65] = "tacacs-ds"
            portMap[66] = "sql-net"
            portMap[67] = "bootps"
            portMap[68] = "bootpc"
            portMap[69] = "tftp"
            portMap[70] = "gopher"
            portMap[71] = "netrjs-1"
            portMap[72] = "netrjs-2"
            portMap[73] = "netrjs-3"
            portMap[74] = "netrjs-4"
            portMap[76] = "deos"
            portMap[78] = "vettcp"
            portMap[79] = "finger"
            portMap[80] = "http"
            portMap[82] = "xfer"
            portMap[83] = "mit-ml-dev"
            portMap[84] = "ctf"
            portMap[85] = "mit-ml-dev"
            portMap[86] = "mfcobol"
            portMap[88] = "kerberos"
            portMap[89] = "su-mit-tg"
            portMap[90] = "dnsix"
            portMap[91] = "mit-dov"
            portMap[92] = "npp"
            portMap[93] = "dcp"
            portMap[94] = "objcall"
            portMap[95] = "supdup"
            portMap[96] = "dixie"
            portMap[97] = "swift-rvf"
            portMap[98] = "tacnews"
            portMap[99] = "metagram"
            portMap[101] = "hostname"
            portMap[102] = "iso-tsap"
            portMap[103] = "gppitnp"
            portMap[104] = "acr-nema"
            portMap[105] = "cso"
            portMap[106] = "3com-tsmux"
            portMap[107] = "rtelnet"
            portMap[108] = "snagas"
            portMap[109] = "pop2"
            portMap[110] = "pop3"
            portMap[111] = "sunrpc"
            portMap[112] = "mcidas"
            portMap[113] = "ident"
            portMap[115] = "sftp"
            portMap[116] = "ansanotify"
            portMap[117] = "uucp-path"
            portMap[118] = "sqlserv"
            portMap[119] = "nntp"
            portMap[120] = "cfdptkt"
            portMap[121] = "erpc"
            portMap[122] = "smakynet"
            portMap[123] = "ntp"
            portMap[124] = "ansatrader"
            portMap[125] = "locus-map"
            portMap[126] = "nxedit"
            portMap[127] = "locus-con"
            portMap[128] = "gss-xlicen"
            portMap[129] = "pwdgen"
            portMap[130] = "cisco-fna"
            portMap[131] = "cisco-tna"
            portMap[132] = "cisco-sys"
            portMap[133] = "statsrv"
            portMap[134] = "ingres-net"
            portMap[135] = "epmap"
            portMap[136] = "profile"
            portMap[137] = "netbios-ns"
            portMap[138] = "netbios-dgm"
            portMap[139] = "netbios-ssn"
            portMap[140] = "emfis-data"
            portMap[141] = "emfis-cntl"
            portMap[142] = "bl-idm"
            portMap[143] = "imap"
            portMap[144] = "uma"
            portMap[145] = "uaac"
            portMap[146] = "iso-tp0"
            portMap[147] = "iso-ip"
            portMap[148] = "jargon"
            portMap[149] = "aed-512"
            portMap[150] = "sql-net"
            portMap[151] = "hems"
            portMap[152] = "bftp"
            portMap[153] = "sgmp"
            portMap[154] = "netsc-prod"
            portMap[155] = "netsc-dev"
            portMap[156] = "sqlsrv"
            portMap[157] = "knet-cmp"
            portMap[158] = "pcmail-srv"
            portMap[159] = "nss-routing"
            portMap[160] = "sgmp-traps"
            portMap[161] = "snmp"
            portMap[162] = "snmptrap"
            portMap[163] = "cmip-man"
            portMap[164] = "cmip-agent"
            portMap[165] = "xns-courier"
            portMap[166] = "s-net"
            portMap[167] = "namp"
            portMap[168] = "rsvd"
            portMap[169] = "send"
            portMap[170] = "print-srv"
            portMap[171] = "multiplex"
            portMap[172] = "cl-1"
            portMap[173] = "xyplex-mux"
            portMap[174] = "mailq"
            portMap[175] = "vmnet"
            portMap[176] = "genrad-mux"
            portMap[177] = "xdmcp"
            portMap[178] = "nextstep"
            portMap[179] = "bgp"
            portMap[180] = "ris"
            portMap[181] = "unify"
            portMap[182] = "audit"
            portMap[183] = "ocbinder"
            portMap[184] = "ocserver"
            portMap[185] = "remote-kis"
            portMap[186] = "kis"
            portMap[187] = "aci"
            portMap[188] = "mumps"
            portMap[189] = "qft"
            portMap[190] = "gacp"
            portMap[191] = "prospero"
            portMap[192] = "osu-nms"
            portMap[193] = "srmp"
            portMap[194] = "irc"
            portMap[195] = "dn6-nlm-aud"
            portMap[196] = "dn6-smm-red"
            portMap[197] = "dls"
            portMap[198] = "dls-mon"
            portMap[199] = "smux"
            portMap[200] = "src"
            portMap[201] = "at-rtmp"
            portMap[202] = "at-nbp"
            portMap[203] = "at-3"
            portMap[204] = "at-echo"
            portMap[205] = "at-5"
            portMap[206] = "at-zis"
            portMap[207] = "at-7"
            portMap[208] = "at-8"
            portMap[209] = "qmtp"
            portMap[210] = "z39-50"
            portMap[211] = "914c-g"
            portMap[212] = "anet"
            portMap[213] = "ipx"
            portMap[214] = "vmpwscs"
            portMap[215] = "softpc"
            portMap[216] = "CAIlic"
            portMap[217] = "dbase"
            portMap[218] = "mpp"
            portMap[219] = "uarps"
            portMap[220] = "imap3"
            portMap[221] = "fln-spx"
            portMap[222] = "rsh-spx"
            portMap[223] = "cdc"
            portMap[224] = "masqdialer"
            portMap[242] = "direct"
            portMap[243] = "sur-meas"
            portMap[244] = "inbusiness"
            portMap[245] = "link"
            portMap[246] = "dsp3270"
            portMap[247] = "subntbcst-tftp"
            portMap[248] = "bhfhs"
            portMap[256] = "rap"
            portMap[257] = "set"
            portMap[259] = "esro-gen"
            portMap[260] = "openport"
            portMap[261] = "nsiiops"
            portMap[262] = "arcisdms"
            portMap[263] = "hdap"
            portMap[264] = "bgmp"
            portMap[265] = "x-bone-ctl"
            portMap[266] = "sst"
            portMap[267] = "td-service"
            portMap[268] = "td-replica"
            portMap[269] = "manet"
            portMap[270] = "gist"
            portMap[271] = "pt-tls"
            portMap[280] = "http-mgmt"
            portMap[281] = "personal-link"
            portMap[282] = "cableport-ax"
            portMap[283] = "rescap"
            portMap[284] = "corerjd"
            portMap[286] = "fxp"
            portMap[287] = "k-block"
            portMap[308] = "novastorbakcup"
            portMap[309] = "entrusttime"
            portMap[310] = "bhmds"
            portMap[311] = "asip-webadmin"
            portMap[312] = "vslmp"
            portMap[313] = "magenta-logic"
            portMap[314] = "opalis-robot"
            portMap[315] = "dpsi"
            portMap[316] = "decauth"
            portMap[317] = "zannet"
            portMap[318] = "pkix-timestamp"
            portMap[319] = "ptp-event"
            portMap[320] = "ptp-general"
            portMap[321] = "pip"
            portMap[322] = "rtsps"
            portMap[323] = "rpki-rtr"
            portMap[324] = "rpki-rtr-tls"
            portMap[333] = "texar"
            portMap[344] = "pdap"
            portMap[345] = "pawserv"
            portMap[346] = "zserv"
            portMap[347] = "fatserv"
            portMap[348] = "csi-sgwp"
            portMap[349] = "mftp"
            portMap[350] = "matip-type-a"
            portMap[351] = "matip-type-b"
            portMap[352] = "dtag-ste-sb"
            portMap[353] = "ndsauth"
            portMap[354] = "bh611"
            portMap[355] = "datex-asn"
            portMap[356] = "cloanto-net-1"
            portMap[357] = "bhevent"
            portMap[358] = "shrinkwrap"
            portMap[359] = "nsrmp"
            portMap[360] = "scoi2odialog"
            portMap[361] = "semantix"
            portMap[362] = "srssend"
            portMap[363] = "rsvp-tunnel"
            portMap[364] = "aurora-cmgr"
            portMap[365] = "dtk"
            portMap[366] = "odmr"
            portMap[367] = "mortgageware"
            portMap[368] = "qbikgdp"
            portMap[369] = "rpc2portmap"
            portMap[370] = "codaauth2"
            portMap[371] = "clearcase"
            portMap[372] = "ulistproc"
            portMap[373] = "legent-1"
            portMap[374] = "legent-2"
            portMap[375] = "hassle"
            portMap[376] = "nip"
            portMap[377] = "tnETOS"
            portMap[378] = "dsETOS"
            portMap[379] = "is99c"
            portMap[380] = "is99s"
            portMap[381] = "hp-collector"
            portMap[382] = "hp-managed-node"
            portMap[383] = "hp-alarm-mgr"
            portMap[384] = "arns"
            portMap[385] = "ibm-app"
            portMap[386] = "asa"
            portMap[387] = "aurp"
            portMap[388] = "unidata-ldm"
            portMap[389] = "ldap"
            portMap[390] = "uis"
            portMap[391] = "synotics-relay"
            portMap[392] = "synotics-broker"
            portMap[393] = "meta5"
            portMap[394] = "embl-ndt"
            portMap[395] = "netcp"
            portMap[396] = "netware-ip"
            portMap[397] = "mptn"
            portMap[398] = "kryptolan"
            portMap[399] = "iso-tsap-c2"
            portMap[400] = "osb-sd"
            portMap[401] = "ups"
            portMap[402] = "genie"
            portMap[403] = "decap"
            portMap[404] = "nced"
            portMap[405] = "ncld"
            portMap[406] = "imsp"
            portMap[407] = "timbuktu"
            portMap[408] = "prm-sm"
            portMap[409] = "prm-nm"
            portMap[410] = "decladebug"
            portMap[411] = "rmt"
            portMap[412] = "synoptics-trap"
            portMap[413] = "smsp"
            portMap[414] = "infoseek"
            portMap[415] = "bnet"
            portMap[416] = "silverplatter"
            portMap[417] = "onmux"
            portMap[418] = "hyper-g"
            portMap[419] = "ariel1"
            portMap[420] = "smpte"
            portMap[421] = "ariel2"
            portMap[422] = "ariel3"
            portMap[423] = "opc-job-start"
            portMap[424] = "opc-job-track"
            portMap[425] = "icad-el"
            portMap[426] = "smartsdp"
            portMap[427] = "svrloc"
            portMap[428] = "ocs-cmu"
            portMap[429] = "ocs-amu"
            portMap[430] = "utmpsd"
            portMap[431] = "utmpcd"
            portMap[432] = "iasd"
            portMap[433] = "nnsp"
            portMap[434] = "mobileip-agent"
            portMap[435] = "mobilip-mn"
            portMap[436] = "dna-cml"
            portMap[437] = "comscm"
            portMap[438] = "dsfgw"
            portMap[439] = "dasp"
            portMap[440] = "sgcp"
            portMap[441] = "decvms-sysmgt"
            portMap[442] = "cvc-hostd"
            portMap[443] = "https"
            portMap[444] = "snpp"
            portMap[445] = "microsoft-ds"
            portMap[446] = "ddm-rdb"
            portMap[447] = "ddm-dfm"
            portMap[448] = "ddm-ssl"
            portMap[449] = "as-servermap"
            portMap[450] = "tserver"
            portMap[451] = "sfs-smp-net"
            portMap[452] = "sfs-config"
            portMap[453] = "creativeserver"
            portMap[454] = "contentserver"
            portMap[455] = "creativepartnr"
            portMap[456] = "macon-tcp"
            portMap[457] = "scohelp"
            portMap[458] = "appleqtc"
            portMap[459] = "ampr-rcmd"
            portMap[460] = "skronk"
            portMap[461] = "datasurfsrv"
            portMap[462] = "datasurfsrvsec"
            portMap[463] = "alpes"
            portMap[464] = "kpasswd"
            portMap[465] = "urd"
            portMap[466] = "digital-vrc"
            portMap[467] = "mylex-mapd"
            portMap[468] = "photuris"
            portMap[469] = "rcp"
            portMap[470] = "scx-proxy"
            portMap[471] = "mondex"
            portMap[472] = "ljk-login"
            portMap[473] = "hybrid-pop"
            portMap[474] = "tn-tl-w1"
            portMap[475] = "tcpnethaspsrv"
            portMap[476] = "tn-tl-fd1"
            portMap[477] = "ss7ns"
            portMap[478] = "spsc"
            portMap[479] = "iafserver"
            portMap[480] = "iafdbase"
            portMap[481] = "ph"
            portMap[482] = "bgs-nsi"
            portMap[483] = "ulpnet"
            portMap[484] = "integra-sme"
            portMap[485] = "powerburst"
            portMap[486] = "avian"
            portMap[487] = "saft"
            portMap[488] = "gss-http"
            portMap[489] = "nest-protocol"
            portMap[490] = "micom-pfs"
            portMap[491] = "go-login"
            portMap[492] = "ticf-1"
            portMap[493] = "ticf-2"
            portMap[494] = "pov-ray"
            portMap[495] = "intecourier"
            portMap[496] = "pim-rp-disc"
            portMap[497] = "retrospect"
            portMap[498] = "siam"
            portMap[499] = "iso-ill"
            portMap[500] = "isakmp"
            portMap[501] = "stmf"
            portMap[502] = "mbap"
            portMap[503] = "intrinsa"
            portMap[504] = "citadel"
            portMap[505] = "mailbox-lm"
            portMap[506] = "ohimsrv"
            portMap[507] = "crs"
            portMap[508] = "xvttp"
            portMap[509] = "snare"
            portMap[510] = "fcp"
            portMap[511] = "passgo"
            portMap[512] = "exec"
            portMap[513] = "login"
            portMap[514] = "shell"
            portMap[515] = "printer"
            portMap[516] = "videotex"
            portMap[517] = "talk"
            portMap[518] = "ntalk"
            portMap[519] = "utime"
            portMap[520] = "efs"
            portMap[521] = "ripng"
            portMap[522] = "ulp"
            portMap[523] = "ibm-db2"
            portMap[524] = "ncp"
            portMap[525] = "timed"
            portMap[526] = "tempo"
            portMap[527] = "stx"
            portMap[528] = "custix"
            portMap[529] = "irc-serv"
            portMap[530] = "courier"
            portMap[531] = "conference"
            portMap[532] = "netnews"
            portMap[533] = "netwall"
            portMap[534] = "windream"
            portMap[535] = "iiop"
            portMap[536] = "opalis-rdv"
            portMap[537] = "nmsp"
            portMap[538] = "gdomap"
            portMap[539] = "apertus-ldp"
            portMap[540] = "uucp"
            portMap[541] = "uucp-rlogin"
            portMap[542] = "commerce"
            portMap[543] = "klogin"
            portMap[544] = "kshell"
            portMap[545] = "appleqtcsrvr"
            portMap[546] = "dhcpv6-client"
            portMap[547] = "dhcpv6-server"
            portMap[548] = "afpovertcp"
            portMap[549] = "idfp"
            portMap[550] = "new-rwho"
            portMap[551] = "cybercash"
            portMap[552] = "devshr-nts"
            portMap[553] = "pirp"
            portMap[554] = "rtsp"
            portMap[555] = "dsf"
            portMap[556] = "remotefs"
            portMap[557] = "openvms-sysipc"
            portMap[558] = "sdnskmp"
            portMap[559] = "teedtap"
            portMap[560] = "rmonitor"
            portMap[561] = "monitor"
            portMap[562] = "chshell"
            portMap[563] = "nntps"
            portMap[564] = "9pfs"
            portMap[565] = "whoami"
            portMap[566] = "streettalk"
            portMap[567] = "banyan-rpc"
            portMap[568] = "ms-shuttle"
            portMap[569] = "ms-rome"
            portMap[570] = "meter"
            portMap[571] = "meter"
            portMap[572] = "sonar"
            portMap[573] = "banyan-vip"
            portMap[574] = "ftp-agent"
            portMap[575] = "vemmi"
            portMap[576] = "ipcd"
            portMap[577] = "vnas"
            portMap[578] = "ipdd"
            portMap[579] = "decbsrv"
            portMap[580] = "sntp-heartbeat"
            portMap[581] = "bdp"
            portMap[582] = "scc-security"
            portMap[583] = "philips-vc"
            portMap[584] = "keyserver"
            portMap[586] = "password-chg"
            portMap[587] = "submission"
            portMap[588] = "cal"
            portMap[589] = "eyelink"
            portMap[590] = "tns-cml"
            portMap[591] = "http-alt"
            portMap[592] = "eudora-set"
            portMap[593] = "http-rpc-epmap"
            portMap[594] = "tpip"
            portMap[595] = "cab-protocol"
            portMap[596] = "smsd"
            portMap[597] = "ptcnameservice"
            portMap[598] = "sco-websrvrmg3"
            portMap[599] = "acp"
            portMap[600] = "ipcserver"
            portMap[601] = "syslog-conn"
            portMap[602] = "xmlrpc-beep"
            portMap[603] = "idxp"
            portMap[604] = "tunnel"
            portMap[605] = "soap-beep"
            portMap[606] = "urm"
            portMap[607] = "nqs"
            portMap[608] = "sift-uft"
            portMap[609] = "npmp-trap"
            portMap[610] = "npmp-local"
            portMap[611] = "npmp-gui"
            portMap[612] = "hmmp-ind"
            portMap[613] = "hmmp-op"
            portMap[614] = "sshell"
            portMap[615] = "sco-inetmgr"
            portMap[616] = "sco-sysmgr"
            portMap[617] = "sco-dtmgr"
            portMap[618] = "dei-icda"
            portMap[619] = "compaq-evm"
            portMap[620] = "sco-websrvrmgr"
            portMap[621] = "escp-ip"
            portMap[622] = "collaborator"
            portMap[623] = "oob-ws-http"
            portMap[624] = "cryptoadmin"
            portMap[625] = "dec-dlm"
            portMap[626] = "asia"
            portMap[627] = "passgo-tivoli"
            portMap[628] = "qmqp"
            portMap[629] = "3com-amp3"
            portMap[630] = "rda"
            portMap[631] = "ipp"
            portMap[632] = "bmpp"
            portMap[633] = "servstat"
            portMap[634] = "ginad"
            portMap[635] = "rlzdbase"
            portMap[636] = "ldaps"
            portMap[637] = "lanserver"
            portMap[638] = "mcns-sec"
            portMap[639] = "msdp"
            portMap[640] = "entrust-sps"
            portMap[641] = "repcmd"
            portMap[642] = "esro-emsdp"
            portMap[643] = "sanity"
            portMap[644] = "dwr"
            portMap[645] = "pssc"
            portMap[646] = "ldp"
            portMap[647] = "dhcp-failover"
            portMap[648] = "rrp"
            portMap[649] = "cadview-3d"
            portMap[650] = "obex"
            portMap[651] = "ieee-mms"
            portMap[652] = "hello-port"
            portMap[653] = "repscmd"
            portMap[654] = "aodv"
            portMap[655] = "tinc"
            portMap[656] = "spmp"
            portMap[657] = "rmc"
            portMap[658] = "tenfold"
            portMap[660] = "mac-srvr-admin"
            portMap[661] = "hap"
            portMap[662] = "pftp"
            portMap[663] = "purenoise"
            portMap[664] = "oob-ws-https"
            portMap[665] = "sun-dr"
            portMap[666] = "mdqs"
            portMap[667] = "disclose"
            portMap[668] = "mecomm"
            portMap[669] = "meregister"
            portMap[670] = "vacdsm-sws"
            portMap[671] = "vacdsm-app"
            portMap[672] = "vpps-qua"
            portMap[673] = "cimplex"
            portMap[674] = "acap"
            portMap[675] = "dctp"
            portMap[676] = "vpps-via"
            portMap[677] = "vpp"
            portMap[678] = "ggf-ncp"
            portMap[679] = "mrm"
            portMap[680] = "entrust-aaas"
            portMap[681] = "entrust-aams"
            portMap[682] = "xfr"
            portMap[683] = "corba-iiop"
            portMap[684] = "corba-iiop-ssl"
            portMap[685] = "mdc-portmapper"
            portMap[686] = "hcp-wismar"
            portMap[687] = "asipregistry"
            portMap[688] = "realm-rusd"
            portMap[689] = "nmap"
            portMap[690] = "vatp"
            portMap[691] = "msexch-routing"
            portMap[692] = "hyperwave-isp"
            portMap[693] = "connendp"
            portMap[694] = "ha-cluster"
            portMap[695] = "ieee-mms-ssl"
            portMap[696] = "rushd"
            portMap[697] = "uuidgen"
            portMap[698] = "olsr"
            portMap[699] = "accessnetwork"
            portMap[700] = "epp"
            portMap[701] = "lmp"
            portMap[702] = "iris-beep"
            portMap[704] = "elcsd"
            portMap[705] = "agentx"
            portMap[706] = "silc"
            portMap[707] = "borland-dsj"
            portMap[709] = "entrust-kmsh"
            portMap[710] = "entrust-ash"
            portMap[711] = "cisco-tdp"
            portMap[712] = "tbrpf"
            portMap[713] = "iris-xpc"
            portMap[714] = "iris-xpcs"
            portMap[715] = "iris-lwz"
            portMap[716] = "pana"
            portMap[729] = "netviewdm1"
            portMap[730] = "netviewdm2"
            portMap[731] = "netviewdm3"
            portMap[741] = "netgw"
            portMap[742] = "netrcs"
            portMap[744] = "flexlm"
            portMap[747] = "fujitsu-dev"
            portMap[748] = "ris-cm"
            portMap[749] = "kerberos-adm"
            portMap[750] = "rfile"
            portMap[751] = "pump"
            portMap[752] = "qrh"
            portMap[753] = "rrh"
            portMap[754] = "tell"
            portMap[758] = "nlogin"
            portMap[759] = "con"
            portMap[760] = "ns"
            portMap[761] = "rxe"
            portMap[762] = "quotad"
            portMap[763] = "cycleserv"
            portMap[764] = "omserv"
            portMap[765] = "webster"
            portMap[767] = "phonebook"
            portMap[769] = "vid"
            portMap[770] = "cadlock"
            portMap[771] = "rtip"
            portMap[772] = "cycleserv2"
            portMap[773] = "submit"
            portMap[774] = "rpasswd"
            portMap[775] = "entomb"
            portMap[776] = "wpages"
            portMap[777] = "multiling-http"
            portMap[780] = "wpgs"
            portMap[800] = "mdbs-daemon"
            portMap[801] = "device"
            portMap[802] = "mbap-s"
            portMap[810] = "fcp-udp"
            portMap[828] = "itm-mcell-s"
            portMap[829] = "pkix-3-ca-ra"
            portMap[830] = "netconf-ssh"
            portMap[831] = "netconf-beep"
            portMap[832] = "netconfsoaphttp"
            portMap[833] = "netconfsoapbeep"
            portMap[847] = "dhcp-failover2"
            portMap[848] = "gdoi"
            portMap[853] = "secure-dns"
            portMap[860] = "iscsi"
            portMap[861] = "owamp-control"
            portMap[862] = "twamp-control"
            portMap[873] = "rsync"
            portMap[886] = "iclcnet-locate"
            portMap[887] = "iclcnet-svinfo"
            portMap[888] = "accessbuilder"
            portMap[900] = "omginitialrefs"
            portMap[901] = "smpnameres"
            portMap[902] = "ideafarm-door"
            portMap[903] = "ideafarm-panic"
            portMap[910] = "kink"
            portMap[911] = "xact-backup"
            portMap[912] = "apex-mesh"
            portMap[913] = "apex-edge"
            portMap[989] = "ftps-data"
            portMap[990] = "ftps"
            portMap[991] = "nas"
            portMap[992] = "telnets"
            portMap[993] = "imaps"
            portMap[995] = "pop3s"
            portMap[996] = "vsinet"
            portMap[997] = "maitrd"
            portMap[998] = "busboy"
            portMap[999] = "garcon"
            portMap[1000] = "cadlock2"
            portMap[1001] = "webpush"
            portMap[1010] = "surf"
            portMap[1021] = "exp1"
            portMap[1022] = "exp2"

            return portMap
        }
    }
}
