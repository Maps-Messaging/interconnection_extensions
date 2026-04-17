package io.mapsmessaging.network.protocol.impl.ros;

import java.io.IOException;

@FunctionalInterface
public interface RosMessageListener {
  void onMessage(RosMessageEnvelope envelope) throws IOException;
}
