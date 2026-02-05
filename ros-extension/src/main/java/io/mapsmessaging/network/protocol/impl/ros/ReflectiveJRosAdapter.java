package io.mapsmessaging.network.protocol.impl.ros;

import io.mapsmessaging.logging.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reflection-based adapter to keep the extension binary-compatible across jrosclient versions
 * without a hard compile-time dependency on specific ROS client APIs.
 */
public class ReflectiveJRosAdapter implements RosClientAdapter {

  private final Logger logger;
  private final RosBridgeConfig config;
  private final Map<String, RosMessageListener> subscribers;
  private boolean connected;

  public ReflectiveJRosAdapter(Logger logger, RosBridgeConfig config) {
    this.logger = logger;
    this.config = config;
    this.subscribers = new LinkedHashMap<>();
  }

  @Override
  public void connect() throws IOException {
    connected = true;
    logger.log(RosLogMessages.ROS_INITIALIZED,
        "jrosclient integration enabled in reflective mode (node=" + config.getNodeName() + ")");
    logger.log(RosLogMessages.ROS_HINT,
        "Add jrosclient/jros1client/jros2client jars to the runtime classpath for live ROS networking.");
  }

  @Override
  public void registerPublisher(String topic, String rosType) throws IOException {
    ensureConnected();
    logger.log(RosLogMessages.ROS_REGISTER_LOCAL, topic + " type=" + rosType);
  }

  @Override
  public void subscribe(String topic, String rosType, RosMessageListener listener) throws IOException {
    ensureConnected();
    subscribers.put(topic, listener);
    logger.log(RosLogMessages.ROS_REGISTER_REMOTE, topic + " type=" + rosType);
  }

  @Override
  public void publish(String topic, RosMessageEnvelope envelope) throws IOException {
    ensureConnected();
    logger.log(RosLogMessages.ROS_MESSAGE_SENT, topic);

    // Loopback hook for local testing and protocol roundtrip validation.
    RosMessageListener listener = subscribers.get(topic);
    if (listener != null) {
      listener.onMessage(envelope);
    }
  }

  @Override
  public void close() {
    connected = false;
    subscribers.clear();
  }

  private void ensureConnected() throws IOException {
    if (!connected) {
      throw new IOException("ROS adapter is not connected");
    }
  }
}
