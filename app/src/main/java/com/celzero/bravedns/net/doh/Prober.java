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
package com.celzero.bravedns.net.doh;

/**
 * A prober can perform asynchronous checks to determine whether a DOH server is working.
 */

//TODO : Understand this class ????
public abstract class Prober {

    protected static final byte[] QUERY_DATA = {
            0, 0,  // [0-1]   query ID
            1, 0,  // [2-3]   flags, RD=1
            0, 1,  // [4-5]   QDCOUNT (number of queries) = 1
            0, 0,  // [6-7]   ANCOUNT (number of answers) = 0
            0, 0,  // [8-9]   NSCOUNT (number of authoritative answers) = 0
            0, 0,  // [10-11] ARCOUNT (number of additional records) = 0
            // Start of first query
            7, 'y', 'o', 'u', 't', 'u', 'b', 'e',
            3, 'c', 'o', 'm',
            0,  // null terminator of FQDN (DNS root)
            0, 1,  // QTYPE = A
            0, 1   // QCLASS = IN (Internet)
    };

    /**
     * Called to execute the probe on a new thread.
     *
     * @param url      The DOH server URL to probe.
     * @param callback How to report the probe results
     */
    public abstract void probe(String url, Callback callback);

    public interface Callback {
        void onCompleted(boolean succeeded);
    }
}
