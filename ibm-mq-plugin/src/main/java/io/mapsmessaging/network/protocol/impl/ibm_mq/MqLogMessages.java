package io.mapsmessaging.network.protocol.impl.ibm_mq;

import io.mapsmessaging.logging.Category;
import io.mapsmessaging.logging.LEVEL;
import io.mapsmessaging.logging.LogMessage;
import lombok.Getter;

@Getter
public enum MqLogMessages implements LogMessage {

  MQ_INITIALIZED(LEVEL.INFO, MQ_CATEGORY.PROTOCOL, "MQ connection established to {}"),
  MQ_INITIALIZE_ERROR(LEVEL.ERROR, MQ_CATEGORY.PROTOCOL, "Error initializing MQ"),
  MQ_CLOSE_ERROR(LEVEL.ERROR, MQ_CATEGORY.PROTOCOL, "Error closing MQ resources"),
  MQ_MESSAGE_SENT(LEVEL.DEBUG, MQ_CATEGORY.PROTOCOL, "MQ message sent to {}"),
  MQ_SEND_ERROR(LEVEL.ERROR, MQ_CATEGORY.PROTOCOL, "Failed to send MQ message to {}"),
  MQ_PRODUCER_NOT_FOUND(LEVEL.WARN, MQ_CATEGORY.PROTOCOL, "Producer for {} not found"),
  MQ_SUBSCRIBE_REMOTE_SUCCESS(LEVEL.INFO, MQ_CATEGORY.PROTOCOL, "Subscribed to MQ queue: {}"),
  MQ_SUBSCRIBE_LOCAL_SUCCESS(LEVEL.INFO, MQ_CATEGORY.PROTOCOL, "Registered local MQ producer for queue: {}"),
  MQ_POLL_ERROR(LEVEL.ERROR, MQ_CATEGORY.PROTOCOL, "Error polling MQ messages from {}"),
  ;

  ;

  private final  String message;
  private final  LEVEL level;
  private final Category category;
  private final  int parameterCount;

  MqLogMessages(LEVEL level, Category category, String message) {
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
  public enum MQ_CATEGORY implements Category {
    PROTOCOL("Protocol");

    private final String description;

    public String getDivision() {
      return "Inter-Protocol";
    }

    MQ_CATEGORY(String description) {
      this.description = description;
    }
  }

}
