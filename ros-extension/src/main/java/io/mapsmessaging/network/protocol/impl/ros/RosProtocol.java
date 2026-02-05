package io.mapsmessaging.network.protocol.impl.ros;

import io.mapsmessaging.api.message.Message;
import io.mapsmessaging.dto.rest.config.protocol.impl.ExtensionConfigDTO;
import io.mapsmessaging.logging.Logger;
import io.mapsmessaging.logging.LoggerFactory;
import io.mapsmessaging.network.EndPointURL;
import io.mapsmessaging.network.io.EndPoint;
import io.mapsmessaging.network.protocol.impl.extension.Extension;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RosProtocol extends Extension {

  private final Logger logger;
  private final EndPointURL url;
  private final ExtensionConfigDTO protocolConfig;
  private final RosMessageTranslator translator;

  private final Map<String, RosPushBinding> pushBindings;
  private final Map<String, RosPullBinding> pullBindings;

  private RosBridgeConfig bridgeConfig;
  private RosClientAdapter rosClientAdapter;

  public RosProtocol(EndPoint endPoint, ExtensionConfigDTO protocolConfigDTO) {
    this.logger = LoggerFactory.getLogger(RosProtocol.class);
    this.url = new EndPointURL(endPoint.getConfig().getUrl());
    this.protocolConfig = protocolConfigDTO;
    this.translator = new RosMessageTranslator();
    this.pushBindings = new ConcurrentHashMap<>();
    this.pullBindings = new ConcurrentHashMap<>();
  }

  @Override
  public void initialise() throws IOException {
    try {
      Map<String, Object> cfg = protocolConfig.getConfig();
      this.bridgeConfig = RosBridgeConfig.fromMap(cfg);
      this.rosClientAdapter = RosClientAdapterFactory.create(logger, bridgeConfig);
      this.rosClientAdapter.connect();
      logger.log(RosLogMessages.ROS_INITIALIZED,
          "version=" + bridgeConfig.getRosVersion() + " endpoint=" + resolvedEndpoint());
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
  public String getName() {
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
  public void outbound(String destination, Message message) {
    if (destination.startsWith("$schema/") || destination.startsWith("$SCHEMA/")) {
      return;
    }

    RosPushBinding binding = pushBindings.get(destination);
    if (binding == null) {
      logger.log(RosLogMessages.ROS_OUTBOUND_ERROR, destination);
      return;
    }

    try {
      RosMessageEnvelope envelope = translator.toRosEnvelope(message, binding, bridgeConfig);
      rosClientAdapter.publish(binding.getRosTopic(), envelope);
      logger.log(RosLogMessages.ROS_MESSAGE_SENT, binding.getRosTopic());
    } catch (Exception e) {
      logger.log(RosLogMessages.ROS_OUTBOUND_ERROR, destination + " reason=" + e.getMessage());
    }
  }

  @Override
  public void registerRemoteLink(String destination, String filter) throws IOException {
    Map<String, Object> attrs = findLinkAttributes(destination, "pull");
    if (attrs == null) {
      attrs = protocolConfig.getConfig();
    }

    RosPullBinding binding = buildPullBinding(destination, attrs);
    pullBindings.put(destination, binding);

    rosClientAdapter.subscribe(binding.getRosTopic(), binding.getRosType(), envelope -> {
      try {
        Message message = translator.toMapsMessage(envelope);
        inbound(binding.getLocalNamespace(), message);
        logger.log(RosLogMessages.ROS_MESSAGE_RECEIVED, binding.getRosTopic());
      } catch (Exception e) {
        logger.log(RosLogMessages.ROS_INBOUND_ERROR,
            binding.getLocalNamespace() + " reason=" + e.getMessage());
      }
    });

    logger.log(RosLogMessages.ROS_REGISTER_REMOTE,
        binding.getRosTopic() + " -> " + binding.getLocalNamespace());
  }

  @Override
  public void registerLocalLink(String destination) throws IOException {
    Map<String, Object> attrs = findLinkAttributes(destination, "push");
    if (attrs == null) {
      attrs = protocolConfig.getConfig();
    }

    RosPushBinding binding = buildPushBinding(destination, attrs);
    pushBindings.put(binding.getLocalNamespace(), binding);

    rosClientAdapter.registerPublisher(binding.getRosTopic(), binding.getRosType());

    logger.log(RosLogMessages.ROS_REGISTER_LOCAL,
        binding.getLocalNamespace() + " -> " + binding.getRosTopic());
  }

  private String resolvedEndpoint() {
    String endpoint = bridgeConfig.getRosEndpoint();
    if (endpoint != null && !endpoint.trim().isEmpty()) {
      return endpoint;
    }
    return url.toString();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> findLinkAttributes(String remoteNamespace, String direction) {
    Map<String, Object> cfg = protocolConfig.getConfig();
    Object linksObj = cfg.get("links");
    if (!(linksObj instanceof List)) {
      return null;
    }

    for (Map<String, Object> link : (List<Map<String, Object>>) linksObj) {
      String linkDirection = asString(link.get("direction"));
      String linkRemoteNs = asString(link.get("remote_namespace"));
      if (direction.equalsIgnoreCase(linkDirection) && remoteNamespace.equals(linkRemoteNs)) {
        return link;
      }
    }
    return null;
  }

  private RosPushBinding buildPushBinding(String destination, Map<String, Object> attrs) throws IOException {
    String localNamespace = asString(attrs.get("local_namespace"));
    if (localNamespace == null || localNamespace.isEmpty()) {
      localNamespace = destination;
    }

    String rosTopic = asString(attrs.get("ros_topic"));
    if (rosTopic == null || rosTopic.isEmpty()) {
      rosTopic = destination;
    }

    String rosVersion = firstNonBlank(asString(attrs.get("ros_version")), bridgeConfig.getRosVersion().name());
    String rosPackage = firstNonBlank(asString(attrs.get("ros_package")), "unknown");
    String rosType = asString(attrs.get("ros_type"));
    if (bridgeConfig.getSchemaMode() == RosBridgeConfig.SchemaMode.STRICT
        && (rosType == null || rosType.trim().isEmpty())) {
      throw new IOException("ros_type is required in strict schema mode for push link: " + destination);
    }
    rosType = firstNonBlank(rosType, "unknown");

    return new RosPushBinding(localNamespace, rosTopic, rosVersion, rosPackage, rosType);
  }

  private RosPullBinding buildPullBinding(String destination, Map<String, Object> attrs) throws IOException {
    String localNamespace = asString(attrs.get("local_namespace"));
    if (localNamespace == null || localNamespace.isEmpty()) {
      throw new IOException("local_namespace is required for pull link: " + destination);
    }

    String rosTopic = asString(attrs.get("ros_topic"));
    if (rosTopic == null || rosTopic.isEmpty()) {
      rosTopic = destination;
    }

    String rosVersion = firstNonBlank(asString(attrs.get("ros_version")), bridgeConfig.getRosVersion().name());
    String rosPackage = firstNonBlank(asString(attrs.get("ros_package")), "unknown");
    String rosType = asString(attrs.get("ros_type"));
    if (bridgeConfig.getSchemaMode() == RosBridgeConfig.SchemaMode.STRICT
        && (rosType == null || rosType.trim().isEmpty())) {
      throw new IOException("ros_type is required in strict schema mode for pull link: " + destination);
    }
    rosType = firstNonBlank(rosType, "unknown");

    return new RosPullBinding(localNamespace, rosTopic, rosVersion, rosPackage, rosType);
  }

  private String asString(Object value) {
    return value == null ? null : value.toString();
  }

  private String firstNonBlank(String first, String fallback) {
    if (first != null && !first.trim().isEmpty()) {
      return first;
    }
    return fallback;
  }
}
