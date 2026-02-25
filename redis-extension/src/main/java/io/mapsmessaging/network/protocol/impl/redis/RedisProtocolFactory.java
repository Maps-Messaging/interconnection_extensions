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

import io.mapsmessaging.dto.rest.config.protocol.impl.ExtensionConfigDTO;
import io.mapsmessaging.network.io.EndPoint;
import io.mapsmessaging.network.io.Packet;
import io.mapsmessaging.network.protocol.Protocol;
import io.mapsmessaging.network.protocol.ProtocolImplFactory;
import io.mapsmessaging.network.protocol.detection.NoOpDetection;
import io.mapsmessaging.network.protocol.impl.extension.ExtensionEndPoint;
import io.mapsmessaging.network.protocol.impl.extension.ExtensionProtocol;

import java.io.IOException;

/**
 * Factory that registers and instantiates the Redis extension protocol.
 */
public class RedisProtocolFactory extends ProtocolImplFactory {

  /**
   * Builds the Redis protocol factory metadata used by plugin discovery.
   */
  public RedisProtocolFactory() {
    super("redis", "Provides a connection to Redis Pub/Sub and Streams", new NoOpDetection());
  }

  /**
   * Creates a connected {@link ExtensionProtocol} wrapper around {@link RedisProtocol}.
   *
   * @param endPoint end point that contains extension configuration
   * @param sessionId negotiated session id
   * @param username authentication username
   * @param password authentication password
   * @return connected protocol instance
   * @throws IOException if protocol connection fails
   */
  @Override
  public Protocol connect(EndPoint endPoint, String sessionId, String username, String password) throws IOException {
    ExtensionConfigDTO protocolConfigDTO = (ExtensionConfigDTO) ((ExtensionEndPoint) endPoint).config();
    Protocol protocol = new ExtensionProtocol(endPoint, new RedisProtocol(endPoint, protocolConfigDTO));
    protocol.connect(sessionId, username, password);
    return protocol;
  }

  /**
   * No-op because this extension does not accept raw inbound socket packets.
   *
   * @param endPoint connection endpoint
   * @param packet inbound packet
   */
  @Override
  public void create(EndPoint endPoint, Packet packet) {
    // This extension does not accept inbound Redis client sockets.
  }

  /**
   * Returns an empty transport hint because Redis uses its own client connection model.
   *
   * @return empty transport type
   */
  @Override
  public String getTransportType() {
    return "";
  }
}
