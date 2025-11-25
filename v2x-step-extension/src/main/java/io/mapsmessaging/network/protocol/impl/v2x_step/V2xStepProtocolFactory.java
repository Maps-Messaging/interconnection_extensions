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

  public V2xStepProtocolFactory() {
    super("v2x-step", "Provides a V2X STEP protocol connection", new NoOpDetection());
  }



  @Override
  public Protocol connect(EndPoint endPoint, String sessionId, String username, String password) throws IOException {
    ExtensionConfigDTO config = (ExtensionConfigDTO) ((ExtensionEndPoint) endPoint).config();

    // V2X STEP uses applicationId/applicationToken authentication, not username/password
    // Provide default credentials and session ID if not specified to avoid NullPointerException in ExtensionProtocol
    String effectiveSessionId = (sessionId != null && !sessionId.isEmpty()) ? sessionId : "v2x-step-session-" + java.util.UUID.randomUUID();
    String effectiveUsername = (username != null && !username.isEmpty()) ? username : "v2x-step-client";
    String effectivePassword = (password != null && !password.isEmpty()) ? password : "";

    Protocol protocol = new ExtensionProtocol(endPoint, new V2xStepProtocol(endPoint, config));
    protocol.connect(effectiveSessionId, effectiveUsername, effectivePassword);
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