package com.celzero.bravedns.wireguard

import Logger
import Logger.LOG_TAG_PROXY
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.WgHopMap
import com.celzero.bravedns.database.WgHopMapRepository
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.INVALID_CONF_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.CopyOnWriteArrayList

object WgHopManager: KoinComponent {

    private val db: WgHopMapRepository by inject()
    private var maps: CopyOnWriteArrayList<WgHopMap> = CopyOnWriteArrayList()
    private const val TAG = "WgHopMgr"

    init {
        io { load() }
    }

    suspend fun load(): Int {
        if (maps.isNotEmpty()) {
            Logger.i(LOG_TAG_PROXY, "$TAG reload hop: ${maps.size}")
        }
        maps.clear()
        maps = CopyOnWriteArrayList(db.getAll())
        printMaps()
        Logger.i(LOG_TAG_PROXY, "$TAG load complete: ${maps.size}")
        return maps.size
    }

    private suspend fun add(map: WgHopMap): Pair<Boolean, String> {
        val srcConfig = WireguardManager.getConfigById(toId(map.src))
        val viaConfig = WireguardManager.getConfigById(toId(map.via))

        val isAlreadyMapped = maps.any { it.src == map.src && it.via == map.via }
        if (isAlreadyMapped) {
            Logger.i(LOG_TAG_PROXY, "$TAG add: already mapped ${map.src} -> ${map.via}")
            return Pair(false, "Already mapped ${map.src} -> ${map.via}")
        }

        val canRoute = canRoute(map.src)
        if (!canRoute) {
            Logger.i(LOG_TAG_PROXY, "$TAG add: Can't route $map.src -> $map.via")
            return Pair(false, "Can't route ${map.src} -> ${map.via}")
        }

        if (srcConfig != null && viaConfig != null) {
            Logger.i(LOG_TAG_PROXY, "$TAG add: ${map.src}(${srcConfig.getName()}) -> ${map.via}(${viaConfig.getName()})")
            val hop = VpnController.createWgHop(map.src, map.via)
            Logger.i(LOG_TAG_PROXY, "$TAG add: ${map.src} -> ${map.via}, res: ${hop.first}, ${hop.second}")
            if (hop.first) {
                map.isActive = true
                db.insert(map)
                maps.add(map)
                return Pair(true, hop.second)
            } else {
                return Pair(false, hop.second)
            }
        } else {
            Logger.i(LOG_TAG_PROXY, "$TAG add: invalid config")
            return Pair(false, "Invalid config")
        }
    }

    private suspend fun delete(map: WgHopMap): Pair<Boolean, String> {
        var res = Pair(false, "Map not found")
        val rmv = maps.remove(map)
        if (!rmv) {
            Logger.i(LOG_TAG_PROXY, "$TAG delete: map not found")
            return res
        }
        res = VpnController.createWgHop(map.src, "")
        db.delete(map)
        Logger.i(LOG_TAG_PROXY, "$TAG delete: ${map.src} -> ${map.via}, res: $res")
        return Pair(rmv, "Removed ${map.src} -> ${map.via}")
    }

    suspend fun removeHop(srcId: Int, viaId: Int): Pair<Boolean, String> {
        val src = ID_WG_BASE + srcId
        val via = ID_WG_BASE + viaId
        var res = Pair(false, "Map not found")
        val isAvailableInMap = maps.find { it.src == src && it.via == via }
        Logger.v(LOG_TAG_PROXY, "$TAG removeHop: $src")
        if (isAvailableInMap != null) {
            res = delete(isAvailableInMap)
        } else {
            Logger.i(LOG_TAG_PROXY, "$TAG delete: map not found")
        }
        return res
    }

    suspend fun hop(src: Int, via: Int):  Pair<Boolean, String> {
        val srcId = ID_WG_BASE + src
        val viaId = ID_WG_BASE + via
        // see if the src is mapped with some other via, if so remove it
        val srcMaps = getMapBySrc(srcId)
        if (srcMaps.isNotEmpty()) {
            srcMaps.forEach {
                if (it.via != viaId && it.via.isNotEmpty()) {
                    removeHop(src, toId(it.via))
                }
            }
        }
        Logger.d(LOG_TAG_PROXY, "$TAG hop init: $srcId -> $viaId")
        return if (srcId == viaId) {
            Pair(false, "Can't hop to self")
        } else {
            val map = getMap(srcId, viaId)
            if (map != null) {
                Pair(true, "Already hopped")
            } else {
                add(WgHopMap(0, srcId, viaId, true, ""))
            }
        }
    }

    suspend fun getVia(src: Int): String {
        val srcId = ID_WG_BASE + src
        return getVia(srcId)
    }

    suspend fun getVia(src: String): String {
        val map = maps.find { it.src == src && it.isActive }
        return map?.via ?: ""
    }

    suspend fun getHopableWgs(src: Int): List<Config> {
        val srcId = ID_WG_BASE + src
        val possibleHops: MutableSet<Config> = mutableSetOf()
        // add the via of this src to the list even if it is not active
        val via = maps.find { it.src == srcId }
        if (via != null) {
            val viaConfig = WireguardManager.getConfigById(toId(via.via))
            if (viaConfig != null) {
                possibleHops.add(viaConfig)
            }
        }
        // get the list of active wgs
        val activeWgs = WireguardManager.getActiveConfigs()
        Logger.d(LOG_TAG_PROXY, "$TAG getHopableWgs activeWgs: $activeWgs")
        possibleHops.addAll(activeWgs.filter { wg ->
            wg.getId() != toId(srcId) && canRoute(srcId)
        })
        Logger.i(LOG_TAG_PROXY, "$TAG getHopableWgs possibleHops: ${possibleHops.map { it.getName() }}")
        return possibleHops.toList() // return the immutable list
    }

    fun isAlreadyVia(id: String): Boolean {
        return maps.any { it.via == id }
    }

    fun getMaps(): List<WgHopMap> {
        return maps
    }

    fun getMap(src: String, via: String): WgHopMap? {
        return maps.find { it.src == src && it.via == via }
    }

    fun getAllVia(): List<String> {
        // get all the via from the maps
        return maps.map { it.via }
    }

    fun getMapBySrc(src: String): List<WgHopMap> {
        return maps.filter { it.src == src }
    }

    fun getMapByVia(via: String): List<WgHopMap> {
        return maps.filter { it.via == via }
    }

    fun canRoute(src: String): Boolean {
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
            Logger.i(LOG_TAG_PROXY, "$TAG err converting string id to int: $id, ${e.message}")
            INVALID_CONF_ID
        }
    }

    fun printMaps() {
        Logger.v(LOG_TAG_PROXY, "$TAG printMaps: $maps")
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }

}
