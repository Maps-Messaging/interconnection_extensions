package io.mapsmessaging.network.protocol.impl.ros;

import io.mapsmessaging.logging.Logger;

public final class RosClientAdapterFactory {

  private RosClientAdapterFactory() {
  }

  public static RosClientAdapter create(Logger logger, RosBridgeConfig config) {
    return new ReflectiveJRosAdapter(logger, config);
  }
}
