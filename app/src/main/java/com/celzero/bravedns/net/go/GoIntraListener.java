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

import android.os.SystemClock;

import androidx.collection.LongSparseArray;

import com.celzero.bravedns.net.dns.DnsPacket;
import com.celzero.bravedns.net.doh.Transaction;
import com.celzero.bravedns.net.doh.Transaction.Status;
import com.celzero.bravedns.service.BraveVPNService;

import java.util.Calendar;

import dnscrypt.Dnscrypt;
import dnscrypt.Summary;
import doh.Doh;
import doh.Token;
import intra.Listener;
import intra.TCPSocketSummary;
import intra.UDPSocketSummary;

//import tunnel.IntraListener;


/**
 * This is a callback class that is passed to our go-tun2socks code.  Go calls this class's methods
 * when a socket has concluded, with performance metrics for that socket, and this class forwards
 * those metrics to Firebase.
 */
public class GoIntraListener implements Listener {

    // UDP is often used for one-off messages and pings.  The relative overhead of reporting metrics
    // on these short messages would be large, so we only report metrics on sockets that transfer at
    // least this many bytes.
    private static final int UDP_THRESHOLD_BYTES = 10000;

    private final BraveVPNService vpnService;

    GoIntraListener(BraveVPNService vpnService) {
        this.vpnService = vpnService;
    }

    @Override
    public void onTCPSocketClosed(TCPSocketSummary summary) {

    }

    @Override
    public void onUDPSocketClosed(UDPSocketSummary summary) {

    }

    private static final LongSparseArray<Status> goStatusMap = new LongSparseArray<>();

    static {
        goStatusMap.put(Doh.Complete, Status.COMPLETE);
        goStatusMap.put(Doh.SendFailed, Status.SEND_FAIL);
        goStatusMap.put(Doh.HTTPError, Status.HTTP_ERROR);
        goStatusMap.put(Doh.BadQuery, Status.INTERNAL_ERROR); // TODO: Add a BAD_QUERY Status
        goStatusMap.put(Doh.BadResponse, Status.BAD_RESPONSE);
        goStatusMap.put(Doh.InternalError, Status.INTERNAL_ERROR);
    }

    private static final LongSparseArray<Status> dohStatusMap = new LongSparseArray<>();
    private static final LongSparseArray<Status> dnscryptStatusMap = new LongSparseArray<>();

    static {
        // TODO: Add a BAD_QUERY Status
        dohStatusMap.put(Doh.Complete, Status.COMPLETE);
        dohStatusMap.put(Doh.SendFailed, Status.SEND_FAIL);
        dohStatusMap.put(Doh.HTTPError, Status.HTTP_ERROR);
        dohStatusMap.put(Doh.BadQuery, Status.INTERNAL_ERROR);
        dohStatusMap.put(Doh.BadResponse, Status.BAD_RESPONSE);
        dohStatusMap.put(Doh.InternalError, Status.INTERNAL_ERROR);
        dnscryptStatusMap.put(Dnscrypt.Complete, Status.COMPLETE);
        dnscryptStatusMap.put(Dnscrypt.SendFailed, Status.SEND_FAIL);
        dnscryptStatusMap.put(Dnscrypt.Error, Status.CANCELED);
        dnscryptStatusMap.put(Dnscrypt.BadQuery, Status.INTERNAL_ERROR);
        dnscryptStatusMap.put(Dnscrypt.BadResponse, Status.BAD_RESPONSE);
        dnscryptStatusMap.put(Dnscrypt.InternalError, Status.INTERNAL_ERROR);
    }


    @Override
    public boolean onDNSCryptQuery(String s) {
        return false;
    }

    @Override
    public void onDNSCryptResponse(Summary summary) {
        final DnsPacket query;
        try {
            query = new DnsPacket(summary.getQuery());
        } catch (Exception e) {
            return;
        }
        long latencyMs = (long) (1000 * summary.getLatency());
        long nowMs = SystemClock.elapsedRealtime();
        long queryTimeMs = nowMs - latencyMs;
        Transaction transaction = new Transaction(query, queryTimeMs);
        transaction.response = summary.getResponse();
        transaction.responseTime = latencyMs;
        transaction.serverIp = summary.getServer();
        transaction.relayIp = summary.getRelayServer();
        transaction.blocklist = summary.getBlocklists();
        transaction.status = dnscryptStatusMap.get(summary.getStatus());
        transaction.responseCalendar = Calendar.getInstance();
        transaction.isDNSCrypt = true;
        vpnService.recordTransaction(transaction);
    }

    @Override
    public Token onQuery(String url) {
        return null;
    }

    private static int len(byte[] a) {
        return a != null ? a.length : 0;
    }

    @Override
    public void onResponse(Token token, doh.Summary summary) {
        final DnsPacket query;
        try {
            query = new DnsPacket(summary.getQuery());
        } catch (Exception e) {
            return;
        }
        long latencyMs = (long) (1000 * summary.getLatency());
        long nowMs = SystemClock.elapsedRealtime();
        long queryTimeMs = nowMs - latencyMs;
        Transaction transaction = new Transaction(query, queryTimeMs);
        transaction.response = summary.getResponse();
        transaction.responseTime = latencyMs;
        transaction.serverIp = summary.getServer();
        transaction.status = goStatusMap.get(summary.getStatus());
        transaction.responseCalendar = Calendar.getInstance();
        transaction.blocklist = summary.getBlocklists();
        transaction.isDNSCrypt = false;
        vpnService.recordTransaction(transaction);
    }
}
