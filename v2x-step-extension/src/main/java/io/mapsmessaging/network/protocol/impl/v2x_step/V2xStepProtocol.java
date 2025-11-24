package io.mapsmessaging.network.protocol.impl.v2x_step;

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

import com.vodafone.v2xsdk4javav2.facade.V2XSDK;
import com.vodafone.v2xsdk4javav2.facade.SDKConfiguration;
import com.vodafone.v2xsdk4javav2.facade.enums.StepInstance;
import com.vodafone.v2xsdk4javav2.facade.enums.StationType;
import com.vodafone.v2xsdk4javav2.facade.enums.ServiceMode;
import com.vodafone.v2xsdk4javav2.facade.enums.LogLevel;
import com.vodafone.v2xsdk4javav2.facade.enums.V2XServiceState;

/**
 * V2X STEP protocol extension for routing DENM messages between MapsMessaging and Vodafone STEP.
 * This extension extracts DENM parameters from incoming message payloads and triggers
 * DENM events via the STEP SDK.
 */
public class V2xStepProtocol extends Extension {
    static{
        System.out.println("##### v2xStep Protocol class loaded");
    }

  private final Logger logger;
  private final ExtensionConfigDTO protocolConfig;
  private final EndPointURL url;
  private V2XSDK sdk;
  private V2xStepSdkAdapter sdkAdapter;
  private PayloadFieldExtractor fieldExtractor;
  private FakeLocationProvider locationProvider;
  private boolean denmEnabled;
  private String denmPublishGroup;

  // Map of remote_namespace -> PushBinding for outbound routing
  private final Map<String, PushBinding> pushBindings;

  public V2xStepProtocol(EndPoint endPoint, ExtensionConfigDTO protocolConfigDTO) {
    super();
    this.protocolConfig = protocolConfigDTO;
    this.url = new EndPointURL(endPoint.getConfig().getUrl());
    this.logger = LoggerFactory.getLogger(V2xStepProtocol.class);
    this.pushBindings = new ConcurrentHashMap<>();
    this.fieldExtractor = new PayloadFieldExtractor(logger);

    // DEBUG: Log the received configuration
    System.out.println("##### V2xStepProtocol constructor called");
    System.out.println("##### URL: " + endPoint.getConfig().getUrl());
    if (protocolConfigDTO != null && protocolConfigDTO.getConfig() != null) {
      System.out.println("##### Config received: " + protocolConfigDTO.getConfig().keySet());
    } else {
      System.out.println("##### Config is NULL!");
    }
  }

  /**
   * Package-private constructor for testing that accepts URL string and config map directly.
   */
  V2xStepProtocol(String urlString, Map<String, Object> configMap) {
    super();
    // Create a minimal config DTO wrapper for testing
    this.protocolConfig = new ExtensionConfigDTO() {
      @Override
      public Map<String, Object> getConfig() {
        return configMap;
      }
    };
    this.url = new EndPointURL(urlString);
    this.logger = LoggerFactory.getLogger(V2xStepProtocol.class);
    this.pushBindings = new ConcurrentHashMap<>();
    this.fieldExtractor = new PayloadFieldExtractor(logger);

    // Initialize DENM service configuration for testing
    if (configMap.containsKey("denmService")) {
      Map<String, Object> denmConfig = (Map<String, Object>) configMap.get("denmService");
      this.denmEnabled = Boolean.parseBoolean(denmConfig.getOrDefault("enabled", false).toString());
      if (this.denmEnabled && denmConfig.containsKey("publishGroup")) {
        this.denmPublishGroup = denmConfig.get("publishGroup").toString();
        logger.log(V2xStepLogMessages.V2X_STEP_INITIALIZED,"DENM Group set to "+denmPublishGroup);
      }
    }
  }

