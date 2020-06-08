/*
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

import com.celzero.bravedns.service.BraveVPNService;
/*import com.google.firebase.perf.FirebasePerformance;
import com.google.firebase.perf.metrics.HttpMetric;*/

import java.net.ProtocolException;
import java.util.Calendar;

import com.celzero.bravedns.net.dns.DnsPacket;
import com.celzero.bravedns.net.doh.Transaction;
import com.celzero.bravedns.net.doh.Transaction.Status;

import doh.Doh;
import doh.Token;
import intra.TCPSocketSummary;
import intra.UDPSocketSummary;
import split.RetryStats;

/**
 * This is a callback class that is passed to our go-tun2socks code.  Go calls this class's methods
 * when a socket has concluded, with performance metrics for that socket, and this class forwards
 * those metrics to Firebase.
 */
public class GoIntraListener implements tunnel.IntraListener {

  // UDP is often used for one-off messages and pings.  The relative overhead of reporting metrics
  // on these short messages would be large, so we only report metrics on sockets that transfer at
  // least this many bytes.
  private static final int UDP_THRESHOLD_BYTES = 10000;

  private final BraveVPNService vpnService;
  //private final AnalyticsWrapper analytics;
  GoIntraListener(BraveVPNService vpnService) {
    this.vpnService = vpnService;
    //analytics = AnalyticsWrapper.get(vpnService);
  }

  @Override
  public void onTCPSocketClosed(TCPSocketSummary summary) {
    /*analytics.logTCP(
        summary.getUploadBytes(),
        summary.getDownloadBytes(),
        summary.getServerPort(),
        summary.getSynack(),
        summary.getDuration());

    RetryStats retry = summary.getRetry();
    if (retry != null) {
      // Connection was eligible for split-retry.
      if (retry.getSplit() == 0) {
        // Split-retry was not attempted.
        return;
      }
      boolean success = summary.getDownloadBytes() > 0;
      analytics.logEarlyReset(
          retry.getBytes(),
          retry.getChunks(),
          retry.getTimeout(),
          retry.getSplit(),
          success);
    }*/
 }

  @Override
  public void onUDPSocketClosed(UDPSocketSummary summary) {
    /*long totalBytes = summary.getUploadBytes() + summary.getDownloadBytes();
    if (totalBytes < UDP_THRESHOLD_BYTES) {
      return;
    }
    analytics.logUDP(
        summary.getUploadBytes(),
        summary.getDownloadBytes(),
        summary.getDuration());*/
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

  // Wrapping HttpMetric into a doh.Token allows us to get paired query and response notifications
  // from Go without reverse-binding any Java APIs into Go.  Pairing these notifications is
  // required by the structure of the HttpMetric API (which does not have any other way to record
  // latency), and reverse binding is worth avoiding, especially because it's not compatible with
  // the Go module system (https://github.com/golang/go/issues/27234).
  /*private class Metric implements doh.Token {
    final HttpMetric metric;
    Metric(String url) {
      metric = FirebasePerformance.getInstance().newHttpMetric(url, "POST");
    }
  }*/

  @Override
  public Token onQuery(String url) {
    //Metric m = new Metric(url);
    //m.metric.start();
    return null;
  }

  private static int len(byte[] a) {
    return a != null ? a.length : 0;
  }

  @Override
  public void onResponse(Token token, doh.Summary summary) {
    /*if (summary.getHTTPStatus() != 0 && token != null) {
      // HTTP transaction completed.  Report performance metrics.
      Metric m = (Metric)token;
      m.metric.setRequestPayloadSize(len(summary.getQuery()));
      m.metric.setHttpResponseCode((int)summary.getHTTPStatus());
      m.metric.setResponsePayloadSize(len(summary.getResponse()));
      m.metric.stop();  // Finalizes the metric and queues it for upload.
    }*/

    final DnsPacket query;
    try {
      query = new DnsPacket(summary.getQuery());
    } catch (Exception e) {
      return;
    }
    long latencyMs = (long)(1000 * summary.getLatency());
    long nowMs = SystemClock.elapsedRealtime();
    long queryTimeMs = nowMs - latencyMs;
    Transaction transaction = new Transaction(query, queryTimeMs);
    transaction.response = summary.getResponse();
    transaction.responseTime = (long)(1000 * summary.getLatency());
    transaction.serverIp = summary.getServer();
    transaction.status = goStatusMap.get(summary.getStatus());
    transaction.responseCalendar = Calendar.getInstance();

    //vpnService.recordTransaction(transaction);
  }
}
