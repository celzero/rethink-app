/*
 * Copyright 2020 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.util

import java.util.Arrays
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

/** Estimate percentiles without storing actual samples. */
class P2QuantileEstimation(probability: Double) {

    // details: https://aakinshin.net/posts/p2-quantile-estimator/
    // orig impl: github.com/AndreyAkinshin/perfolizer P2QuantileEstimator.cs

    // median percentile
    private var p = 0.5

    // total samples, typically 5; higher u improves accuracy for
    // lower percentiles (p50) at the expense of computational cost;
    // for higher percentiles (p90+), even u as low as 5 works fine.
    private val u = 31
    private val mid = floor(u / 2.0).roundToInt()

    private val n = IntArray(u) // marker positions
    private val ns = DoubleArray(u) // desired marker positions
    private val dns = DoubleArray(u)
    private val q = DoubleArray(u) // marker heights

    private var count = 0 // total sampled so far

    init {
        p = probability
    }

    // https://www.cse.wustl.edu/~jain/papers/ftp/psqr.pdf (p. 1078)
    fun addValue(x: Double) {
        if (count < u) {

            q[count] = x
            count += 1

            if (count == u) {
                Arrays.sort(q)

                val t = u - 1 // 0 index
                for (i in 0..t) {
                    n[i] = i
                }

                // divide p into mid no of equal segments
                // p => 0.5, u = 11, t = 10, mid = 5; pmid => 0.1
                val pmid = p / mid
                for (i in 0..mid) {
                    // assign i-th portion of pmid to dns[i]
                    // [0] => .0, [1] => .1, [2] => .2,
                    // [3] => .3, [4] => .4, [5] => .5
                    dns[i] = pmid * i
                    // assign t-th portion of dns[i] to ns[i]
                    // [0] => .0, [1] => 1, [2] => 2,
                    // [3] => 3, [4] => 4, [5] => 5
                    ns[i] = dns[i] * t
                }

                val rem = t - mid // the rest
                val s = 1 - p // left-over probability
                // divide q into rem no of equal segments
                // q => 0.5, u = 10, mid = 5, rem = 5; smid => 0.5
                val smid = s / rem
                for (i in 1..rem) {
                    // assign i-th portion of smid to dns[mid+i]
                    // [mid+1] => .6, [mid+2] => .7, [mid+3] => .8,
                    // [mid+4] => .9, [mid+5] => 1
                    dns[mid + i] = (smid * i) + p
                    // assign t-th portion of dns[mid+i] to ns[mid+i]
                    // [mid+1] => 6, [mid+2] => 7, [mid+3] => 8,
                    // [mid+4] => 9, [mid+5] => 10
                    ns[mid + i] = dns[mid + i] * t
                }
            }

            return
        }

        var k: Int
        if (x < q[0]) {
            q[0] = x // update min
            k = 0
        } else if (x > q[u - 1]) {
            q[u - 1] = x // update max
            k = u - 2
        } else {
            k = u - 2
            for (i in 1..u - 2) {
                if (x < q[i]) {
                    k = i - 1
                    break
                }
            }
        }

        for (i in (k + 1) until u) n[i]++

        for (i in 0 until u) ns[i] += dns[i]

        for (i in 1 until u - 1) { // update intermediatories
            val d = ns[i] - n[i]

            if (d >= 1 && n[i + 1] - n[i] > 1 || d <= -1 && n[i - 1] - n[i] < -1) {
                val dInt = sign(d).toInt()
                val qs = parabolic(i, dInt.toDouble())
                if (q[i - 1] < qs && qs < q[i + 1]) {
                    q[i] = qs
                } else {
                    q[i] = linear(i, dInt)
                }
                n[i] += dInt
            }
        }

        count++
    }

    private fun parabolic(i: Int, d: Double): Double {
        return q[i] +
            (d / (n[i + 1] - n[i - 1])) *
                (((n[i] - n[i - 1] + d) * (q[i + 1] - q[i]) / (n[i + 1] - n[i])) +
                    ((n[i + 1] - n[i] - d) * (q[i] - q[i - 1]) / (n[i] - n[i - 1])))
    }

    private fun linear(i: Int, d: Int): Double {
        return q[i] + (d * (q[i + d] - q[i]) / (n[i + d] - n[i]))
    }

    fun getQuantile(): Long {
        val c = count

        if (c > u) {
            return q[mid].toLong()
        }

        Arrays.sort(q, 0, c)
        val index = ((c - 1) * p).roundToInt()
        return q[index].toLong()
    }
}
