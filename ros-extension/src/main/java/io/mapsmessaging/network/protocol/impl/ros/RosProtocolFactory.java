package io.mapsmessaging.network.protocol.impl.ros;

import io.mapsmessaging.dto.rest.config.protocol.impl.ExtensionConfigDTO;
import io.mapsmessaging.network.io.EndPoint;
import io.mapsmessaging.network.io.Packet;
import io.mapsmessaging.network.protocol.Protocol;
import io.mapsmessaging.network.protocol.ProtocolImplFactory;
import io.mapsmessaging.network.protocol.detection.NoOpDetection;
import io.mapsmessaging.network.protocol.impl.extension.ExtensionEndPoint;
import io.mapsmessaging.network.protocol.impl.extension.ExtensionProtocol;

import java.io.IOException;

public class RosProtocolFactory extends ProtocolImplFactory {

  public RosProtocolFactory() {
    super("ros", "Provides ROS2 topic bridge support", new NoOpDetection());
  }

  @Override
  public Protocol connect(EndPoint endPoint, String sessionId, String username, String password) throws IOException {
    ExtensionConfigDTO config = (ExtensionConfigDTO) ((ExtensionEndPoint) endPoint).config();
    Protocol protocol = new ExtensionProtocol(endPoint, new RosProtocol(config));
    protocol.connect(sessionId, username, password);
    return protocol;
  }

  @Override
  public void create(EndPoint endPoint, Packet packet) {
    // ROS extension does not accept inbound client sockets.
  }

  @Override
  public String getTransportType() {
    return "ros";
  }
}
