package io.mapsmessaging.network.protocol.impl.v2x_step;

import com.vodafone.v2xsdk4javav2.facade.V2XSDK;
import com.vodafone.v2xsdk4javav2.facade.models.GnssLocation;
import java.io.IOException;

/**
 * Default implementation of V2xStepSdkAdapter that delegates to the real V2XSDK.
 */
public class V2xStepSdkAdapterImpl implements V2xStepSdkAdapter {

  private final V2XSDK sdk;

  public V2xStepSdkAdapterImpl(V2XSDK sdk) {
    if (sdk == null) {
      throw new IllegalArgumentException("V2XSDK cannot be null");
    }
    this.sdk = sdk;
  }

  @Override
  public long triggerDenm(DenmParameters params) throws IOException {
    try {
      // Create GNSS location for the event
      // Constructor: GnssLocation(double lat, double lon, Double altitude, Float bearing,
      //                           Float speed, Float accuracy, Long timestamp)
      GnssLocation eventLocation = new GnssLocation(
          params.getLatitude(),
          params.getLongitude(),
          null,  // altitude
          null,  // bearing
          null,  // speed
          null,  // accuracy
          null   // timestamp
      );

      // Trigger DENM via SDK
      // Method signature: denmTrigger(int causeCode, int subCauseCode, GnssLocation location,
      //                               int validityDuration, int transmissionInterval, long detectionTime)
      long sequenceNumber = sdk.denmTrigger(
          params.getCauseCode(),
          params.getSubCauseCode(),
          eventLocation,
          params.getValidityDuration(),
          params.getTransmissionInterval(),
          params.getDetectionTime()
      );

      return sequenceNumber;
    } catch (Exception e) {
      throw new IOException("Failed to trigger DENM: " + e.getMessage(), e);
    }
  }
}
