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
package com.celzero.bravedns.automaton;

import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.List;

public final class AppPermissionGroup {
    private final String mName;
    private final CharSequence mLabel;
    private final ArrayMap<String, Permission> mPermissions = new ArrayMap<>();

    public static AppPermissionGroup create(PackageManager packageManager, PackageInfo packageInfo, String permissionName) {
        PermissionInfo permissionInfo;
        try {
            permissionInfo = packageManager.getPermissionInfo(permissionName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }

        if ((permissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                != PermissionInfo.PROTECTION_DANGEROUS
                || (permissionInfo.flags & PermissionInfo.FLAG_INSTALLED) == 0) {
            return null;
        }

        PackageItemInfo groupInfo = permissionInfo;
        if (permissionInfo.group != null) {
            try {
                groupInfo = packageManager.getPermissionGroupInfo(permissionInfo.group, 0);
            } catch (PackageManager.NameNotFoundException e) {
                /* ignore */
            }
        }

        List<PermissionInfo> permissionInfos = null;
        if (groupInfo instanceof PermissionGroupInfo) {
            try {
                permissionInfos = packageManager.queryPermissionsByGroup(groupInfo.name, 0);
            } catch (PackageManager.NameNotFoundException e) {
                /* ignore */
            }
        }

        return create(packageInfo, groupInfo, permissionInfos);
    }

    public static AppPermissionGroup create(PackageInfo packageInfo, PackageItemInfo groupInfo, List<PermissionInfo> permissionInfos) {

        AppPermissionGroup group = new AppPermissionGroup(groupInfo.name, groupInfo.packageName);

        if (groupInfo instanceof PermissionInfo) {
            permissionInfos = new ArrayList<>();
            permissionInfos.add((PermissionInfo) groupInfo);
        }

        if (permissionInfos == null || permissionInfos.isEmpty()) {
            return null;
        }

        final int permissionCount = packageInfo.requestedPermissions.length;
        for (int i = 0; i < permissionCount; i++) {
            String requestedPermission = packageInfo.requestedPermissions[i];

            PermissionInfo requestedPermissionInfo = null;

            for (PermissionInfo permissionInfo : permissionInfos) {
                if (requestedPermission.equals(permissionInfo.name)) {
                    requestedPermissionInfo = permissionInfo;
                    break;
                }
            }

            if (requestedPermissionInfo == null) {
                continue;
            }

            // Collect only runtime permissions.
            if ((requestedPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                    != PermissionInfo.PROTECTION_DANGEROUS) {
                continue;
            }

            final boolean granted = (packageInfo.requestedPermissionsFlags[i]
                    & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;


            Permission permission = new Permission(requestedPermission, granted, requestedPermissionInfo.protectionLevel);
            group.addPermission(permission);
        }

        return group;
    }

    private AppPermissionGroup(String name, CharSequence label) {
        mName = name;
        mLabel = label;
    }

    private void addPermission(Permission permission) {
        mPermissions.put(permission.mName, permission);
    }

    public boolean hasPermission(String permission) {
        return mPermissions.get(permission) != null;
    }

    String getName() {
        return mName;
    }

    CharSequence getLabel() {
        return mLabel;
    }

    ArrayMap<String, Permission> getPermissions() {
        return mPermissions;
    }

    public boolean areRuntimePermissionsGranted() {
        final int permissionCount = mPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            final Permission permission = mPermissions.valueAt(i);
            if (permission.mGranted) return true;
        }
        return false;
    }
}
