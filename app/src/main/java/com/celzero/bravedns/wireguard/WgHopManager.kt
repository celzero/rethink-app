package com.celzero.bravedns.wireguard

import Logger
import Logger.LOG_TAG_PROXY
import com.celzero.bravedns.database.WgHopMap
import com.celzero.bravedns.database.WgHopMapRepository
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.INVALID_CONF_ID
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.CopyOnWriteArrayList

object WgHopManager: KoinComponent {

    private val db: WgHopMapRepository by inject()
    private var maps: CopyOnWriteArrayList<WgHopMap> = CopyOnWriteArrayList()
    private const val TAG = "WgHopMgr"

    suspend fun load(): Int {
        maps = CopyOnWriteArrayList(db.getAll())
        printMaps()
        Logger.i(LOG_TAG_PROXY, "$TAG load complete: ${maps.size}")
        return maps.size
    }

    private suspend fun add(map: WgHopMap): Pair<Boolean, String> {
        val srcConfig = WireguardManager.getConfigById(toId(map.src))
        val viaConfig = WireguardManager.getConfigById(toId(map.via))
        val canRoute = canRoute(map.src, map.via)
        if (!canRoute) {
            Logger.i(LOG_TAG_PROXY, "$TAG add: Can't route $map.src -> $map.via")
            return Pair(false, "Can't route ${map.src} -> ${map.via}")
        }
        if (srcConfig != null && viaConfig != null) {
            Logger.i(LOG_TAG_PROXY, "$TAG add: ${map.src}(${srcConfig.getName()}) -> ${map.via}(${viaConfig.getName()})")
            val c = VpnController.createWgHop(srcConfig, viaConfig)
            Logger.i(LOG_TAG_PROXY, "$TAG add: ${c.first}, ${c.second}")
            val temp = VpnController.via(ID_WG_BASE + srcConfig.getId())
            Logger.i(LOG_TAG_PROXY, "$TAG via for ${ID_WG_BASE + srcConfig.getId()}: $temp")
            val temp1 = VpnController.via(ID_WG_BASE + viaConfig.getId())
            Logger.i(LOG_TAG_PROXY, "$TAG via for ${ID_WG_BASE + viaConfig.getId()}: $temp1")
            val hop = VpnController.createWgHop(srcConfig, viaConfig)
            if (hop.first) {
                map.isActive = true
                db.insert(map)
                maps.add(map)
                return Pair(true, hop.second)
            } else {
                return Pair(false, hop.second)
            }
        } else {
            Logger.i(LOG_TAG_PROXY, "$TAG add: Invalid config")
            return Pair(false, "Invalid config")
        }
    }

    private suspend fun delete(map: WgHopMap) {
        db.delete(map)
        maps.remove(map)
        val srcConfig = WireguardManager.getConfigById(toId(map.src))
        val viaConfig = WireguardManager.getConfigById(toId(map.via))
        if (srcConfig != null && viaConfig != null) {
            val hop = VpnController.createWgHop(srcConfig, null)
            Logger.i(LOG_TAG_PROXY, "$TAG delete: ${hop.first}, ${hop.second}")
        }
        Logger.i(LOG_TAG_PROXY, "$TAG delete: ${map.src} -> ${map.via}")
    }

    suspend fun deleteBySrc(src: String): Pair<Boolean, String> {
        var res = Pair(false, "Map not found")
        val map = maps.find { it.src == src }
        Logger.d(LOG_TAG_PROXY, "$TAG deleteById: $src")
        if (map != null) {
            db.deleteById(map.id)
            maps.remove(map)
            val srcConfig = WireguardManager.getConfigById(toId(map.src))
            val viaConfig = WireguardManager.getConfigById(toId(map.via))
            if (srcConfig != null && viaConfig != null) {
                res = VpnController.createWgHop(srcConfig, null)
                Logger.i(LOG_TAG_PROXY, "$TAG delete: ${res.first}, ${res.second}")
            }
            Logger.i(LOG_TAG_PROXY, "$TAG delete: ${map.src} -> ${map.via}")
        } else {
            Logger.i(LOG_TAG_PROXY, "$TAG delete: map not found")
        }
        return res
    }

    suspend fun hop(src: Int, via: Int):  Pair<Boolean, String> {
        val srcId = ID_WG_BASE + src
        val viaId = ID_WG_BASE + via
        val map = maps.find { it.src == srcId && it.via == viaId }
        Logger.d(LOG_TAG_PROXY, "$TAG hop init: $srcId -> $viaId")
        return if (map != null) {
            Logger.i(LOG_TAG_PROXY, "$TAG hop: ${map.src} -> ${map.via}")
            add(map)
        } else {
            Logger.i(LOG_TAG_PROXY, "$TAG hop: map null, create one with $src -> $via")
            add(WgHopMap(0, srcId, viaId, false, ""))
        }
    }

    suspend fun getHopableWgs(src: String): List<Config> {
        // get the list of active wgs
        val activeWgs = WireguardManager.getActiveConfigs()
        Logger.d(LOG_TAG_PROXY, "$TAG getHopableWgs activeWgs: $activeWgs")
        // see if there is any map with src as via, if so return empty list
        val viaWgs = maps.filter { it.via == src }
        if (viaWgs.isNotEmpty()) {
            Logger.d(LOG_TAG_PROXY, "$TAG getHopableWgs viaWgs: $viaWgs")
            return emptyList()
        }
        Logger.d(LOG_TAG_PROXY, "$TAG getHopableWgs viaWgs: $viaWgs")
        val possibleHops = activeWgs.filter { wg ->
            wg.getId() != toId(src) && canRoute(src, wg.getId().toString())
        }
        Logger.d(LOG_TAG_PROXY, "$TAG getHopableWgs possibleHops: ${possibleHops.map { it.getName() }}")
        return possibleHops
    }

    fun getMaps(): List<WgHopMap> {
        return maps
    }

    fun getMap(src: String, via: String): WgHopMap? {
        return maps.find { it.src == src && it.via == via }
    }

    fun getMapBySrc(src: String): List<WgHopMap> {
        return maps.filter { it.src == src }
    }

    fun getMapByVia(via: String): List<WgHopMap> {
        return maps.filter { it.via == via }
    }

    fun canRoute(src: String, via: String): Boolean {
        // if the src is via for any other map, then it can't route
        val srcMaps = getMapBySrc(src)
        val can = srcMaps.none { it.via == src }
        Logger.d(LOG_TAG_PROXY, "$TAG canRoute srcMaps: $srcMaps, can: $can")
        return can
    }

    private fun toId(id: String): Int {
        return try {
            val configId = id.substring(ID_WG_BASE.length)
            configId.toIntOrNull() ?: INVALID_CONF_ID
        } catch (e: Exception) {
            Logger.i(LOG_TAG_PROXY, "$TAG err converting string id to int: $id")
            INVALID_CONF_ID
        }
    }

    fun printMaps() {
        Logger.v(LOG_TAG_PROXY, "$TAG printMaps: $maps")
    }

}
