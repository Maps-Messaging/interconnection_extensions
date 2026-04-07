package io.mapsmessaging.network.protocol.impl.ros;

import io.mapsmessaging.api.message.Message;
import io.mapsmessaging.dto.rest.config.protocol.impl.ExtensionConfigDTO;
import io.mapsmessaging.logging.Logger;
import io.mapsmessaging.logging.LoggerFactory;
import io.mapsmessaging.network.protocol.impl.extension.Extension;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MAPS protocol extension which bridges configured MAPS links to ROS2 topics.
 *
 * <p>This class is intentionally a protocol adapter, not a Nav2 controller:
 * it keeps ROS2 payloads as ROS CDR bytes in MAPS messages and delegates any
 * JSON projection or higher-level navigation behavior to MAPS transformations
 * or ROS/Nav2 nodes.
 */
public class RosProtocol extends Extension {

  private final Logger logger;
  private final ExtensionConfigDTO protocolConfig;
  private final RosMessageTranslator translator;

  private final Map<String, RosPushBinding> pushBindings;

  private RosBridgeConfig bridgeConfig;
  private JRos2ClientAdapter rosClientAdapter;

  public RosProtocol(@NonNull ExtensionConfigDTO protocolConfigDTO) {
    this.logger = LoggerFactory.getLogger(RosProtocol.class);
    this.protocolConfig = protocolConfigDTO;
    this.translator = new RosMessageTranslator(LoggerFactory.getLogger(RosMessageTranslator.class));
    this.pushBindings = new ConcurrentHashMap<>();
  }

  @Override
  public void initialise() throws IOException {
    try {
      Map<String, Object> cfg = protocolConfig.getConfig();
      this.bridgeConfig = RosBridgeConfig.fromMap(cfg);
      this.rosClientAdapter = new JRos2ClientAdapter(logger, bridgeConfig);
      this.rosClientAdapter.connect();
      logger.log(RosLogMessages.ROS_INITIALIZED, "version=" + bridgeConfig.rosVersion());
    } catch (Exception e) {
      logger.log(RosLogMessages.ROS_INITIALIZE_ERROR, e);
      throw new IOException("Failed to initialize ROS protocol", e);
    }
  }

  @Override
  public void close() throws IOException {
    try {
      if (rosClientAdapter != null) {
        rosClientAdapter.close();
      }
      logger.log(RosLogMessages.ROS_CLOSED);
    } catch (Exception e) {
      logger.log(RosLogMessages.ROS_CLOSE_ERROR, e);
    }
    super.close();
  }

  @Override
  public @NotNull String getName() {
    return "ros";
  }

  @Override
  public String getVersion() {
    return "1.0";
  }

  @Override
  public boolean supportsRemoteFiltering() {
    return false;
  }

  @Override
  public void outbound(String destination, @NotNull Message message) {
    if (destination.startsWith("$schema/") || destination.startsWith("$SCHEMA/")) {
      return;
    }

    if (!rosClientAdapter.isConnected()) {
      logger.log(RosLogMessages.ROS_OUTBOUND_ERROR, destination + " reason=adapter not connected");
      return;
    }

    RosPushBinding binding = pushBindings.get(destination);
    if (binding == null) {
      logger.log(RosLogMessages.ROS_OUTBOUND_ERROR, destination);
      return;
    }

    try {
      RosMessageEnvelope envelope = translator.toRosEnvelope(message, binding, bridgeConfig);
      rosClientAdapter.publish(binding.rosTopic(), envelope);
      logger.log(RosLogMessages.ROS_MESSAGE_SENT, binding.rosTopic());
    } catch (Exception e) {
      logger.log(RosLogMessages.ROS_OUTBOUND_ERROR, destination + " reason=" + e.getMessage());
    }
  }

  @Override
  public void registerRemoteLink(@NotNull String destination, String filter) throws IOException {
    if (isSchemaDestination(destination)) {
      return;
    }
    Map<String, Object> attrs = findLinkAttributes(destination, "pull");
    if (attrs == null) {
      attrs = protocolConfig.getConfig();
    }

    RosPullBinding binding = buildPullBinding(destination, attrs, bridgeConfig);

    rosClientAdapter.subscribe(binding, envelope -> {
      try {
        Message message = translator.toMapsMessage(envelope);
        inbound(binding.localNamespace(), message);
        logger.log(RosLogMessages.ROS_MESSAGE_RECEIVED, binding.rosTopic());
      } catch (Exception e) {
        logger.log(RosLogMessages.ROS_INBOUND_ERROR,
            binding.localNamespace() + " reason=" + e.getMessage());
      }
    });

    logger.log(RosLogMessages.ROS_REGISTER_REMOTE,
        binding.rosTopic() + " -> " + binding.localNamespace());
  }

