package com.celzero.bravedns.automaton

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashMap

class AppPermissions(private val packageManager: PackageManager, packageInfo: PackageInfo) {
    private val mGroups = ArrayList<AppPermissionGroup>()

    private val mNameToGroupMap = LinkedHashMap<CharSequence, AppPermissionGroup>()

    private var packageInfo: PackageInfo? = null
        private set

    val permissionGroups: List<AppPermissionGroup>
        get() = mGroups

    init {
        this.packageInfo = packageInfo
        loadPermissionGroups()
    }

    fun refresh() {
        loadPackageInfo()
        loadPermissionGroups()
    }

    fun getPermissionGroup(name: CharSequence): AppPermissionGroup? {
        return mNameToGroupMap[name]
    }

    private fun loadPackageInfo() {
        try {
            packageInfo = packageManager.getPackageInfo(
                packageInfo!!.packageName, PackageManager.GET_PERMISSIONS
            )
        } catch (e: PackageManager.NameNotFoundException) {
        }
    }

    private fun loadPermissionGroups() {
        mGroups.clear()

        if (packageInfo!!.requestedPermissions == null) {
            return
        }
        for (requestedPerm in packageInfo!!.requestedPermissions) {
            addPermissionGroupIfNeeded(requestedPerm)
        }

        mNameToGroupMap.clear()
        for (group in mGroups) {
            mNameToGroupMap[group.label] = group
        }
    }

    private fun addPermissionGroupIfNeeded(permission: String) {
        if (getGroupForPermission(permission) != null) {
            return
        }

        Log.w("PermisionsManager", "bbbb __________ bbbb " + permission)
        val group = AppPermissionGroup.create(
            packageManager,
            packageInfo, permission
        ) ?: return

        mGroups.add(group)
    }

    private fun getGroupForPermission(permission: String): AppPermissionGroup? {
        for (group in mGroups) {
            if (group.hasPermission(permission)) {
                return group
            }
        }
        return null
    }
}