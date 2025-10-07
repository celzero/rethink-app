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
package com.rethinkdns.retrixed.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "IpInfo")
class IpInfo {
    @PrimaryKey var ip: String = ""
    var asn: String = ""
    @SerializedName("as_name")
    var asName: String = ""
    @SerializedName("as_domain")
    var asDomain: String = ""
    @SerializedName("country_code")
    var countryCode: String = ""
    var country: String = ""
    @SerializedName("continent_code")
    var continentCode: String = ""
    var continent: String = ""
    var createdTs: Long = 0L
}
