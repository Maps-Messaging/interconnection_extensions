package io.mapsmessaging.network.protocol.impl.v2x_step;

import io.mapsmessaging.logging.Category;
import io.mapsmessaging.logging.LEVEL;
import io.mapsmessaging.logging.LogMessage;

/**
 * Logging messages for V2X STEP protocol.
 */
public enum V2xStepLogMessages implements LogMessage {

  V2X_STEP_INITIALIZED(LEVEL.INFO, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "V2X STEP protocol initialized"),
  V2X_STEP_CLOSED(LEVEL.INFO, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "V2X STEP protocol closed"),
  V2X_STEP_MESSAGE_SENT(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "STEP message sent to {}"),
  V2X_STEP_SUBSCRIBE_REMOTE(LEVEL.INFO, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Subscribed to STEP remote: {}"),
  V2X_STEP_SUBSCRIBE_LOCAL(LEVEL.INFO, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Registered local STEP subscription for: {}");

  private final String message;
  private final LEVEL level;
  private final Category category;
  private final int parameterCount;

  V2xStepLogMessages(LEVEL level, Category category, String message) {
    this.level = level;
    this.category = category;
    this.message = message;
    int count = 0;
    int idx = message.indexOf("{}");
    while (idx != -1) {
      count++;
      idx = message.indexOf("{}", idx + 2);
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

  public enum V2X_STEP_CATEGORY implements Category {
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

    V2X_STEP_CATEGORY(String description) {
      this.description = description;
    }
  }
}