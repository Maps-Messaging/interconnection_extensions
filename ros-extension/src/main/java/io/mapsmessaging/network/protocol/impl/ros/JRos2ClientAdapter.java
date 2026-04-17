package io.mapsmessaging.network.protocol.impl.ros;

import id.jros2client.JRos2Client;
import id.jros2client.JRos2ClientConfiguration;
import id.jros2client.JRos2ClientFactory;
import id.jros2client.qos.PublisherQos;
import id.jros2client.qos.QosDurability;
import id.jros2client.qos.QosReliability;
import id.jros2client.qos.SubscriberQos;
import com.google.gson.Gson;
import id.jros2messages.Ros2MessageSerializationUtils;
import id.jrosclient.TopicSubmissionPublisher;
import id.jrosclient.TopicSubscriber;
import id.jrosclient.exceptions.JRosClientException;
import id.jrosmessages.Message;
import io.mapsmessaging.logging.Logger;
import lombok.NonNull;
import pinorobotics.rtpstalk.RtpsTalkConfiguration;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ROS2 adapter backed by jros2client.
 *
 * <p>Supported {@code ros_qos} profile values: {@code default}, {@code sensor_data}
 * (BEST_EFFORT+VOLATILE), {@code reliable} (RELIABLE+VOLATILE),
 * {@code transient_local} (RELIABLE+TRANSIENT_LOCAL).
 */
public class JRos2ClientAdapter {

  private final Logger logger;
  private final RosBridgeConfig config;
  private final Ros2MessageSerializationUtils serializer = new Ros2MessageSerializationUtils();
  private final Gson gson = new Gson();
  private final Map<String, PublisherHolder> publishers = new ConcurrentHashMap<>();
  private volatile JRos2Client client;

  public JRos2ClientAdapter(@NonNull Logger logger, @NonNull RosBridgeConfig config) {
    this.logger = logger;
    this.config = config;
  }

  public void connect() throws IOException {
    try {
      JRos2ClientConfiguration clientConfig = buildClientConfiguration();
      client = new JRos2ClientFactory().createClient(clientConfig);
      logger.log(RosLogMessages.ROS_INITIALIZED,
          "jros2client adapter connected (domainId="
              + (config.rosDomainId() == null ? "default" : config.rosDomainId())
              + ", interface=" + (config.networkInterface() == null ? "default" : config.networkInterface())
              + ")");
    } catch (Exception e) {
      throw new IOException("Failed to initialize jros2client", e);
    }
  }

  /**
   * Returns true if the DDS client is initialised and {@code JRosClient.isClosed()} reports false.
   * This is a local state check and does not probe the DDS network.
   */
  public boolean isConnected() {
    return client != null && !client.isClosed();
  }

  JRos2ClientConfiguration buildClientConfiguration() throws IOException {
    RtpsTalkConfiguration.Builder builder = new RtpsTalkConfiguration.Builder();

    Integer rosDomainId = config.rosDomainId();
    if (rosDomainId != null) {
      if (rosDomainId < 0) {
        throw new IOException("ros_domain_id must be >= 0");
      }
      builder.domainId(rosDomainId);
    }

    String networkInterfaceValue = this.config.networkInterface();
    if (networkInterfaceValue != null) {
      try {
        NetworkInterface networkInterface = NetworkInterface.getByName(networkInterfaceValue);

        if (networkInterface == null) {
          InetAddress inetAddress = InetAddress.getByName(networkInterfaceValue);
          networkInterface = NetworkInterface.getByInetAddress(inetAddress);
        }

        if (networkInterface == null) {
          throw new IOException("network_interface not found: " + networkInterfaceValue);
        }

        builder.networkInterface(networkInterface);
      } catch (Exception e) {
        throw new IOException("network_interface not found: " + networkInterfaceValue);
      }
    }

    return new JRos2ClientConfiguration(builder.build());
  }

  public void registerPublisher(RosPushBinding binding) throws IOException {
    ensureConnected();
    Class<? extends Message> messageClass = resolveMessageClass(binding.rosPackage(), binding.rosType());
    PublisherQos qos = publisherQos(binding.rosQosProfile());
    try {
      TopicSubmissionPublisher<? extends Message> publisher =
          new TopicSubmissionPublisher<>(messageClass, binding.rosTopic());
      client.publish(qos, publisher);
      publishers.put(binding.rosTopic(), new PublisherHolder(messageClass, publisher));
      logger.log(RosLogMessages.ROS_QOS_APPLIED, binding.rosTopic() + " qos=" + qosLabel(binding.rosQosProfile()));
    } catch (JRosClientException e) {
      throw new IOException("Failed to register ROS publisher for " + binding.rosTopic(), e);
    }
  }

  @SuppressWarnings("unchecked")
  public void subscribe(RosPullBinding binding, RosMessageListener listener) throws IOException {
    ensureConnected();
    Class<? extends Message> messageClass = resolveMessageClass(binding.rosPackage(), binding.rosType());
    SubscriberQos qos = subscriberQos(binding.rosQosProfile());
    try {
      client.subscribe(qos, buildSubscriber((Class<Message>) messageClass, binding, listener));
      logger.log(RosLogMessages.ROS_QOS_APPLIED, binding.rosTopic() + " qos=" + qosLabel(binding.rosQosProfile()));
    } catch (JRosClientException e) {
      throw new IOException("Failed to subscribe to ROS topic " + binding.rosTopic(), e);
    }
  }