  @Override
  public void initialise() throws IOException {
    System.out.println("##### V2xStepProtocol.initialise() called");
    Map<String, Object> cfg = protocolConfig.getConfig();
    System.out.println("##### Configuration map: " + cfg);
    try {
      // Parse STEP instance from URL (e.g., step://DE_DEV_FRANKFURT)
      String instanceName = url.getHost();
      System.out.println("##### Parsed STEP instance: " + instanceName);
      if (instanceName == null || instanceName.isEmpty()) {
        throw new IOException("STEP instance not specified in URL. Use format: step://INSTANCE_NAME (e.g., step://DE_DEV_FRANKFURT)");
      }
      StepInstance stepInst = StepInstance.valueOf(instanceName);

      String appId = cfg.get("applicationId").toString();
      String appToken = cfg.get("applicationToken").toString();

      // Parse DENM service configuration
      denmEnabled = false;
      if (cfg.containsKey("denmService")) {
        Map<String, Object> denmConfig = (Map<String, Object>) cfg.get("denmService");
        denmEnabled = Boolean.parseBoolean(denmConfig.getOrDefault("enabled", false).toString());
        if (denmEnabled) {
          denmPublishGroup = denmConfig.get("publishGroup").toString();
        }
      }

      if (!denmEnabled) {
        throw new IOException("DENM service must be enabled for V2X STEP extension");
      }

      // Station type placeholder (not used for DENM trigger)
      StationType defaultStationType = StationType.UNKNOWN;

      // Initialize location provider (required by SDK but not used for DENM trigger)
      locationProvider = new FakeLocationProvider(0.0, 0.0);

      // Build SDK configuration
      Map<String, Object> denmConfig = (Map<String, Object>) cfg.get("denmService");
      SDKConfiguration.SDKConfigurationBuilder builder = SDKConfiguration.builder()
          .stepInstance(stepInst)
          .applicationID(appId)
          .applicationToken(appToken)
          .mqttClientID("maps-" + java.util.UUID.randomUUID())
          .stationType(defaultStationType)
          .denmServiceMode(ServiceMode.TxAndRx)
          .denmPublishGroup(denmPublishGroup)
          .denmSubscribeGroup(denmConfig.get("subscribeGroup").toString());

      SDKConfiguration sdkConfig = builder.build();
      locationProvider.turnOn();
      sdk = new V2XSDK(locationProvider, sdkConfig);
      sdkAdapter = new V2xStepSdkAdapterImpl(sdk);
      sdk.setSDKLogLevel(LogLevel.INFO);
      sdk.startV2XService();

      // Wait for V2X service to come up
      int retries = 0;
      while (sdk.getV2XServiceState() != V2XServiceState.UP_AND_RUNNING && retries < 10) {
        Thread.sleep(1000);
        retries++;
      }
      if (sdk.getV2XServiceState() != V2XServiceState.UP_AND_RUNNING) {
        throw new IOException("V2X service did not start");
      }

      sdk.startDENMService();
      logger.log(V2xStepLogMessages.V2X_STEP_INITIALIZED);
    } catch (Exception e) {
      throw new IOException("Error initializing V2X STEP protocol", e);
    }
  }

  @Override
  public void close() throws IOException {
    try {
      if (denmEnabled) {
        sdk.stopDENMService();
      }
      sdk.stopV2XService();
      if (locationProvider != null) {
        locationProvider.turnOff();
      }
      logger.log(V2xStepLogMessages.V2X_STEP_CLOSED);
    } catch (Exception e) {
      logger.log(V2xStepLogMessages.V2X_STEP_CLOSED, e);
    }
    super.close();
  }

  @Override
  public String getName() {
    return "v2x-step";
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
    // Resolve the push binding for this destination
    PushBinding binding = pushBindings.get(destination);
    if (binding == null) {
      logger.log(V2xStepLogMessages.V2X_STEP_DESTINATION_NOT_REGISTERED, destination);
      return; // No-op for unregistered destinations
    }

    try {
      // Extract DENM parameters from message using field mapping
      DenmParameters params = fieldExtractor.extractDenmParameters(message, binding.getFieldMapping());

      // Trigger DENM via SDK adapter
      long sequenceNumber = sdkAdapter.triggerDenm(params);

      logger.log(V2xStepLogMessages.V2X_STEP_OUTBOUND_SUCCESS,
          binding.getServiceType(), binding.getPublishGroup(), sequenceNumber);

    } catch (IOException e) {
      logger.log(V2xStepLogMessages.V2X_STEP_OUTBOUND_ERROR, destination, e.getMessage());
    }
  }

