/*
 *  Copyright [ 2020 - 2024 ] Matthew Buckton
 *  Copyright [ 2024 - 2025 ] MapsMessaging B.V.
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

package io.mapsmessaging.network.protocol.impl.aws_sns;

import io.mapsmessaging.logging.Category;
import io.mapsmessaging.logging.LEVEL;
import io.mapsmessaging.logging.LogMessage;
import lombok.Getter;

@Getter
public enum SnsLogMessages implements LogMessage {

  SNS_INITIALIZED(LEVEL.INFO, SNS_CATEGORY.PROTOCOL, "SNS connection established to {}"),
  SNS_INITIALIZE_ERROR(LEVEL.ERROR, SNS_CATEGORY.PROTOCOL, "Error initializing SNS"),
  SNS_CLOSE_ERROR(LEVEL.ERROR, SNS_CATEGORY.PROTOCOL, "Error closing SNS resources"),
  SNS_MESSAGE_SENT(LEVEL.DEBUG, SNS_CATEGORY.PROTOCOL, "SNS message sent to {}"),
  SNS_SEND_ERROR(LEVEL.ERROR, SNS_CATEGORY.PROTOCOL, "Failed to send SNS message to {}"),
  SNS_PRODUCER_NOT_FOUND(LEVEL.WARN, SNS_CATEGORY.PROTOCOL, "Producer for {} not found"),
  SNS_SUBSCRIBE_REMOTE_SUCCESS(LEVEL.INFO, SNS_CATEGORY.PROTOCOL, "Subscribed to SNS queue: {}"),
  SNS_SUBSCRIBE_LOCAL_SUCCESS(LEVEL.INFO, SNS_CATEGORY.PROTOCOL, "Registered local SNS producer for queue: {}"),
  SNS_POLL_ERROR(LEVEL.ERROR, SNS_CATEGORY.PROTOCOL, "Error polling SNS messages from {}"),
  ;

  ;

  private final  String message;
  private final  LEVEL level;
  private final Category category;
  private final  int parameterCount;

  SnsLogMessages(LEVEL level, Category category, String message) {
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

  @Getter
  public enum SNS_CATEGORY implements Category {
    PROTOCOL("Protocol");

    private final String description;

    public String getDivision() {
      return "Inter-Protocol";
    }

    SNS_CATEGORY(String description) {
      this.description = description;
    }
  }

}
