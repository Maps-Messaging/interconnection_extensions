package io.mapsmessaging.network.protocol.impl.v2x_step;

import io.mapsmessaging.dto.rest.config.protocol.impl.ExtensionConfigDTO;
import io.mapsmessaging.network.io.EndPoint;
import io.mapsmessaging.network.io.Packet;
import io.mapsmessaging.network.protocol.Protocol;
import io.mapsmessaging.network.protocol.ProtocolImplFactory;
import io.mapsmessaging.network.protocol.detection.NoOpDetection;
import io.mapsmessaging.network.protocol.impl.extension.ExtensionProtocol;
import io.mapsmessaging.network.protocol.impl.extension.ExtensionEndPoint;
import java.io.IOException;

/**
 * Factory for V2X STEP protocol.
 */
public class V2xStepProtocolFactory extends ProtocolImplFactory {

    static{
        System.out.println("##### v2xStep Protocol class loaded");
    }


  public V2xStepProtocolFactory() {
    super("v2x-step", "Provides a V2X STEP protocol connection", new NoOpDetection());
  }



  @Override
  public Protocol connect(EndPoint endPoint, String sessionId, String username, String password) throws IOException {
    ExtensionConfigDTO config = (ExtensionConfigDTO) ((ExtensionEndPoint) endPoint).config();
    Protocol protocol = new ExtensionProtocol(endPoint, new V2xStepProtocol(endPoint, config));
    protocol.connect(sessionId, username, password);
    return protocol;
  }

  @Override
  public void create(EndPoint endPoint, Packet packet) throws IOException {
    // STEP protocol does not support incoming client connections
  }

  @Override
  public String getTransportType() {
    return "";
  }
}