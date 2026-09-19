/*
 * Copyright 2025 RethinkDNS and its authors
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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldMapPathsTest {

    @Test
    fun `dataset is populated with unique ISO alpha-2 keys`() {
        assertFalse(WorldMapPaths.PATHS.isEmpty())
        assertEquals(WorldMapPaths.PATHS.size, WorldMapPaths.PATHS.keys.toSet().size)
        assertTrue(WorldMapPaths.PATHS.keys.all { it.length == 2 && it == it.uppercase() })
    }

    @Test
    fun `major countries are present`() {
        listOf("US", "DE", "IN", "CN", "GB", "BR", "JP").forEach {
            assertNotNull("missing country: $it", WorldMapPaths.PATHS[it])
        }
    }

    @Test
    fun `unknown marker XX is not drawn as a country`() {
        assertFalse(WorldMapPaths.PATHS.containsKey("XX"))
    }

    @Test
    fun `every ring parses into a valid closed polygon`() {
        WorldMapPaths.PATHS.forEach { (code, encoded) ->
            val rings = encoded.split("|")
            assertTrue("country $code has no rings", rings.isNotEmpty())
            rings.forEach { ring ->
                val points = ring.trim().split(" ")
                assertTrue("country $code ring too small", points.size >= 3)
                points.forEach { pt ->
                    val xy = pt.split(",")
                    assertEquals("country $code bad point '$pt'", 2, xy.size)
                    xy.forEach { v -> Integer.parseInt(v) } // throws if malformed
                }
            }
        }
    }

    @Test
    fun `encoded coordinates stay within the quantized map space`() {
        WorldMapPaths.PATHS.values.forEach { encoded ->
            encoded.split("|").forEach { ring ->
                ring.trim().split(" ").forEach { pt ->
                    val (x, y) = pt.split(",").map { Integer.parseInt(it) }
                    assertTrue(x in 0..WorldMapPaths.MAP_WIDTH * WorldMapPaths.QUANT_SCALE)
                    assertTrue(y in 0..WorldMapPaths.MAP_HEIGHT * WorldMapPaths.QUANT_SCALE)
                }
            }
        }
    }
}
