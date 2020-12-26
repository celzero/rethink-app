/*
 * Copyright 2020 RethinkDNS and its authors

 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at

 * https://www.apache.org/licenses/LICENSE-2.0

 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.util

import java.util.*
import kotlin.math.roundToInt
import kotlin.math.sign

// P² quantile estimator: estimating the median without storing values.
// github.com/AndreyAkinshin/perfolizer/blob/master/src/Perfolizer/Perfolizer/Mathematics/QuantileEstimators/P2QuantileEstimator.cs
// https://aakinshin.net/posts/p2-quantile-estimator/
class P2QuantileEstimation(probability: Double) {
    private var p = 0.5

    private val n = IntArray(5) // marker positions
    private val ns = DoubleArray(5) // desired marker positions
    private val dns = DoubleArray(5)
    private val q = DoubleArray(5) // marker heights

    private var count = 0

    init {
        p = probability
    }

    fun addValue(x: Double) {
        if (count < 5) {

            q[count++] = x

            if (count == 5) {
                Arrays.sort(q)

                for (i in 0..4) n[i] = i

                ns[0] = 0.0
                ns[1] = 2 * p
                ns[2] = 4 * p
                ns[3] = 2 + 2 * p
                ns[4] = 4.0

                dns[0] = 0.0
                dns[1] = p / 2
                dns[2] = p
                dns[3] = (1 + p) / 2
                dns[4] = 1.0
            }

            return
        }
        
        val k: Int
        if (x < q[0]) {
            q[0] = x
            k = 0
        } else if (x < q[1]) {
            k = 0
        } else if (x < q[2]) {
            k = 1
        } else if (x < q[3]) {
            k = 2
        } else if (x < q[4]) {
            k = 3
        } else {
            q[4] = x
            k = 3
        }

        for (i in (k + 1)..4) n[i]++

        for (i in 0..4) ns[i] += dns[i]

        for (i in 1..3) {
            val d = ns[i] - n[i]

            if (d >= 1 && n[i + 1] - n[i] > 1 ||
                        d <= -1 && n[i - 1] - n[i] < -1) {
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
        return q[i] + d / (n[i + 1] - n[i - 1]) * (
                    (n[i] - n[i - 1] + d) * (q[i + 1] - q[i]) / (n[i + 1] - n[i]) +
                    (n[i + 1] - n[i] - d) * (q[i] - q[i - 1]) / (n[i] - n[i - 1])
                )
    }

    private fun linear(i: Int, d: Int): Double {
        return q[i] + d * (q[i + d] - q[i]) / (n[i + d] - n[i])
    }

    fun getQuantile(): Double {
        if (count > 5) {
            return q[2]
        }
 
        Arrays.sort(q, 0, count)
        val index = ((count - 1) * p).roundToInt()
        return q[index]
    }

}
