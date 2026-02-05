package io.mapsmessaging.network.protocol.impl.ros;

import java.io.IOException;

public interface RosClientAdapter {
  void connect() throws IOException;

  void registerPublisher(String topic, String rosType) throws IOException;

  void subscribe(String topic, String rosType, RosMessageListener listener) throws IOException;

  void publish(String topic, RosMessageEnvelope envelope) throws IOException;

  void close() throws IOException;
}
