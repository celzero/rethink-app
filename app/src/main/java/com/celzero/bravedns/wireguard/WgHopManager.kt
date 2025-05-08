package com.celzero.bravedns.wireguard

import Logger
import Logger.LOG_TAG_PROXY
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
        io { load(forceRefresh = false) }
    }

    suspend fun load(forceRefresh: Boolean): Int {
        if (!forceRefresh && maps.isNotEmpty()) {
            Logger.i(LOG_TAG_PROXY, "$TAG load: already loaded")
            return maps.size
        }
        maps.clear()
        maps = CopyOnWriteArrayList(db.getAll())
        printMaps()
        Logger.i(LOG_TAG_PROXY, "$TAG load complete: ${maps.size}")
        return maps.size
    }

    private suspend fun add(map: WgHopMap): Pair<Boolean, String> {
        val srcConfig = WireguardManager.getConfigById(toId(map.src))
        val hopConfig = WireguardManager.getConfigById(toId(map.hop))

        if (map.src.isBlank() || map.hop.isBlank()) {
            Logger.e(LOG_TAG_PROXY, "$TAG add: invalid map with empty source and hop")
            return Pair(false, "Invalid map configuration")
        }

        val isAlreadyMapped = maps.any { it.src == map.src && it.hop == map.hop }
        if (isAlreadyMapped) {
            Logger.i(LOG_TAG_PROXY, "$TAG add: already mapped ${map.src} -> ${map.hop}")
            return Pair(false, "Already mapped ${map.src} -> ${map.hop}")
        }

        val canRoute = canRoute(map.src)
        if (!canRoute) {
            Logger.i(LOG_TAG_PROXY, "$TAG add: cannot route ${map.src} -> ${map.hop}")
            return Pair(false, "Can't route ${map.src} -> ${map.hop}")
        }

        if (srcConfig != null && hopConfig != null) {
            Logger.i(LOG_TAG_PROXY, "$TAG add: ${map.src}(${srcConfig.getName()}) -> ${map.hop}(${hopConfig.getName()})")
            val hop = VpnController.createWgHop(map.src, map.hop)
            Logger.i(LOG_TAG_PROXY, "$TAG add: ${map.src} -> ${map.hop}, res: ${hop.first}, ${hop.second}")
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
        db.delete(map)
        res = VpnController.removeHop(map.src)
        Logger.i(LOG_TAG_PROXY, "$TAG delete: ${map.src} -> ${map.hop}, res: $res")
        return res
    }

    suspend fun removeHop(srcId: Int, hopId: Int): Pair<Boolean, String> {
        val src = ID_WG_BASE + srcId
        val hop = ID_WG_BASE + hopId
        var res = Pair(false, "removeHop; Map not found")
        val map = maps.find { it.src == src && it.hop == hop }
        Logger.v(LOG_TAG_PROXY, "$TAG removeHop: $src, $hop, map: $map")
        if (map != null) {
            res = delete(map)
        }
        return res
    }

    suspend fun hop(src: Int, hop: Int):  Pair<Boolean, String> {
        val srcId = ID_WG_BASE + src
        val hopId = ID_WG_BASE + hop
        // see if the src is mapped with some other hop, if so remove it
        val srcMaps = getMapBySrc(srcId)
        if (srcMaps.isNotEmpty()) {
            srcMaps.forEach {
                if (it.hop != hopId && it.hop.isNotEmpty()) {
                    removeHop(src, toId(it.hop))
                }
            }
        }
        Logger.d(LOG_TAG_PROXY, "$TAG hop init: $srcId -> $hopId")
        return if (srcId == hopId) {
            Pair(false, "Can't hop to self")
        } else {
            val map = getMap(srcId, hopId)
            if (map != null) {
                Pair(true, "Already configured")
            } else {
                add(WgHopMap(0, srcId, hopId, true, ""))
            }
        }
    }

    suspend fun getHop(src: Int): String {
        val srcId = ID_WG_BASE + src
        return getHop(srcId)
    }

    suspend fun getHop(src: String): String {
        if (src.isBlank()) return ""
        val map = maps.find { it.src == src && it.isActive }
        return map?.hop ?: ""
    }

    suspend fun getHopableWgs(src: Int): List<Config> {
        val srcId = ID_WG_BASE + src
        val possibleHops: MutableSet<Config> = mutableSetOf()
        // add the hop of this src to the list even if it is not active
        val map = maps.find { it.src == srcId }
        if (map != null) {
            val hopConfig = WireguardManager.getConfigById(toId(map.hop))
            if (hopConfig != null) {
                possibleHops.add(hopConfig)
            }
        }
        // get the list of active wgs
        val activeWgs = WireguardManager.getActiveConfigs()
        Logger.d(LOG_TAG_PROXY, "$TAG getHopableWgs activeWgs: $activeWgs")
        // remove the wireguards which are part of other hops
        val filteredWgs = activeWgs.filter { wg ->
            val wgId = wg.getId()
            val isHop = maps.any { toId(it.src) == wgId }
            !isHop
        }
        Logger.d(LOG_TAG_PROXY, "$TAG getHopableWgs filteredWgs: $filteredWgs")
        possibleHops.addAll(filteredWgs.filter { wg ->
            wg.getId() != toId(srcId) && canRoute(srcId)
        })
        Logger.i(LOG_TAG_PROXY, "$TAG getHopableWgs possibleHops: ${possibleHops.map { it.getName() }}")
        return possibleHops.toList() // return the immutable list
    }

    fun isAlreadyHop(id: String): Boolean {
        return maps.any { it.hop == id }
    }

    fun getMaps(): List<WgHopMap> {
        return maps
    }

    fun getMap(src: String, hop: String): WgHopMap? {
        return maps.find { it.src == src && it.hop == hop }
    }

    fun getAllHop(): List<String> {
        // get all the hop from the maps
        return maps.map { it.hop }
    }

    fun getMapBySrc(src: String): List<WgHopMap> {
        return maps.filter { it.src == src }
    }

    fun getMapByHop(hop: String): List<WgHopMap> {
        return maps.filter { it.hop == hop }
    }

    suspend fun handleWgDelete(id: Int) {
        val idStr = ID_WG_BASE + id
        // if the src is deleted, then remove all the maps which are using this src
        val srcMap = getMapBySrc(idStr)
        if (srcMap.isNotEmpty()) {
            srcMap.forEach { map ->
                Logger.i(LOG_TAG_PROXY, "$TAG handleWgDelete: deleting map $map")
                delete(map)
            }
        }
        // if the src is hop for any other map, then it can't route, remove all the maps
        val hopMap = getMapByHop(idStr)
        if (hopMap.isNotEmpty()) {
            hopMap.forEach { map ->
                Logger.i(LOG_TAG_PROXY, "$TAG handleWgDelete: deleting map $map")
                delete(map)
            }
        }
    }

    fun isWgEitherHopOrSrc(id: Int): Boolean {
        val idStr = ID_WG_BASE + id
        val srcMap = getMapBySrc(idStr)
        val hopMap = getMapByHop(idStr)
        return srcMap.isNotEmpty() || hopMap.isNotEmpty()
    }

    fun canRoute(src: String): Boolean {
        // if the src is hop for any other map, then it can't route
        val srcMap = getMapBySrc(src)
        val can = srcMap.none { it.hop == src }
        // or if the src is already part of any other map as src
        val hopMap = getMapByHop(src)
        val can2 = hopMap.none { it.src == src && it.isActive }
        Logger.d(LOG_TAG_PROXY, "$TAG canRoute($src) srcMap: $srcMap, can: $can, hopMap: $hopMap, can2: $can2")
        return can && can2
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
