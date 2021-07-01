/*
Copyright 2020 RethinkDNS and its authors

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
package com.celzero.bravedns.data

import java.io.Serializable

data class IPDetails(val uid: Int, val sourceIP: String, val sourcePort: Int, val destIP: String,
                     val destPort: Int, val timestamp: Long, var isBlocked: Boolean,
                     var blockedByRule: String, val protocol: Int) : Serializable