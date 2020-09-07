package com.celzero.bravedns.database


class BlockedConnectionsRepository(private val blockedConnectionsDAO: BlockedConnectionsDAO) {

    fun updateAsync(blockedConnections: BlockedConnections) {
        blockedConnectionsDAO.update(blockedConnections)
    }

    fun deleteAsync(blockedConnections: BlockedConnections) {
        blockedConnectionsDAO.delete(blockedConnections)
    }

    fun clearFirewallRules(uid: Int){
        blockedConnectionsDAO.clearFirewallRules(uid)
    }

    fun insertAsync(blockedConnections: BlockedConnections) {
        blockedConnectionsDAO.insert(blockedConnections)
    }

    fun getBlockedConnections(): List<BlockedConnections> {
        return blockedConnectionsDAO.getBlockedConnections()
    }

    fun getBlockedConnectionsByUID(uid: Int): List<BlockedConnections> {
        return blockedConnectionsDAO.getBlockedConnectionsByUID(uid)
    }

    //fun deleteRules(uid, )
}