  @Override
  public void registerLocalLink(@NotNull String destination) throws IOException {
    if (isSchemaDestination(destination)) {
      return;
    }
    Map<String, Object> attrs = findLinkAttributes(destination, "push");
    if (attrs == null) {
      attrs = protocolConfig.getConfig();
    }

    RosPushBinding binding = buildPushBinding(destination, attrs, bridgeConfig);
    pushBindings.put(binding.localNamespace(), binding);

    rosClientAdapter.registerPublisher(binding);

    logger.log(RosLogMessages.ROS_REGISTER_LOCAL,
        binding.localNamespace() + " -> " + binding.rosTopic());
  }

  private boolean isSchemaDestination(String destination) {
    return destination.startsWith("$schema/") || destination.startsWith("$SCHEMA/");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> findLinkAttributes(String destination, String direction) {
    Map<String, Object> cfg = protocolConfig.getConfig();
    Object linksObj = cfg.get("links");
    if (!(linksObj instanceof List)) {
      return null;
    }

    for (Map<String, Object> link : (List<Map<String, Object>>) linksObj) {
      String linkDirection = asString(link.get("direction"));
      if (!direction.equalsIgnoreCase(linkDirection)) {
        continue;
      }
      if (matchesLinkDestination(destination, link)) {
        return link;
      }
    }
    return null;
  }

  private boolean matchesLinkDestination(String destination, Map<String, Object> link) {
    return destination.equals(asString(link.get("remote_namespace")))
        || destination.equals(asString(link.get("local_namespace")))
        || destination.equals(asString(link.get("ros_topic")));
  }

  // Package-private and static so tests can exercise binding validation directly.

  static RosPushBinding buildPushBinding(String destination, Map<String, Object> attrs, RosBridgeConfig bridgeConfig) throws IOException {
    String localNamespace = asString(attrs.get("local_namespace"));
    if (localNamespace == null || localNamespace.isEmpty()) {
      localNamespace = destination;
    }

    String rosTopic = asString(attrs.get("ros_topic"));
    if (rosTopic == null || rosTopic.isEmpty()) {
      rosTopic = destination;
    }

    String rosVersion = normalizeRosVersion(
        firstNonBlank(asString(attrs.get("ros_version")), bridgeConfig.rosVersion().name()),
        destination,
        "push");

    String rosPackage = asString(attrs.get("ros_package"));
    String rosType = asString(attrs.get("ros_type"));

    if (bridgeConfig.schemaMode() == RosBridgeConfig.SchemaMode.STRICT) {
      if (rosPackage == null || rosPackage.trim().isEmpty()) {
        throw new IOException("ros_package is required in strict schema mode for push link: " + destination);
      }
      if (rosType == null || rosType.trim().isEmpty()) {
        throw new IOException("ros_type is required in strict schema mode for push link: " + destination);
      }
    }

    rosPackage = firstNonBlank(rosPackage, "unknown");
    rosType = firstNonBlank(rosType, "unknown");

    String rosQosProfile = asString(attrs.get("ros_qos"));

    return new RosPushBinding(localNamespace, rosTopic, rosVersion, rosPackage, rosType, rosQosProfile);
  }

  static RosPullBinding buildPullBinding(String destination, Map<String, Object> attrs, RosBridgeConfig bridgeConfig) throws IOException {
    String localNamespace = asString(attrs.get("local_namespace"));
    if (localNamespace == null || localNamespace.isEmpty()) {
      throw new IOException("local_namespace is required for pull link: " + destination);
    }

    String rosTopic = asString(attrs.get("ros_topic"));
    if (rosTopic == null || rosTopic.isEmpty()) {
      rosTopic = destination;
    }

    String rosVersion = normalizeRosVersion(
        firstNonBlank(asString(attrs.get("ros_version")), bridgeConfig.rosVersion().name()),
        destination,
        "pull");

    String rosPackage = asString(attrs.get("ros_package"));
    String rosType = asString(attrs.get("ros_type"));

    if (bridgeConfig.schemaMode() == RosBridgeConfig.SchemaMode.STRICT) {
      if (rosPackage == null || rosPackage.trim().isEmpty()) {
        throw new IOException("ros_package is required in strict schema mode for pull link: " + destination);
      }
      if (rosType == null || rosType.trim().isEmpty()) {
        throw new IOException("ros_type is required in strict schema mode for pull link: " + destination);
      }
    }

    rosPackage = firstNonBlank(rosPackage, "unknown");
    rosType = firstNonBlank(rosType, "unknown");

    String rosQosProfile = asString(attrs.get("ros_qos"));

    return new RosPullBinding(localNamespace, rosTopic, rosVersion, rosPackage, rosType, rosQosProfile);
  }

  private static String asString(Object value) {
    return value == null ? null : value.toString();
  }

  private static String firstNonBlank(String first, String fallback) {
    if (first != null && !first.trim().isEmpty()) {
      return first;
    }
    return fallback;
  }

  private static String normalizeRosVersion(String version, String destination, String direction) throws IOException {
    try {
      RosBridgeConfig.RosVersion.from(version);
      return "2";
    } catch (IllegalArgumentException e) {
      throw new IOException(
          "Only ROS2 is supported for " + direction + " link '" + destination + "' (ros_version=" + version + ")",
          e);
    }
  }
}
