package com.celzero.bravedns.automaton;

public class Permission {
    final String mName;
    final boolean mGranted;
    final int mProtectionLevel;

    Permission(String permissionName, boolean granted, int protectionLevel) {
        mName = permissionName;
        mGranted = granted;
        mProtectionLevel = protectionLevel;
    }
}
