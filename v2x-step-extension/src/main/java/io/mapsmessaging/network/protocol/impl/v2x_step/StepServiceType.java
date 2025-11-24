package io.mapsmessaging.network.protocol.impl.v2x_step;

/**
 * Enumeration of supported STEP service types.
 * Currently only DENM is implemented.
 */
public enum StepServiceType {
  DENM;

  /**
   * Parse a service type string (case-insensitive).
   *
   * @param value The service type string
   * @return The parsed StepServiceType
   * @throws IllegalArgumentException if the value is not a valid service type
   */
  public static StepServiceType fromString(String value) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("Service type cannot be null or empty");
    }
    return valueOf(value.trim().toUpperCase());
  }
}
