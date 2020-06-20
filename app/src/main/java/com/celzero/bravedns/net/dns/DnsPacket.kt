/*
package com.celzero.bravedns.net.dns

import okhttp3.Dns
import okhttp3.internal.and
import java.lang.reflect.Constructor
import java.net.InetAddress
import java.net.ProtocolException
import java.net.UnknownHostException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.util.*

class DnsPacket(data: ByteArray?) {

    private lateinit var data: ByteArray

    private val TYPE_A: Short = 1
    private val TYPE_AAAA: Short = 28

    private class DnsRecord {
        var name: String? = null
        var rtype: Short = 0
        var rclass: Short = 0
        var ttl = 0
        lateinit var data: ByteArray
    }

    private class DnsQuestion {
        var name: String? = null
        var qtype: Short = 0
        var qclass: Short = 0
    }

    private var id: Short = 0
    private var qr = false
    private var opcode: Byte = 0
    private var aa = false
    private var tc = false
    private var rd = false
    private var ra = false
    private var z: Byte = 0
    private var rcode: Byte = 0
    private lateinit var  question: Array<DnsQuestion>
    private lateinit var answer: ArrayList<DnsRecord>
    private lateinit var authority: ArrayList<DnsRecord>
    private lateinit var additional: ArrayList<DnsRecord>


    //TODO : Check why it has no calling method. It should have a calling method.
    //TODO : If not then find all the reference and do the appropriate changes
    @Throws(ProtocolException::class)
    fun Constructor(data: ByteArray?) {
        this.data = data!!
        val buffer = ByteBuffer.wrap(data)
        try {
            id = buffer.short
            // First flag byte: QR, Opcode (4 bits), AA, RD, RA
            val flags1 = buffer.get()
            val QR_BIT = 7
            val OPCODE_SIZE = 4
            val OPCODE_START = 3
            val AA_BIT = 2
            val TC_BIT = 1
            val RD_BIT = 0
            qr = getBit(flags1, QR_BIT)
            opcode = getBits(flags1, OPCODE_START, OPCODE_SIZE)
            aa = getBit(flags1, AA_BIT)
            tc = getBit(flags1, TC_BIT)
            rd = getBit(flags1, RD_BIT)

            // Second flag byte: RA, 0, 0, 0, Rcode
            val RA_BIT = 7
            val ZEROS_START = 4
            val ZEROS_SIZE = 3
            val RCODE_START = 0
            val RCODE_SIZE = 4
            val flags2 = buffer.get()
            ra = getBit(flags2, RA_BIT)
            z = getBits(flags2, ZEROS_START, ZEROS_SIZE)
            rcode = getBits(flags2, RCODE_START, RCODE_SIZE)
            val numQuestions = buffer.short
            val numAnswers = buffer.short
            val numAuthorities = buffer.short
            val numAdditional = buffer.short
            // var dnsQuestionArray = DnsQuestion()
            //var question : Array<DnsPacket.DnsQuestion> ?= null
            var question = ArrayList<DnsQuestion>(numQuestions.toInt())
            for (i in 0 until numQuestions) {
                question[i] = DnsQuestion()
                question[i].name = readName(buffer)
                question[i].qtype = buffer.short
                question[i].qclass = buffer.short
            }
            answer = readRecords(buffer, numAnswers)
            authority = readRecords(buffer, numAuthorities)
            additional = readRecords(buffer, numAdditional)
        } catch (e: BufferUnderflowException) {
            val p = ProtocolException("Packet too short")
            p.initCause(e)
            throw p
        }
    }



    //TODO: Find a way to change the throws exception
    @Throws(BufferUnderflowException::class, ProtocolException::class)
    private fun readName(buffer: ByteBuffer): String {
        val nameBuffer = StringBuilder()
        var labelLength = buffer.get()
        while (labelLength > 0) {
            val labelBytes = ByteArray(labelLength.toInt())
            buffer[labelBytes]
            val label = String(labelBytes)
            nameBuffer.append(label)
            nameBuffer.append(".")
            labelLength = buffer.get()
        }
        if (labelLength < 0) {
            // The last byte we read is now a barrier: we should not read past that byte again.
            val barrier = buffer.position() - 1
            // This is a compressed label, starting with a 14-bit backwards offset consisting of
            // the lower 6 bits from the first byte and all 8 from the second.
            val OFFSET_HIGH_BITS_START = 0
            val OFFSET_HIGH_BITS_SIZE = 6
            val offsetHighBits = getBits(
                labelLength, OFFSET_HIGH_BITS_START,
                OFFSET_HIGH_BITS_SIZE
            )
            val offsetLowBits = buffer.get()
            val offset: Int = (offsetHighBits.toInt() shl 8) or (offsetLowBits and 0xFF)
            val data = buffer.array()
            // Only allow references that terminate before the barrier, to avoid stack
            // overflow attacks.
            val delta = barrier - offset
            if (offset < 0 || delta < 0) {
                throw ProtocolException("Bad compressed name")
            }
            val referenceBuffer = ByteBuffer.wrap(data, offset, delta)
            val suffix = readName(referenceBuffer)
            nameBuffer.append(suffix)
        }
        return nameBuffer.toString()
    }

    @Throws(BufferUnderflowException::class, ProtocolException::class)
    private fun readRecords(
        src: ByteBuffer,
        numRecords: Short
    ): ArrayList<DnsRecord> {
        val dest = ArrayList<DnsRecord>(numRecords.toInt())
        for (i in dest.indices) {
            val r =
                DnsRecord()
            r.name = readName(src)
            r.rtype = src.short
            r.rclass = src.short
            r.ttl = src.int
            r.data = ByteArray(src.short.toInt())
            src[r.data]
            dest[i] = r
        }
        return dest
    }

    private fun getBit(src: Byte, index: Int): Boolean {
        return src and (1 shl index) != 0
    }

    private fun getBits(src: Byte, start: Int, size: Int): Byte {
        val mask = (1 shl size) - 1
        return ((src.toInt() ushr start) and mask) as Byte
    }


    //TODO : Check why
    fun getId(): Short {
        return id
    }

    //TODO : Check why
    fun isNormalQuery(): Boolean {
        return !qr && question.size > 0 && z.toInt() == 0 && authority.size == 0 && answer.size == 0
    }

    //TODO : Check why
    fun isResponse(): Boolean {
        return qr
    }

    fun getQueryName(): String? {
        return if (question.size > 0) {
            question[0].name
        } else null
    }

    fun getQueryType(): Short {
        return if (question.size > 0) {
            question[0].qtype
        } else 0
    }

    //TODO : Check why
    fun getResponseAddresses(): List<InetAddress>? {
        val addresses: MutableList<InetAddress> =
            ArrayList()
        for (src in arrayOf(
            answer,
            authority
        )) {
            for (r in src) {
                if (r.rtype == TYPE_A || r.rtype == TYPE_AAAA) {
                    try {
                        addresses.add(InetAddress.getByAddress(r.data))
                    } catch (e: UnknownHostException) {
                    }
                }
            }
        }
        return addresses
    }

}
*/