  @Override
  public void registerRemoteLink(String destination, String filter) throws IOException {
    // TODO: implement DENM subscription (pull links)
    logger.log(V2xStepLogMessages.V2X_STEP_SUBSCRIBE_REMOTE, destination + " filter = " + filter);
  }

  @Override
  public void registerLocalLink(String destination) throws IOException {
    System.out.println("##### V2xStepProtocol.registerLocalLink() called for destination: " + destination);
    // Find the link configuration for this destination
    Map<String, Object> linkAttrs = findLinkAttributes(destination, "push");
    System.out.println("##### Found link attributes: " + linkAttrs);
    if (linkAttrs == null) {
      System.out.println("##### ERROR: No push link configuration found for destination: " + destination);
      throw new IOException("No push link configuration found for destination: " + destination);
    }

    // Extract service_type attribute
    Object serviceTypeObj = linkAttrs.get("service_type");
    if (serviceTypeObj == null) {
      throw new IOException("Missing service_type attribute for push link: " + destination);
    }

    StepServiceType serviceType;
    try {
      serviceType = StepServiceType.fromString(serviceTypeObj.toString());
    } catch (IllegalArgumentException e) {
      throw new IOException("Invalid service_type '" + serviceTypeObj + "' for push link: " + destination, e);
    }

    // Validate that DENM service is enabled
    if (serviceType == StepServiceType.DENM && !denmEnabled) {
      throw new IOException("DENM service is not enabled but push link requires it: " + destination);
    }

    // Get publish group (with optional per-link override)
    String publishGroup = denmPublishGroup;
    Object perLinkPublishGroup = linkAttrs.get("publish_group");
    if (perLinkPublishGroup != null && !perLinkPublishGroup.toString().trim().isEmpty()) {
      publishGroup = perLinkPublishGroup.toString();
    }

    // Validate publish group is configured
    if (publishGroup == null || publishGroup.trim().isEmpty()) {
      throw new IOException("No publish group configured for " + serviceType + " service");
    }

    // Parse field mapping configuration (or use defaults)
    DenmFieldMapping fieldMapping;
    Object fieldMappingsObj = linkAttrs.get("field_mappings");
    if (fieldMappingsObj instanceof Map) {
      fieldMapping = DenmFieldMapping.fromConfig((Map<String, Object>) fieldMappingsObj);
    } else {
      fieldMapping = DenmFieldMapping.createDefault();
    }

    // Store the push binding
    PushBinding binding = new PushBinding(serviceType, publishGroup, fieldMapping);
    pushBindings.put(destination, binding);

    System.out.println("##### Successfully registered push binding: " + destination + " -> " + binding);
    logger.log(V2xStepLogMessages.V2X_STEP_SUBSCRIBE_LOCAL, destination + " -> " + binding);
  }

  /**
   * Find link attributes by remote namespace and direction.
   * This helper method searches through the link configurations to find
   * custom attributes like service_type, publish_group, and field_mappings.
   *
   * @param remoteNamespace The remote namespace (destination)
   * @param direction The link direction ("push" or "pull")
   * @return Map of link attributes, or null if not found
   */
  private Map<String, Object> findLinkAttributes(String remoteNamespace, String direction) {
    Map<String, Object> cfg = protocolConfig.getConfig();

    // Links might be stored in the config under a "links" key
    Object linksObj = cfg.get("links");
    if (linksObj instanceof List) {
      List<Map<String, Object>> links = (List<Map<String, Object>>) linksObj;
      for (Map<String, Object> link : links) {
        String linkDirection = (String) link.get("direction");
        String linkRemoteNs = (String) link.get("remote_namespace");

        if (direction.equalsIgnoreCase(linkDirection) &&
            remoteNamespace.equals(linkRemoteNs)) {
          return link;
        }
      }
    }

    return null;
  }

  /**
   * Set the SDK adapter (for testing purposes).
   * Package-private to allow unit tests to inject a mock adapter.
   *
   * @param adapter The SDK adapter to use
   */
  void setSdkAdapter(V2xStepSdkAdapter adapter) {
    this.sdkAdapter = adapter;
  }
}
