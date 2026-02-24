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

package io.mapsmessaging.network.protocol.impl.kafka;

import io.mapsmessaging.logging.Category;
import io.mapsmessaging.logging.LEVEL;
import io.mapsmessaging.logging.LogMessage;

public enum KafkaLogMessages implements LogMessage {

  INITIALISE_KAFKA_ENDPOINT(LEVEL.INFO, KAFKA_CATEGORY.PROTOCOL, "Initialising kafka endpoint on {}"),
  KAFKA_ENDPOINT_INITIALIZED(LEVEL.INFO, KAFKA_CATEGORY.PROTOCOL, "Kafka endpoint initialised for {}"),
  KAFKA_ENDPOINT_CLOSED(LEVEL.INFO, KAFKA_CATEGORY.PROTOCOL, "Kafka endpoint closed"),
  KAFKA_ENDPOINT_CLOSE_ERROR(LEVEL.ERROR, KAFKA_CATEGORY.PROTOCOL, "Kafka close error"),
  KAFKA_SEND_MESSAGE(LEVEL.DEBUG, KAFKA_CATEGORY.PROTOCOL, "Kafka send message to {}"),
  KAFKA_FAILED_TO_SEND_MESSAGE(LEVEL.ERROR, KAFKA_CATEGORY.PROTOCOL, "Failed to send message to {}"),
  KAFKA_SUBSCRIBE_LOCAL_SUCCESS(LEVEL.INFO, KAFKA_CATEGORY.PROTOCOL, "Registered push link from {} to {}"),
  KAFKA_SUBSCRIBE_REMOTE_SUCCESS(LEVEL.INFO, KAFKA_CATEGORY.PROTOCOL, "Registered pull link from {} to {}"),
  KAFKA_FAILED_TO_PROCESS_INCOMING_EVENT(LEVEL.ERROR, KAFKA_CATEGORY.PROTOCOL, "Failed to process incoming message from {}"),
  KAFKA_CONSUMER_ERROR(LEVEL.ERROR, KAFKA_CATEGORY.PROTOCOL, "Kafka consumer poll failed for {}"),
  KAFKA_CONFIGURATION_WARNING(LEVEL.WARN, KAFKA_CATEGORY.PROTOCOL, "Kafka configuration warning: {}"),
  KAFKA_ROUTING_RULE_APPLIED(LEVEL.DEBUG, KAFKA_CATEGORY.PROTOCOL, "Applied routing rule {} for {}"),
  KAFKA_LOOP_SAME_TOPIC_DROPPED(LEVEL.WARN, KAFKA_CATEGORY.PROTOCOL, "Dropped outbound message for {} because source topic {} matches target topic"),
  KAFKA_LOOP_HOP_LIMIT_DROPPED(LEVEL.WARN, KAFKA_CATEGORY.PROTOCOL, "Dropped outbound message for {} because hop count {} reached limit {}"),
  ;

  private final String message;
  private final LEVEL level;
  private final Category category;
  private final int parameterCount;

  KafkaLogMessages(LEVEL level, Category category, String message) {
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

  public enum KAFKA_CATEGORY implements Category {
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

    KAFKA_CATEGORY(String description) {
      this.description = description;
    }
  }
}
