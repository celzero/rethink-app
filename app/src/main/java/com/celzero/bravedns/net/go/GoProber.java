/*
Copyright 2020 RethinkDNS developers

Copyright 2019 Jigsaw Operations LLC

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
package com.celzero.bravedns.net.go;

import android.content.Context;

import com.celzero.bravedns.net.doh.Prober;

import dnsx.Summary;
import dnsx.Transport;

/**
 * Implements a Probe using the Go-based DoH client.
 */
public class GoProber extends Prober {

    private final Context context;

    private static final String PROBER_TAG = "Prober";

    public GoProber(Context context) {
        this.context = context;
    }

    @Override
    public void probe(String url, Callback callback) {
        new Thread(() -> {
            String dohIPs = GoVpnAdapter.Companion.getIpString(context, url);
            try {
                Transport transport = null; //Intra.newDoHTransport(PROBER_TAG, url, dohIPs);
                if (transport == null) {
                    callback.onCompleted(false);
                    return;
                }
                final Summary summary = new dnsx.Summary();
                byte[] response = transport.query("", QUERY_DATA, summary);
                if (response != null && response.length > 0) {
                    callback.onCompleted(true);
                    return;
                }
                callback.onCompleted(false);
            } catch (Exception e) {
                callback.onCompleted(false);
            }
        }, "GoProber-probe").start();
    }
}
