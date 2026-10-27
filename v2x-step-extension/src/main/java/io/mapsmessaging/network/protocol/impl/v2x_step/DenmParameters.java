package io.mapsmessaging.network.protocol.impl.v2x_step;

/**
 * Extracted DENM parameters for triggering via STEP SDK.
 */
public class DenmParameters {
  private final int causeCode;
  private final int subCauseCode;
  private final double latitude;
  private final double longitude;
  private final int validityDuration;
  private final int transmissionInterval;
  private final long detectionTime;

  public DenmParameters(int causeCode, int subCauseCode, double latitude, double longitude,
                        int validityDuration, int transmissionInterval, long detectionTime) {
    this.causeCode = causeCode;
    this.subCauseCode = subCauseCode;
    this.latitude = latitude;
    this.longitude = longitude;
    this.validityDuration = validityDuration;
    this.transmissionInterval = transmissionInterval;
    this.detectionTime = detectionTime;
  }

  public int getCauseCode() { return causeCode; }
  public int getSubCauseCode() { return subCauseCode; }
  public double getLatitude() { return latitude; }
  public double getLongitude() { return longitude; }
  public int getValidityDuration() { return validityDuration; }
  public int getTransmissionInterval() { return transmissionInterval; }
  public long getDetectionTime() { return detectionTime; }

  @Override
  public String toString() {
    return "DenmParameters{" +
        "causeCode=" + causeCode +
        ", subCauseCode=" + subCauseCode +
        ", lat=" + latitude +
        ", lon=" + longitude +
        ", validityDuration=" + validityDuration +
        ", transmissionInterval=" + transmissionInterval +
        ", detectionTime=" + detectionTime +
        '}';
  }
}
