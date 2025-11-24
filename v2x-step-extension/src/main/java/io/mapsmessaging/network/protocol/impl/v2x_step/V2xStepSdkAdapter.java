package io.mapsmessaging.network.protocol.impl.v2x_step;

import java.io.IOException;

/**
 * Adapter interface for V2X STEP SDK operations.
 * This abstraction allows for mocking the SDK in unit tests without requiring
 * the proprietary V2XSDK library on the classpath.
 */
public interface V2xStepSdkAdapter {

  /**
   * Trigger a DENM event via the STEP SDK.
   *
   * @param params The DENM parameters extracted from the message
   * @return The sequence number assigned by the SDK
   * @throws IOException if triggering fails
   */
  long triggerDenm(DenmParameters params) throws IOException;
}
