/*
Copyright 2018 Jigsaw Operations LLC

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

package com.celzero.bravedns.net.dns;

import java.net.InetAddress;
import java.net.ProtocolException;
import java.net.UnknownHostException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;


/**
 * A representation of a DNS query or response packet.  This class provides read-only access to
 * the relevant contents of a DNS query packet.
 */

public class DnsPacket {

  private final byte[] data;

  private static final short TYPE_A = 1;
  private static final short TYPE_AAAA = 28;

  private static class DnsQuestion {

    String name;
    short qtype;
    short qclass;
  }

  private static class DnsRecord {

    String name;
    short rtype;
    short rclass;
    int ttl;
    byte[] data;
  }

  private final short id;
  private final boolean qr;
  private final byte opcode;
  private final boolean aa;
  private final boolean tc;
  private final boolean rd;
  private final boolean ra;
  private final byte z;
  private final byte rcode;
  private final DnsQuestion[] question;
  private final DnsRecord[] answer;
  private final DnsRecord[] authority;
  private final DnsRecord[] additional;

  private static String readName(ByteBuffer buffer) throws BufferUnderflowException,
      ProtocolException {
    StringBuilder nameBuffer = new StringBuilder();
    byte labelLength = buffer.get();
    while (labelLength > 0) {
      byte[] labelBytes = new byte[labelLength];
      buffer.get(labelBytes);
      String label = new String(labelBytes);
      nameBuffer.append(label);
      nameBuffer.append(".");

      labelLength = buffer.get();
    }
    if (labelLength < 0) {
      // The last byte we read is now a barrier: we should not read past that byte again.
      final int barrier = buffer.position() - 1;
      // This is a compressed label, starting with a 14-bit backwards offset consisting of
      // the lower 6 bits from the first byte and all 8 from the second.
      final int OFFSET_HIGH_BITS_START = 0;
      final int OFFSET_HIGH_BITS_SIZE = 6;
      byte offsetHighBits = getBits(labelLength, OFFSET_HIGH_BITS_START,
          OFFSET_HIGH_BITS_SIZE);
      byte offsetLowBits = buffer.get();
      int offset = (offsetHighBits << 8) | (offsetLowBits & 0xFF);
      byte[] data = buffer.array();
      // Only allow references that terminate before the barrier, to avoid stack
      // overflow attacks.
      int delta = barrier - offset;
      if (offset < 0 || delta < 0) {
        throw new ProtocolException("Bad compressed name");
      }
      ByteBuffer referenceBuffer = ByteBuffer.wrap(data, offset, delta);
      String suffix = readName(referenceBuffer);
      nameBuffer.append(suffix);
    }
    return nameBuffer.toString();

  }

  private static DnsRecord[] readRecords(ByteBuffer src, short numRecords)
      throws BufferUnderflowException, ProtocolException {
    DnsRecord[] dest = new DnsRecord[numRecords];
    for (int i = 0; i < dest.length; ++i) {
      DnsRecord r = new DnsRecord();
      r.name = readName(src);
      r.rtype = src.getShort();
      r.rclass = src.getShort();
      r.ttl = src.getInt();
      r.data = new byte[src.getShort()];
      src.get(r.data);
      dest[i] = r;
    }
    return dest;
  }

  private static boolean getBit(byte src, int index) {
    return (src & (1 << index)) != 0;
  }

  private static byte getBits(byte src, int start, int size) {
    int mask = (1 << size) - 1;
    return (byte) ((src >>> start) & mask);
  }

  public DnsPacket(byte[] data) throws ProtocolException {
    this.data = data;
    ByteBuffer buffer = ByteBuffer.wrap(data);
    try {
      id = buffer.getShort();
      // First flag byte: QR, Opcode (4 bits), AA, RD, RA
      byte flags1 = buffer.get();
      final int QR_BIT = 7;
      final int OPCODE_SIZE = 4;
      final int OPCODE_START = 3;
      final int AA_BIT = 2;
      final int TC_BIT = 1;
      final int RD_BIT = 0;
      qr = getBit(flags1, QR_BIT);
      opcode = getBits(flags1, OPCODE_START, OPCODE_SIZE);
      aa = getBit(flags1, AA_BIT);
      tc = getBit(flags1, TC_BIT);
      rd = getBit(flags1, RD_BIT);

      // Second flag byte: RA, 0, 0, 0, Rcode
      final int RA_BIT = 7;
      final int ZEROS_START = 4;
      final int ZEROS_SIZE = 3;
      final int RCODE_START = 0;
      final int RCODE_SIZE = 4;
      byte flags2 = buffer.get();
      ra = getBit(flags2, RA_BIT);
      z = getBits(flags2, ZEROS_START, ZEROS_SIZE);
      rcode = getBits(flags2, RCODE_START, RCODE_SIZE);

      short numQuestions = buffer.getShort();
      short numAnswers = buffer.getShort();
      short numAuthorities = buffer.getShort();
      short numAdditional = buffer.getShort();

      question = new DnsQuestion[numQuestions];
      for (short i = 0; i < numQuestions; ++i) {
        question[i] = new DnsQuestion();
        question[i].name = readName(buffer);
        question[i].qtype = buffer.getShort();
        question[i].qclass = buffer.getShort();
      }
      answer = readRecords(buffer, numAnswers);
      authority = readRecords(buffer, numAuthorities);
      additional = readRecords(buffer, numAdditional);
    } catch (BufferUnderflowException e) {
      ProtocolException p = new ProtocolException("Packet too short");
      p.initCause(e);
      throw p;
    }
  }

  public short getId() {
    return id;
  }

  public boolean isNormalQuery() {
    return !qr && question.length > 0 && z == 0 && authority.length == 0 && answer.length == 0;
  }

  public boolean isResponse() {
    return qr;
  }

  public String getQueryName() {
    if (question.length > 0) {
      return question[0].name;
    }
    return null;
  }

  public short getQueryType() {
    if (question.length > 0) {
      return question[0].qtype;
    }
    return 0;
  }

  public List<InetAddress> getResponseAddresses() {
    List<InetAddress> addresses = new ArrayList<>();
    for (DnsRecord[] src : new DnsRecord[][]{answer, authority}) {
      for (DnsRecord r : src) {
        if (r.rtype == TYPE_A || r.rtype == TYPE_AAAA) {
          try {
            addresses.add(InetAddress.getByAddress(r.data));
          } catch (UnknownHostException e) {
          }
        }
      }
    }
    return addresses;
  }
}