package io.mapsmessaging.network.protocol.impl.v2x_step;

import java.util.Map;

/**
 * Configuration for mapping payload fields to DENM parameters.
 * This allows the extension to extract STEP SDK parameters from incoming message payloads
 * regardless of the payload format (XML, JSON, etc.).
 */
public class DenmFieldMapping {
  private final String causeCodeField;
  private final String subCauseCodeField;
  private final String latitudeField;
  private final String longitudeField;
  private final String validityDurationField;
  private final String transmissionIntervalField;
  private final String detectionTimeField;

  // Default field paths for ETSI DENM XML structure
  // Note: Paths start from root element, <situation> is a direct child of root <denm>
  public static final String DEFAULT_CAUSE_CODE = "situation.eventType.causeCode";
  public static final String DEFAULT_SUB_CAUSE_CODE = "situation.eventType.subCauseCode";
  // <management> is nested inside <denm><denm><management>
  public static final String DEFAULT_LATITUDE = "denm.management.eventPosition.latitude";
  public static final String DEFAULT_LONGITUDE = "denm.management.eventPosition.longitude";
  public static final String DEFAULT_VALIDITY_DURATION = "denm.management.validityDuration";
  public static final String DEFAULT_TRANSMISSION_INTERVAL = "denm.management.transmissionInterval";
  public static final String DEFAULT_DETECTION_TIME = "denm.management.detectionTime";

  private DenmFieldMapping(Builder builder) {
    this.causeCodeField = builder.causeCodeField;
    this.subCauseCodeField = builder.subCauseCodeField;
    this.latitudeField = builder.latitudeField;
    this.longitudeField = builder.longitudeField;
    this.validityDurationField = builder.validityDurationField;
    this.transmissionIntervalField = builder.transmissionIntervalField;
    this.detectionTimeField = builder.detectionTimeField;
  }

  public String getCauseCodeField() { return causeCodeField; }
  public String getSubCauseCodeField() { return subCauseCodeField; }
  public String getLatitudeField() { return latitudeField; }
  public String getLongitudeField() { return longitudeField; }
  public String getValidityDurationField() { return validityDurationField; }
  public String getTransmissionIntervalField() { return transmissionIntervalField; }
  public String getDetectionTimeField() { return detectionTimeField; }

  /**
   * Create field mapping from configuration map.
   *
   * @param fieldMappings Map of field name to payload path
   * @return DenmFieldMapping with configured or default paths
   */
  public static DenmFieldMapping fromConfig(Map<String, Object> fieldMappings) {
    Builder builder = new Builder();

    if (fieldMappings != null) {
      builder.causeCodeField(getStringOrDefault(fieldMappings, "causeCode", DEFAULT_CAUSE_CODE));
      builder.subCauseCodeField(getStringOrDefault(fieldMappings, "subCauseCode", DEFAULT_SUB_CAUSE_CODE));
      builder.latitudeField(getStringOrDefault(fieldMappings, "latitude", DEFAULT_LATITUDE));
      builder.longitudeField(getStringOrDefault(fieldMappings, "longitude", DEFAULT_LONGITUDE));
      builder.validityDurationField(getStringOrDefault(fieldMappings, "validityDuration", DEFAULT_VALIDITY_DURATION));
      builder.transmissionIntervalField(getStringOrDefault(fieldMappings, "transmissionInterval", DEFAULT_TRANSMISSION_INTERVAL));
      builder.detectionTimeField(getStringOrDefault(fieldMappings, "detectionTime", DEFAULT_DETECTION_TIME));
    }

    return builder.build();
  }

  /**
   * Create default field mapping for ETSI DENM XML structure.
   */
  public static DenmFieldMapping createDefault() {
    return new Builder().build();
  }

  private static String getStringOrDefault(Map<String, Object> map, String key, String defaultValue) {
    Object value = map.get(key);
    return value != null ? value.toString() : defaultValue;
  }

  public static class Builder {
    private String causeCodeField = DEFAULT_CAUSE_CODE;
    private String subCauseCodeField = DEFAULT_SUB_CAUSE_CODE;
    private String latitudeField = DEFAULT_LATITUDE;
    private String longitudeField = DEFAULT_LONGITUDE;
    private String validityDurationField = DEFAULT_VALIDITY_DURATION;
    private String transmissionIntervalField = DEFAULT_TRANSMISSION_INTERVAL;
    private String detectionTimeField = DEFAULT_DETECTION_TIME;

    public Builder causeCodeField(String field) { this.causeCodeField = field; return this; }
    public Builder subCauseCodeField(String field) { this.subCauseCodeField = field; return this; }
    public Builder latitudeField(String field) { this.latitudeField = field; return this; }
    public Builder longitudeField(String field) { this.longitudeField = field; return this; }
    public Builder validityDurationField(String field) { this.validityDurationField = field; return this; }
    public Builder transmissionIntervalField(String field) { this.transmissionIntervalField = field; return this; }
    public Builder detectionTimeField(String field) { this.detectionTimeField = field; return this; }

    public DenmFieldMapping build() {
      return new DenmFieldMapping(this);
    }
  }

  @Override
  public String toString() {
    return "DenmFieldMapping{" +
        "causeCode='" + causeCodeField + '\'' +
        ", subCauseCode='" + subCauseCodeField + '\'' +
        ", latitude='" + latitudeField + '\'' +
        ", longitude='" + longitudeField + '\'' +
        ", validityDuration='" + validityDurationField + '\'' +
        ", transmissionInterval='" + transmissionIntervalField + '\'' +
        ", detectionTime='" + detectionTimeField + '\'' +
        '}';
  }
}
