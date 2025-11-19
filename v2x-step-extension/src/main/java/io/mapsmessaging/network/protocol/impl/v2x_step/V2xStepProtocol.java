package io.mapsmessaging.network.protocol.impl.v2x_step;

import io.mapsmessaging.api.message.Message;
import io.mapsmessaging.dto.rest.config.protocol.impl.ExtensionConfigDTO;
import io.mapsmessaging.logging.Logger;
import io.mapsmessaging.logging.LoggerFactory;
import io.mapsmessaging.network.io.EndPoint;
import io.mapsmessaging.network.protocol.impl.extension.Extension;
import java.io.IOException;
import java.util.Map;

import com.vodafone.v2xsdk4javav2.facade.V2XSDK;
import com.vodafone.v2xsdk4javav2.facade.SDKConfiguration;
import com.vodafone.v2xsdk4javav2.facade.enums.StepInstance;
import com.vodafone.v2xsdk4javav2.facade.enums.StationType;
import com.vodafone.v2xsdk4javav2.facade.enums.ServiceMode;
import com.vodafone.v2xsdk4javav2.facade.enums.LogLevel;
import com.vodafone.v2xsdk4javav2.facade.enums.V2XServiceState;

/**
 * Stub for V2X STEP protocol implementation.
 * TODO: integrate with actual STEP client library.
 */
public class V2xStepProtocol extends Extension {

  private final Logger logger;
  private final ExtensionConfigDTO protocolConfig;
  private V2XSDK sdk;
  private FakeLocationProvider locationProvider;
  private boolean camEnabled;
  private boolean denmEnabled;

  public V2xStepProtocol(EndPoint endPoint, ExtensionConfigDTO protocolConfigDTO) {
    super();
    this.protocolConfig = protocolConfigDTO;
    this.logger = LoggerFactory.getLogger(V2xStepProtocol.class);
  }

  @Override
  public void initialise() throws IOException {
    Map<String, Object> cfg = protocolConfig.getConfig();
    try {
      String appId = cfg.get("applicationId").toString();
      String appToken = cfg.get("applicationToken").toString();
      String instance = cfg.getOrDefault("stepInstance", "DE_DEV_FRANKFURT").toString();
      StepInstance stepInst = StepInstance.valueOf(instance);
      double lat = Double.parseDouble(cfg.get("testLatitude").toString());
      double lon = Double.parseDouble(cfg.get("testLongitude").toString());
      camEnabled = Boolean.parseBoolean(cfg.getOrDefault("camServiceEnabled", false).toString());
      denmEnabled = Boolean.parseBoolean(cfg.getOrDefault("denmServiceEnabled", false).toString());
      locationProvider = new FakeLocationProvider(lat, lon);
      SDKConfiguration.SDKConfigurationBuilder builder = SDKConfiguration.builder()
          .stepInstance(stepInst)
          .applicationID(appId)
          .applicationToken(appToken)
          .mqttClientID("maps-" + java.util.UUID.randomUUID())
          .stationType(StationType.PASSENGER_CAR);
      if (camEnabled) {
        builder.camServiceMode(ServiceMode.TxAndRx)
            .camPublishGroup(cfg.get("camPublishGroup").toString())
            .camSubscribeGroup(cfg.get("camSubscribeGroup").toString());
      }
      if (denmEnabled) {
        builder.denmServiceMode(ServiceMode.TxAndRx)
            .denmPublishGroup(cfg.get("denmPublishGroup").toString())
            .denmSubscribeGroup(cfg.get("denmSubscribeGroup").toString());
      }
      SDKConfiguration sdkConfig = builder.build();
      locationProvider.turnOn();
      sdk = new V2XSDK(locationProvider, sdkConfig);
      sdk.setSDKLogLevel(LogLevel.INFO);
      sdk.startV2XService();
      int retries = 0;
      while (sdk.getV2XServiceState() != V2XServiceState.UP_AND_RUNNING && retries < 10) {
        Thread.sleep(1000);
        retries++;
      }
      if (sdk.getV2XServiceState() != V2XServiceState.UP_AND_RUNNING) {
        throw new IOException("V2X service did not start");
      }
      if (camEnabled) {
        sdk.startCAMService();
      }
      if (denmEnabled) {
        sdk.startDENMService();
      }
      logger.log(V2xStepLogMessages.V2X_STEP_INITIALIZED);
    } catch (Exception e) {
      throw new IOException("Error initializing V2X STEP protocol", e);
    }
  }

  @Override
  public void close() throws IOException {
    try {
      if (camEnabled) {
        sdk.stopCAMService();
      }
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
    // TODO: implement outbound STEP messaging
    logger.log(V2xStepLogMessages.V2X_STEP_MESSAGE_SENT, destination+" message="+message);
  }

  @Override
  public void registerRemoteLink(String destination, String filter) throws IOException {
    // TODO: implement remote subscription
    logger.log(V2xStepLogMessages.V2X_STEP_SUBSCRIBE_REMOTE, destination+" filter = "+filter);
  }

  @Override
  public void registerLocalLink(String destination) throws IOException {
    // TODO: implement local subscription
    logger.log(V2xStepLogMessages.V2X_STEP_SUBSCRIBE_LOCAL, destination);
  }
}