package io.mapsmessaging.network.protocol.impl.v2x_step;

/**
 * Configuration for a pull (inbound) link that routes DENM events from STEP to MapsMessaging.
 * Pull bindings define how received DENM messages should be handled and routed.
 */
public class PullBinding {

  private final StepServiceType serviceType;
  private final String subscribeGroup;
  private final boolean filterOwnMessages;
  private final String outputFormat;

  /**
   * Create a new pull binding configuration.
   *
   * @param serviceType The service type (e.g., DENM)
   * @param subscribeGroup The STEP subscribe group name
   * @param filterOwnMessages Whether to filter out our own broadcast messages
   * @param outputFormat Output format for DENM data ("json" or "xml")
   */
  public PullBinding(StepServiceType serviceType, String subscribeGroup,
                     boolean filterOwnMessages, String outputFormat) {
    this.serviceType = serviceType;
    this.subscribeGroup = subscribeGroup;
    this.filterOwnMessages = filterOwnMessages;
    this.outputFormat = outputFormat != null ? outputFormat : "json"; // Default to JSON
  }

  public StepServiceType getServiceType() {
    return serviceType;
  }

  public String getSubscribeGroup() {
    return subscribeGroup;
  }

  public boolean isFilterOwnMessages() {
    return filterOwnMessages;
  }

  public String getOutputFormat() {
    return outputFormat;
  }

  @Override
  public String toString() {
    return "PullBinding{" +
        "serviceType=" + serviceType +
        ", subscribeGroup='" + subscribeGroup + '\'' +
        ", filterOwnMessages=" + filterOwnMessages +
        ", outputFormat='" + outputFormat + '\'' +
        '}';
  }
}
