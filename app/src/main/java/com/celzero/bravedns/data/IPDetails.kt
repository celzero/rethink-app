package com.celzero.bravedns.data

import java.io.Serializable

data class IPDetails(val uid : Int, val sourceIP : String, val sourcePort : String, val destIP : String, val destPort: String,
                     val timeStamp : Long, val isBlocked : Boolean, val protocol : Int) : Serializable