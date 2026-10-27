package io.mapsmessaging.network.protocol.impl.ros;

import io.mapsmessaging.logging.Category;
import io.mapsmessaging.logging.LEVEL;
import io.mapsmessaging.logging.LogMessage;

public enum RosLogMessages implements LogMessage {

  ROS_INITIALIZED(LEVEL.INFO, ROS_CATEGORY.PROTOCOL, "ROS extension initialized: {}"),
  ROS_INITIALIZE_ERROR(LEVEL.ERROR, ROS_CATEGORY.PROTOCOL, "ROS extension initialization failed"),
  ROS_CLOSED(LEVEL.INFO, ROS_CATEGORY.PROTOCOL, "ROS extension closed"),
  ROS_CLOSE_ERROR(LEVEL.ERROR, ROS_CATEGORY.PROTOCOL, "ROS extension close failed"),
  ROS_REGISTER_LOCAL(LEVEL.INFO, ROS_CATEGORY.PROTOCOL, "ROS local link registered: {}"),
  ROS_REGISTER_REMOTE(LEVEL.INFO, ROS_CATEGORY.PROTOCOL, "ROS remote link registered: {}"),
  ROS_MESSAGE_SENT(LEVEL.DEBUG, ROS_CATEGORY.PROTOCOL, "ROS message published: {}"),
  ROS_MESSAGE_RECEIVED(LEVEL.DEBUG, ROS_CATEGORY.PROTOCOL, "ROS message received: {}"),
  ROS_OUTBOUND_ERROR(LEVEL.ERROR, ROS_CATEGORY.PROTOCOL, "ROS outbound publish failed for {}"),
  ROS_INBOUND_ERROR(LEVEL.ERROR, ROS_CATEGORY.PROTOCOL, "ROS inbound handling failed for {}"),
  ROS_HINT(LEVEL.INFO, ROS_CATEGORY.PROTOCOL, "ROS runtime hint: {}"),
  ROS_QOS_APPLIED(LEVEL.DEBUG, ROS_CATEGORY.PROTOCOL, "ROS QoS applied: {}"),
  ROS_MESSAGE_FALLBACK(LEVEL.DEBUG, ROS_CATEGORY.PROTOCOL, "ROS metadata fallback: key={} source={}");

  private final String message;
  private final LEVEL level;
  private final Category category;
  private final int parameterCount;

  RosLogMessages(LEVEL level, Category category, String message) {
    this.message = message;
    this.level = level;
    this.category = category;
    int count = 0;
    int location = message.indexOf("{}");
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

  public enum ROS_CATEGORY implements Category {
    PROTOCOL("Protocol");

    private final String description;

    ROS_CATEGORY(String description) {
      this.description = description;
    }

    @Override
    public String getDivision() {
      return "Inter-Protocol";
    }

    @Override
    public String getDescription() {
      return description;
    }
  }
}