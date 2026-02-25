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

import io.mapsmessaging.logging.Category;
import io.mapsmessaging.logging.LEVEL;
import io.mapsmessaging.logging.LogMessage;

/**
 * Structured log templates used by the Redis extension.
 */
public enum RedisLogMessages implements LogMessage {

  INITIALISE_REDIS_ENDPOINT(LEVEL.INFO, REDIS_CATEGORY.PROTOCOL, "Initialising redis endpoint on {}"),
  REDIS_ENDPOINT_INITIALIZED(LEVEL.INFO, REDIS_CATEGORY.PROTOCOL, "Redis endpoint initialised for {}"),
  REDIS_ENDPOINT_CLOSED(LEVEL.INFO, REDIS_CATEGORY.PROTOCOL, "Redis endpoint closed"),
  REDIS_ENDPOINT_CLOSE_ERROR(LEVEL.ERROR, REDIS_CATEGORY.PROTOCOL, "Redis close error"),
  REDIS_SEND_MESSAGE(LEVEL.DEBUG, REDIS_CATEGORY.PROTOCOL, "Redis sent message to {} via {}"),
  REDIS_FAILED_TO_SEND_MESSAGE(LEVEL.ERROR, REDIS_CATEGORY.PROTOCOL, "Failed to send message to {}"),
  REDIS_SUBSCRIBE_LOCAL_SUCCESS(LEVEL.INFO, REDIS_CATEGORY.PROTOCOL, "Registered push link from {} to {}"),
  REDIS_SUBSCRIBE_REMOTE_SUCCESS(LEVEL.INFO, REDIS_CATEGORY.PROTOCOL, "Registered pull link from {} to {}"),
  REDIS_FAILED_TO_PROCESS_INCOMING_EVENT(LEVEL.ERROR, REDIS_CATEGORY.PROTOCOL, "Failed to process incoming message from {}"),
  REDIS_CONSUMER_ERROR(LEVEL.ERROR, REDIS_CATEGORY.PROTOCOL, "Redis consumer poll failed for {}"),
  REDIS_CONFIGURATION_WARNING(LEVEL.WARN, REDIS_CATEGORY.PROTOCOL, "Redis configuration warning: {}"),
  ;

  private final String message;
  private final LEVEL level;
  private final Category category;
  private final int parameterCount;

  /**
   * Creates a new enum-backed log message with precomputed placeholder count.
   *
   * @param level log level
   * @param category log category
   * @param message template text that may include {@code {}}
   */
  RedisLogMessages(LEVEL level, Category category, String message) {
    this.message = message;
    this.level = level;
    this.category = category;
    int location = message.indexOf("{}");
    int count = 0;
    while (location != -1) {
      count++;
      location = message.indexOf("{}", location + 2);
    }
    this.parameterCount = count;
  }

  @Override
  public String getMessage() {
    return message;
  }

  @Override
  public LEVEL getLevel() {
    return level;
  }

  @Override
  public Category getCategory() {
    return category;
  }

  @Override
  public int getParameterCount() {
    return parameterCount;
  }

  /**
   * Category constants for Redis protocol logging.
   */
  public enum REDIS_CATEGORY implements Category {
    PROTOCOL("Protocol");

    private final String description;

    @Override
    public String getDivision() {
      return "Inter-Protocol";
    }

    @Override
    public String getDescription() {
      return description;
    }

    REDIS_CATEGORY(String description) {
      this.description = description;
    }
  }
}
