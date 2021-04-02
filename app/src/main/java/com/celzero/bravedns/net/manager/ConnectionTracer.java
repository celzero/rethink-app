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

package com.celzero.bravedns.net.manager;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import static android.content.Context.CONNECTIVITY_SERVICE;
import static com.celzero.bravedns.util.Constants.LOG_TAG;

public class ConnectionTracer {
    private static final boolean DEBUG = false;
    private static final int MISSING_UID = -2000;
    private final ConnectivityManager cm;
    private final Cache<String, Integer> uidCache;

    public ConnectionTracer(Context ctx) {
        this.cm = (ConnectivityManager) ctx.getSystemService(CONNECTIVITY_SERVICE);
        // Cache the UID for the next 30 seconds.
        // the UID will expire after 30 seconds of the write.
        // Key for the cache is protocol, local, remote
        this.uidCache = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();
    }

    @TargetApi(Build.VERSION_CODES.Q)
    public int getUidQ(int protocol, String sourceIp, int sourcePort, String destIp, int destPort) {
        if (cm == null ){
            return MISSING_UID;
        }

        // https://android.googlesource.com/platform/development/+/da84168fb2f5eb5ca012c3f430f701bc64472f34/ndk/platforms/android-21/include/linux/in.h
        if (protocol != 6 /* TCP */ && protocol != 17 /* UDP */) {
            //if (DEBUG) Log.d(TAG, "protocol is not valid : "+protocol);
            return MISSING_UID;
        }

        InetSocketAddress local;
        InetSocketAddress remote;

        if (DEBUG) Log.d(LOG_TAG, sourceIp +  " [" + sourcePort + "] to " + destIp + " [" + destPort + "]");

        if (TextUtils.isEmpty(sourceIp) || sourceIp.split("\\.").length < 4) {
            //Log.w(TAG, "empty/invalid sourceIp " + sourceIp);
            local = new InetSocketAddress(sourcePort);
        } else {
            local = new InetSocketAddress(sourceIp, sourcePort);
        }

        if (TextUtils.isEmpty(destIp) || destIp.split("\\.").length < 4) {
            Log.w(LOG_TAG, "empty/invalid destIp " + destIp);
            remote = new InetSocketAddress(destPort);
        } else {
            remote = new InetSocketAddress(destIp, destPort);
        }
        int uid = -1;
        String key = protocol+local.getAddress().getHostAddress()+remote.getAddress().getHostAddress();
        try {
            int value = uidCache.getIfPresent(key);
            Log.d(LOG_TAG, "Cache GetUidQ uid: " + value);
            return value;
        } catch (Exception ignored) { }

        try{
            uid = cm.getConnectionOwnerUid(protocol, local, remote);
            uidCache.put(key, uid);
        }catch(SecurityException secEx){
            Log.e(LOG_TAG,"NETWORK_STACK permission - "+secEx.getMessage());
        }

        if (DEBUG) Log.d(LOG_TAG, "GetUidQ(" + local + "," + remote + "): " + uid);

        return uid;
    }
}
