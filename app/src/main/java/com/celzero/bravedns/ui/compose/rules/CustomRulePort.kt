/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.rules

import android.content.Context
import android.net.Uri
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomIp
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.ui.fragment.RulesImportHelper
import com.celzero.bravedns.util.Utilities
import inet.ipaddr.IPAddress

/** Fakeable UI boundary. Production delegates to the original manager/import contracts. */
internal interface CustomRulePort {
    fun normalize(input: String): String
    fun parseIp(input: String): Pair<IPAddress?, Int>
    fun isCidrEnforceable(ip: IPAddress?): Boolean
    fun extractHost(input: String): String?
    fun isWildcard(host: String): Boolean
    fun isDomain(host: String): Boolean
    suspend fun addIp(uid: Int, ip: IPAddress, port: Int, status: IpRulesManager.IpRuleStatus)
    suspend fun replaceIp(previous: CustomIp, ip: IPAddress, port: Int, status: IpRulesManager.IpRuleStatus)
    suspend fun addDomain(domain: String, status: DomainRulesManager.Status, type: DomainRulesManager.DomainType, uid: Int)
    suspend fun replaceDomain(domain: String, status: DomainRulesManager.Status, type: DomainRulesManager.DomainType, previous: CustomDomain)
    suspend fun deleteIps(items: List<CustomIp>)
    suspend fun deleteIpsByUid(uid: Int)
    suspend fun deleteAllIps()
    suspend fun deleteDomains(items: List<CustomDomain>)
    suspend fun deleteDomainsByUid(uid: Int)
    suspend fun deleteAllDomains()
    suspend fun parseImport(uri: Uri, type: RulesImportHelper.ImportType): RulesImportHelper.ParsedFile?
    suspend fun importRules(entries: List<String>, importType: RulesImportHelper.ImportType, uid: Int,
        ipStatus: IpRulesManager.IpRuleStatus = IpRulesManager.IpRuleStatus.BLOCK,
        domainStatus: DomainRulesManager.Status = DomainRulesManager.Status.BLOCK): RulesImportHelper.ImportSummary
}
internal class ManagerCustomRulePort(private val context: Context) : CustomRulePort {
    override fun normalize(input: String) = Utilities.removeLeadingAndTrailingDots(input)
    override fun parseIp(input: String) = IpRulesManager.getIpNetPort(input)
    override fun isCidrEnforceable(ip: IPAddress?) = IpRulesManager.isCidrEnforceable(ip)
    override fun extractHost(input: String) = DomainRulesManager.extractHost(input)
    override fun isWildcard(host: String) = DomainRulesManager.isWildCardEntry(host)
    override fun isDomain(host: String) = DomainRulesManager.isValidDomain(host)
    override suspend fun addIp(uid: Int, ip: IPAddress, port: Int, status: IpRulesManager.IpRuleStatus) { IpRulesManager.addIpRule(uid, ip, port, status, proxyId = "", proxyCC = "") }
    override suspend fun replaceIp(previous: CustomIp, ip: IPAddress, port: Int, status: IpRulesManager.IpRuleStatus) { IpRulesManager.replaceIpRule(previous, ip, port, status, "", "") }
    override suspend fun addDomain(domain: String, status: DomainRulesManager.Status, type: DomainRulesManager.DomainType, uid: Int) { DomainRulesManager.addDomainRule(domain, status, type, uid = uid) }
    override suspend fun replaceDomain(domain: String, status: DomainRulesManager.Status, type: DomainRulesManager.DomainType, previous: CustomDomain) { DomainRulesManager.updateDomainRule(domain, status, type, previous) }
    override suspend fun deleteIps(items: List<CustomIp>) { IpRulesManager.deleteRules(items) }
    override suspend fun deleteIpsByUid(uid: Int) { IpRulesManager.deleteRulesByUid(uid) }
    override suspend fun deleteAllIps() { IpRulesManager.deleteAllAppsRules() }
    override suspend fun deleteDomains(items: List<CustomDomain>) { DomainRulesManager.deleteRules(items) }
    override suspend fun deleteDomainsByUid(uid: Int) { DomainRulesManager.deleteRulesByUid(uid) }
    override suspend fun deleteAllDomains() { DomainRulesManager.deleteAllRules() }
    override suspend fun parseImport(uri: Uri, type: RulesImportHelper.ImportType) = RulesImportHelper.parseFile(context, uri, type)
    override suspend fun importRules(entries: List<String>, importType: RulesImportHelper.ImportType, uid: Int, ipStatus: IpRulesManager.IpRuleStatus, domainStatus: DomainRulesManager.Status) = RulesImportHelper.importRules(entries, importType, uid, ipStatus, domainStatus)
}
