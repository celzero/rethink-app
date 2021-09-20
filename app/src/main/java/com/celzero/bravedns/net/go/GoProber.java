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

import doh.Transport;
import tun2socks.Tun2socks;

/**
 * Implements a Probe using the Go-based DoH client.
 */
public class GoProber extends Prober {

    private final Context context;

    public GoProber(Context context) {
        this.context = context;
    }

    @Override
    public void probe(String url, Callback callback) {
        new Thread(() -> {
            String dohIPs = GoVpnAdapter.Companion.getIpString(context, url);
            try {
                // commented out the below code, as the app supports 23+
                /* // Protection isn't needed for Lollipop+, or if the VPN is not active.
                Protector protector = VERSION.SDK_INT >= VERSION_CODES.LOLLIPOP ? null :
                        VpnController.INSTANCE.getBraveVpnService(); */
                Transport transport = Tun2socks.newDoHTransport(url, dohIPs, /* protector */null, /* clientAuth */null, /* listener */null);
                if (transport == null) {
                    callback.onCompleted(false);
                    return;
                }
                byte[] response = transport.query(QUERY_DATA);
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
