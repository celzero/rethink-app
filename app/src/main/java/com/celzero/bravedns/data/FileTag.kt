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
package com.celzero.bravedns.data

import com.google.gson.Gson
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import java.io.Serializable
import java.lang.reflect.Type

// there are two different formats for the FileTag json
// below is the sample legacy format
/*"value":0,
"uname":"0",
"vname":"Prevent bypass",
"group":"ParentalControl",
"subg":"BypassMethods",
"url":"https://raw.githubusercontent.com/nextdns/metadata/master/parentalcontrol/bypass-methods",
"pack":[
"vpn & proxies"
],
"show":0,
"entries":635*/

// below is the sample new format
/*"value":171,
"uname":"171",
"vname":"1Hosts (Xtra)",
"group":"privacy",
"subg":"",
"url":"https://raw.githubusercontent.com/badmojr/1Hosts/master/Xtra/domains.wildcards",
"format":"domains",
"pack":[
"extremeprivacy"
],
"level":[
2
],
"entries":296372,
"discards":0*/

// data class to handle filetags for both local and remote blocklist (filetag.json)
// do not use as key in map or set, as some fields are mutable
data class FileTag(
    val value: Int,
    val uname: String,
    val vname: String,
    var group: String,
    var subg: String,
    var urls: List<String>,
    val show: Int,
    var pack: List<String> = arrayListOf(),
    var level: List<Int>? = arrayListOf(),
    val entries: Int,
    var simpleTagId: Int = INVALID_SIMPLE_TAG_ID,
    var isSelected: Boolean = false
) : Serializable

private const val INVALID_SIMPLE_TAG_ID = -1

// The default json/gson serialization and deserialization will not work here,
// because the ["url"] object in filetag.json can be in either format: [string
// or JsonArray] custom deserializer will handle the ["url"] which will verify
// the object type and converts accordingly.
// ref: https://stackoverflow.com/a/28325108
class FileTagDeserializer : JsonDeserializer<FileTag?> {
    @Throws(JsonParseException::class)
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): FileTag {
        val options: FileTag = Gson().fromJson(json, FileTag::class.java)
        val jsonObject = json.asJsonObject
        // attribute == "url", in filetag.json
        if (jsonObject.has("url")) {
            val elem = jsonObject["url"]
            if (elem != null && !elem.isJsonNull) {
                if (elem.isJsonArray) {
                    // handle for obj type JSONArray
                    val values: List<String> =
                        Gson().fromJson(elem, object : TypeToken<ArrayList<String?>?>() {}.type)
                    options.urls = values
                } else {
                    // else consider the object as String
                    val valuesString = elem.asString
                    options.urls = mutableListOf(valuesString)
                }
            }
        }
        return options
    }
}
