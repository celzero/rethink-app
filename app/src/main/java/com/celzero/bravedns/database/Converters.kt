/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.database

import androidx.room.TypeConverter
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

class Converters {

    @TypeConverter
    fun stringToList(string: String?): List<String> {
        val listType: Type = object : TypeToken<List<String>>() {}.type
        return Gson().fromJson(string, listType)
    }

    @TypeConverter
    fun intToList(string: String?): List<Int> {
        if (string.isNullOrEmpty()) return arrayListOf()

        val listType: Type = object : TypeToken<List<Int>?>() {}.type
        return Gson().fromJson(string, listType) ?: arrayListOf()
    }

    @TypeConverter
    fun listToString(set: List<String>?): String {
        if (set == null) return ""

        return Gson().toJson(set)
    }

    @TypeConverter
    fun listToInt(set: List<Int>?): String {
        if (set == null) return ""

        return Gson().toJson(set)
    }
}
