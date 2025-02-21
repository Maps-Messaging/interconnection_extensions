package io.mapsmessaging.network.protocol.impl.apache_pulsar;

import io.mapsmessaging.logging.Category;
import io.mapsmessaging.logging.LEVEL;
import io.mapsmessaging.logging.LogMessage;
import lombok.Getter;

@Getter
public enum PulsarLogMessages implements LogMessage {

  //-------------------------------------------------------------------------------------------------------------

  // <editor-fold desc="Generic messages">
  INITIALISE_PULSAR_ENDPOINT(LEVEL.INFO, PULSAR_CATEGORY.PROTOCOL, "Initialising pulsar endpoint on {}"),
  CLOSE_PULSAR_ENDPOINT(LEVEL.INFO, PULSAR_CATEGORY.PROTOCOL, "Closing pulsar endpoint"),
  PULSAR_SEND_MESSAGE(LEVEL.DEBUG, PULSAR_CATEGORY.PROTOCOL, "Pulsar send message to {}"),
  PULSAR_FAILED_TO_SEND_MESSAGE(LEVEL.ERROR, PULSAR_CATEGORY.PROTOCOL, "Failed to send message to {}"),
  PULSAR_SESSION_CREATION_ERROR(LEVEL.ERROR, PULSAR_CATEGORY.PROTOCOL, "Failed to create local session"),
  PULSAR_SUBSCRIBE_LOCAL_SUCCESS(LEVEL.INFO, PULSAR_CATEGORY.PROTOCOL, "Subscribed local from {} to {}"),
  PULSAR_SUBSCRIBE_REMOTE_SUCCESS(LEVEL.INFO, PULSAR_CATEGORY.PROTOCOL, "Subscribed local from {} to {}"),
  PULSAR_FAILED_TO_PROCESS_INCOMING_EVENT(LEVEL.ERROR, PULSAR_CATEGORY.PROTOCOL, "Failed to process incoming message to {}"),
  PULSAR_CONNECT_ERROR(LEVEL.ERROR, PULSAR_CATEGORY.PROTOCOL, "Failed to connect to {}"),
  ;

  private final  String message;
  private final  LEVEL level;
  private final  Category category;
  private final  int parameterCount;

  PulsarLogMessages(LEVEL level, Category category, String message) {
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

  public enum PULSAR_CATEGORY implements Category {
    PROTOCOL("Protocol");

    private final @Getter String description;

    public String getDivision() {
      return "Inter-Protocol";
    }

    PULSAR_CATEGORY(String description) {
      this.description = description;
    }
  }

}
