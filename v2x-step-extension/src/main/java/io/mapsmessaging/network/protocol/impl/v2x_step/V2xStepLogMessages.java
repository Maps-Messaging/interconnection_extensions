package io.mapsmessaging.network.protocol.impl.v2x_step;

import io.mapsmessaging.logging.Category;
import io.mapsmessaging.logging.LEVEL;
import io.mapsmessaging.logging.LogMessage;

/**
 * Logging messages for V2X STEP protocol.
 */
public enum V2xStepLogMessages implements LogMessage {

  // Initialization and lifecycle
  V2X_STEP_CONSTRUCTOR(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "V2X STEP protocol constructor called with URL: {}"),
  V2X_STEP_CONFIG_RECEIVED(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Configuration received with keys: {}"),
  V2X_STEP_CONFIG_NULL(LEVEL.WARN, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Configuration is NULL!"),
  V2X_STEP_INIT_START(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "V2X STEP initialise() called"),
  V2X_STEP_INIT_CONFIG(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Configuration map: {}"),
  V2X_STEP_INIT_INSTANCE(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Parsed STEP instance from URL: {}"),
  V2X_STEP_INIT_DENM_CONFIG(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "DENM service enabled={}, publishGroup={}"),
  V2X_STEP_INIT_SDK_START(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Starting V2X SDK with instance={}, appId={}"),
  V2X_STEP_INIT_SDK_STATE(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "V2X service state: {}"),
  V2X_STEP_INITIALIZED(LEVEL.INFO, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "V2X STEP protocol initialized"),
  V2X_STEP_CLOSED(LEVEL.INFO, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "V2X STEP protocol closed"),

  // Link registration
  V2X_STEP_REGISTER_LOCAL_START(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "registerLocalLink() called for destination: {}"),
  V2X_STEP_REGISTER_LOCAL_ATTRS(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Found link attributes: {}"),
  V2X_STEP_REGISTER_LOCAL_NO_ATTRS(LEVEL.ERROR, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "No push link configuration found for destination: {}"),
  V2X_STEP_REGISTER_LOCAL_SERVICE_TYPE(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Link service_type: {}"),
  V2X_STEP_REGISTER_LOCAL_PUBLISH_GROUP(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Link publish_group: {}"),
  V2X_STEP_REGISTER_LOCAL_FIELD_MAPPING(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Link field_mapping configured: {}"),
  V2X_STEP_REGISTER_LOCAL_SUCCESS(LEVEL.INFO, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Successfully registered push binding: {} -> {}"),
  V2X_STEP_SUBSCRIBE_LOCAL(LEVEL.INFO, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Registered local STEP subscription for: {}"),
  V2X_STEP_SUBSCRIBE_REMOTE(LEVEL.INFO, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Subscribed to STEP remote: {}"),

  // Outbound message routing
  V2X_STEP_OUTBOUND_CALLED(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "outbound() called for destination: {}"),
  V2X_STEP_OUTBOUND_BINDINGS_COUNT(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Current push bindings count: {}, registered destinations: {}"),
  V2X_STEP_OUTBOUND_BINDING_LOOKUP(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Looking up binding for destination: {}, found: {}"),
  V2X_STEP_DESTINATION_NOT_REGISTERED(LEVEL.WARN, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Outbound message to unregistered destination: {}"),
  V2X_STEP_OUTBOUND_MESSAGE_SIZE(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Message payload size: {} bytes"),
  V2X_STEP_OUTBOUND_EXTRACTING(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Extracting DENM parameters using field mapping: {}"),
  V2X_STEP_OUTBOUND_PARAMS(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Extracted DENM parameters: {}"),
  V2X_STEP_OUTBOUND_TRIGGERING(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Triggering DENM via SDK adapter"),
  V2X_STEP_OUTBOUND_SUCCESS(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Triggered {} in group {}, sequenceNumber={}"),
  V2X_STEP_OUTBOUND_ERROR(LEVEL.ERROR, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "Failed to publish outbound message to {}: {}"),

  // General
  V2X_STEP_MESSAGE_SENT(LEVEL.DEBUG, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "STEP message sent to {}"),
  V2X_STEP_ERROR(LEVEL.ERROR, V2xStepLogMessages.V2X_STEP_CATEGORY.PROTOCOL, "V2X STEP error: {}");

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