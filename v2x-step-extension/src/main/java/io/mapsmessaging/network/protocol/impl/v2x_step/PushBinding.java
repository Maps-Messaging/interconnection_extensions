package io.mapsmessaging.network.protocol.impl.v2x_step;

/**
 * Metadata for a push link (MAPS → STEP).
 * Stores the service type, STEP publish group, and field mapping for outbound message routing.
 */
public class PushBinding {
  private final StepServiceType serviceType;
  private final String publishGroup;
  private final DenmFieldMapping fieldMapping;

  /**
   * Create a push binding.
   *
   * @param serviceType The STEP service type (currently only DENM)
   * @param publishGroup The STEP publish group name
   * @param fieldMapping Field mapping configuration for extracting parameters
   */
  public PushBinding(StepServiceType serviceType, String publishGroup, DenmFieldMapping fieldMapping) {
    if (serviceType == null) {
      throw new IllegalArgumentException("Service type cannot be null");
    }
    if (publishGroup == null || publishGroup.trim().isEmpty()) {
      throw new IllegalArgumentException("Publish group cannot be null or empty");
    }
    if (fieldMapping == null) {
      throw new IllegalArgumentException("Field mapping cannot be null");
    }
    this.serviceType = serviceType;
    this.publishGroup = publishGroup;
    this.fieldMapping = fieldMapping;
  }

  public StepServiceType getServiceType() {
    return serviceType;
  }

  public String getPublishGroup() {
    return publishGroup;
  }

  public DenmFieldMapping getFieldMapping() {
    return fieldMapping;
  }

  @Override
  public String toString() {
    return "PushBinding{serviceType=" + serviceType +
        ", publishGroup='" + publishGroup + '\'' +
        ", fieldMapping=" + fieldMapping + '}';
  }
}
