package io.mapsmessaging.network.protocol.impl.ibm_mq;

import io.mapsmessaging.network.io.EndPoint;
import io.mapsmessaging.network.io.Packet;
import io.mapsmessaging.network.protocol.Protocol;
import io.mapsmessaging.network.protocol.ProtocolImplFactory;
import io.mapsmessaging.network.protocol.detection.NoOpDetection;
import io.mapsmessaging.network.protocol.impl.plugin.PluginProtocol;

import java.io.IOException;

public class MqProtocolFactory extends ProtocolImplFactory {

  public MqProtocolFactory(){
    super("mq", "Provides a connection an IBM MQ server", new NoOpDetection());
  }

  @Override
  public Protocol connect(EndPoint endPoint, String sessionId, String username, String password) throws IOException {
    Protocol protocol = new PluginProtocol( endPoint, new MqProtocol(endPoint));
    protocol.connect(sessionId, username, password);
    return protocol;
  }

  @Override
  public void create(EndPoint endPoint, Packet packet) throws IOException {
    // We don't accept incoming pulsar client connections
  }

  @Override
  public String getTransportType() {
    return "";
  }
}
