package io.mapsmessaging.network.protocol.impl.aws_sns;

import io.mapsmessaging.dto.rest.config.protocol.impl.ExtensionConfigDTO;
import io.mapsmessaging.network.io.EndPoint;
import io.mapsmessaging.network.io.Packet;
import io.mapsmessaging.network.protocol.Protocol;
import io.mapsmessaging.network.protocol.ProtocolImplFactory;
import io.mapsmessaging.network.protocol.detection.NoOpDetection;
import io.mapsmessaging.network.protocol.impl.extension.ExtensionProtocol;
import io.mapsmessaging.network.protocol.impl.extension.ExtensionEndPoint;

import java.io.IOException;

public class SnsProtocolFactory extends ProtocolImplFactory {

  public SnsProtocolFactory() {
    super("aws_sns", "Provides an AWS SNS connection", new NoOpDetection());
  }

  @Override
  public Protocol connect(EndPoint endPoint, String sessionId, String username, String password) throws IOException {
    ExtensionConfigDTO protocolConfigDTO = (ExtensionConfigDTO) ((ExtensionEndPoint) endPoint).config();
    Protocol protocol = new ExtensionProtocol(endPoint, new SnsProtocol(endPoint, protocolConfigDTO));
    protocol.connect(sessionId, username, password);
    return protocol;
  }

  @Override
  public void create(EndPoint endPoint, Packet packet) throws IOException {
    // SNS does not accept direct client connections
  }

  @Override
  public String getTransportType() {
    return "aws_sns";
  }
}
