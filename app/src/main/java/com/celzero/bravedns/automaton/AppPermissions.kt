/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.automaton

import android.content.pm.PackageInfo
import android.content.pm.PackageManager

class AppPermissions(private val packageManager: PackageManager, packageInfo: PackageInfo) {
    private val mGroups = ArrayList<AppPermissionGroup>()

    private val mNameToGroupMap = LinkedHashMap<CharSequence, AppPermissionGroup>()

    private var packageInfo: PackageInfo? = null

    val permissionGroups: List<AppPermissionGroup>
        get() = mGroups

    init {
        this.packageInfo = packageInfo
        loadPermissionGroups()
    }


    fun getPermissionGroup(name: CharSequence): AppPermissionGroup? {
        return mNameToGroupMap[name]
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

        //Log.w("PermisionsManager", "bbbb __________ bbbb " + permission)
        val group = AppPermissionGroup.create(packageManager, packageInfo, permission) ?: return

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
