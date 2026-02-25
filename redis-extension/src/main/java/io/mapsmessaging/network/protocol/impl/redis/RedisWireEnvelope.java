/*
 *  Copyright [ 2020 - 2024 ] Matthew Buckton
 *  Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 *
 *  Licensed under the Apache License, Version 2.0 with the Commons Clause
 *  (the "License"); you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *      https://commonsclause.com/
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package io.mapsmessaging.network.protocol.impl.redis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binary wire envelope used to preserve payload and header bytes over Redis.
 */
final class RedisWireEnvelope {

  private static final int MAGIC = 0x4D4D5231;

  private final byte[] payload;
  private final Map<String, byte[]> headers;

  /**
   * Creates an immutable envelope view for payload and headers.
   *
   * @param payload message payload bytes
   * @param headers message headers as raw bytes
   */
  private RedisWireEnvelope(byte[] payload, Map<String, byte[]> headers) {
    this.payload = payload == null ? new byte[0] : payload;
    this.headers = headers == null ? new LinkedHashMap<>() : headers;
  }

  static RedisWireEnvelope of(byte[] payload, Map<String, byte[]> headers) {
    return new RedisWireEnvelope(payload, headers);
  }

  /**
   * Returns the payload bytes.
   *
   * @return payload bytes, never {@code null}
   */
  byte[] payload() {
    return payload;
  }

  /**
   * Returns header bytes keyed by header name.
   *
   * @return header map, never {@code null}
   */
  Map<String, byte[]> headers() {
    return headers;
  }

  /**
   * Encodes this envelope to a compact binary format.
   *
   * @return encoded bytes
   */
  byte[] encode() {
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      DataOutputStream out = new DataOutputStream(baos);
      out.writeInt(MAGIC);
      out.writeInt(headers.size());
      for (Map.Entry<String, byte[]> entry : headers.entrySet()) {
        writeBytes(out, entry.getKey().getBytes(StandardCharsets.UTF_8));
        writeBytes(out, entry.getValue() == null ? new byte[0] : entry.getValue());
      }
      writeBytes(out, payload);
      out.flush();
      return baos.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Failed to encode redis wire envelope", e);
    }
  }

  static RedisWireEnvelope decode(byte[] bytes) {
    if (bytes == null || bytes.length == 0) {
      return new RedisWireEnvelope(new byte[0], new LinkedHashMap<>());
    }

    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
      int magic = in.readInt();
      if (magic != MAGIC) {
        throw new IllegalArgumentException("Invalid redis envelope magic");
      }

      int headerCount = in.readInt();
      if (headerCount < 0 || headerCount > 65536) {
        throw new IllegalArgumentException("Invalid header count " + headerCount);
      }

      Map<String, byte[]> headers = new LinkedHashMap<>();
      for (int i = 0; i < headerCount; i++) {
        byte[] keyBytes = readBytes(in);
        byte[] valueBytes = readBytes(in);
        headers.put(new String(keyBytes, StandardCharsets.UTF_8), valueBytes);
      }

      byte[] payload = readBytes(in);
      return new RedisWireEnvelope(payload, headers);
    } catch (IOException e) {
      throw new IllegalArgumentException("Failed to decode redis wire envelope", e);
    }
  }

  /**
   * Writes a length-prefixed byte array to the output stream.
   *
   * @param out output stream
   * @param value byte array to write
   * @throws IOException if write fails
   */
  private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
    out.writeInt(value.length);
    out.write(value);
  }

  /**
   * Reads a length-prefixed byte array from the input stream with safety limits.
   *
   * @param in input stream
   * @return decoded byte array
   * @throws IOException if read fails
   */
  private static byte[] readBytes(DataInputStream in) throws IOException {
    int len = in.readInt();
    if (len < 0 || len > (32 * 1024 * 1024)) {
      throw new IllegalArgumentException("Invalid payload length " + len);
    }
    byte[] value = new byte[len];
    in.readFully(value);
    return value;
  }
}