  /**
   * Builds the Flow.Subscriber for a pull binding. Package-private so that unit tests can drive it
   * directly without requiring a live ROS2 connection.
   *
   * <p>{@code TopicSubscriber.onSubscribe()} calls {@code subscription.request(initNumOfMessages)}
   * (default 1) and {@code TopicSubscriber.onNext()} only updates a telemetry counter — it does
   * NOT re-issue demand. Without the explicit {@code getSubscription().ifPresent(s -> s.request(1))}
   * call in {@code onNext()}, demand drops to 0 after the first delivery and the DDS publisher
   * stops sending further messages.
   */
  <M extends Message> TopicSubscriber<M> buildSubscriber(
      Class<M> messageClass, RosPullBinding binding, RosMessageListener listener) {
    return new TopicSubscriber<>(messageClass, binding.rosTopic()) {
      @Override
      public void onNext(M message) {
        byte[] payload = config.payloadFormat() == RosBridgeConfig.PayloadFormat.JSON
            ? gson.toJson(message).getBytes(StandardCharsets.UTF_8)
            : serializer.write(message);
        RosMessageEnvelope envelope = new RosMessageEnvelope(
            binding.rosTopic(),
            binding.rosVersion(),
            binding.rosPackage(),
            binding.rosType(),
            null, null, null,
            RosSchemaConvention.schemaId(binding.rosVersion(), binding.rosPackage(), binding.rosType()),
            payload);
        try {
          listener.onMessage(envelope);
        } catch (IOException e) {
          logger.log(RosLogMessages.ROS_INBOUND_ERROR, binding.rosTopic() + " reason=" + e.getMessage());
        }
        super.onNext(message); // update jrosclient received-messages telemetry counter
        // Re-issue demand for the next message. TopicSubscriber.onSubscribe() calls request(1)
        // once; without this call the subscription demand stays at 0 after the first delivery
        // and no further messages are received from the DDS publisher.
        getSubscription().ifPresent(s -> s.request(1));
      }

      @Override
      public void onError(Throwable throwable) {
        logger.log(RosLogMessages.ROS_INBOUND_ERROR, binding.rosTopic() + " reason=" + throwable.getMessage());
      }
    };
  }

  public void publish(String topic, RosMessageEnvelope envelope) throws IOException {
    ensureConnected();
    PublisherHolder holder = publishers.get(topic);
    if (holder == null) {
      throw new IOException("Publisher not registered for topic: " + topic);
    }
    try {
      Message message = config.payloadFormat() == RosBridgeConfig.PayloadFormat.JSON
          ? gson.fromJson(new String(envelope.payload(), StandardCharsets.UTF_8), holder.messageClass)
          : serializer.read(envelope.payload(), holder.messageClass);
      @SuppressWarnings("unchecked")
      TopicSubmissionPublisher<Message> publisher = (TopicSubmissionPublisher<Message>) holder.publisher;
      publisher.submit(message);
    } catch (Exception e) {
      throw new IOException("Failed to publish ROS2 message to topic " + topic, e);
    }
  }

  public void close() {
    for (PublisherHolder holder : publishers.values()) {
      try {
        holder.publisher.close();
      } catch (Exception ignored) {
      }
    }
    publishers.clear();
    if (client != null) {
      client.close();
      client = null;
    }
  }

  private void ensureConnected() throws IOException {
    if (!isConnected()) {
      throw new IOException("jros2client is not connected");
    }
  }

  private Class<? extends Message> resolveMessageClass(String rosPackage, String rosType) throws IOException {
    return Ros2MessageTypeRegistry.resolve(rosPackage, rosType);
  }

  // ---------------------------------------------------------------------------
  // QoS helpers
  // ---------------------------------------------------------------------------

  /**
   * Maps a {@code ros_qos} profile name from link configuration to a {@link PublisherQos}.
   * Unrecognised and null values fall back to the jros2client default.
   */
  static PublisherQos publisherQos(String profile) {
    if (profile == null) {
      return PublisherQos.DEFAULT_PUBLISHER_QOS;
    }
    return switch (profile.trim().toLowerCase()) {
      case "sensor_data" -> new PublisherQos(QosReliability.BEST_EFFORT);
      case "reliable" -> new PublisherQos(QosReliability.RELIABLE);
      case "transient_local" -> new PublisherQos(QosDurability.TRANSIENT_LOCAL);
      default -> PublisherQos.DEFAULT_PUBLISHER_QOS;
    };
  }

  /**
   * Maps a {@code ros_qos} profile name from link configuration to a {@link SubscriberQos}.
   * Unrecognised and null values fall back to the jros2client default.
   */
  static SubscriberQos subscriberQos(String profile) {
    if (profile == null) {
      return SubscriberQos.DEFAULT_SUBSCRIBER_QOS;
    }
    return switch (profile.trim().toLowerCase()) {
      case "sensor_data" -> new SubscriberQos(QosReliability.BEST_EFFORT);
      case "reliable" -> new SubscriberQos(QosReliability.RELIABLE);
      case "transient_local" -> new SubscriberQos(QosDurability.TRANSIENT_LOCAL);
      default -> SubscriberQos.DEFAULT_SUBSCRIBER_QOS;
    };
  }

  private static String qosLabel(String profile) {
    return profile == null ? "default" : profile;
  }

  private record PublisherHolder(
      Class<? extends Message> messageClass,
      TopicSubmissionPublisher<? extends Message> publisher) {
  }
}