package io.mapsmessaging.network.protocol.impl.v2x_step;

import io.mapsmessaging.api.MessageBuilder;
import io.mapsmessaging.api.message.Message;
import io.mapsmessaging.api.message.TypedData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for V2xStepProtocol outbound DENM flow.
 */
class V2xStepProtocolOutboundTest {

  private V2xStepProtocol protocol;
  private V2xStepSdkAdapter mockAdapter;

  @BeforeEach
  void setUp() throws Exception {
    // Create test config
    Map<String, Object> config = createTestConfig();

    // Create protocol instance using test-friendly constructor
    protocol = new V2xStepProtocol("step://DE_DEV_FRANKFURT/", config);

    // Create and inject mock SDK adapter
    mockAdapter = mock(V2xStepSdkAdapter.class);
    protocol.setSdkAdapter(mockAdapter);
  }

  private Map<String, Object> createTestConfig() {
    Map<String, Object> config = new HashMap<>();
    config.put("applicationId", "test-app-id");
    config.put("applicationToken", "test-token");

    // DENM service config
    Map<String, Object> denmService = new HashMap<>();
    denmService.put("enabled", true);
    denmService.put("publishGroup", "DENM_TX_GROUP");
    denmService.put("subscribeGroup", "DENM_RX_GROUP");
    config.put("denmService", denmService);

    // Links configuration
    Map<String, Object> denmLink = new HashMap<>();
    denmLink.put("direction", "push");
    denmLink.put("local_namespace", "/v2x/outbound/denm");
    denmLink.put("remote_namespace", "DENM_TX_GROUP");
    denmLink.put("service_type", "DENM");
    denmLink.put("include_schema", false);

    config.put("links", List.of(denmLink));

    return config;
  }

  @Test
  void testOutbound_DenmMessage_FromDataMap_Success() throws Exception {
    // Register DENM link
    protocol.registerLocalLink("DENM_TX_GROUP");

    // Create test message with pre-parsed properties in data map
    Map<String, TypedData> dataMap = new HashMap<>();
    dataMap.put("causeCode", createTypedData(2));
    dataMap.put("subCauseCode", createTypedData(0));
    dataMap.put("latitude", createTypedData(52.4687872));
    dataMap.put("longitude", createTypedData(-1.5787688));
    dataMap.put("validityDuration", createTypedData(40));
    dataMap.put("transmissionInterval", createTypedData(500));
    dataMap.put("detectionTime", createTypedData(559397550748L));

    Message message = createMessageWithDataMap(dataMap);

    // Mock SDK adapter to return sequence number
    when(mockAdapter.triggerDenm(any(DenmParameters.class))).thenReturn(42L);

    // Send outbound message
    protocol.outbound("DENM_TX_GROUP", message);

    // Verify triggerDenm was called with correct parameters
    ArgumentCaptor<DenmParameters> paramsCaptor = ArgumentCaptor.forClass(DenmParameters.class);
    verify(mockAdapter).triggerDenm(paramsCaptor.capture());

    DenmParameters params = paramsCaptor.getValue();
    assertEquals(2, params.getCauseCode());
    assertEquals(0, params.getSubCauseCode());
    assertEquals(52.4687872, params.getLatitude(), 0.0001);
    assertEquals(-1.5787688, params.getLongitude(), 0.0001);
    assertEquals(40, params.getValidityDuration());
    assertEquals(500, params.getTransmissionInterval());
    assertEquals(559397550748L, params.getDetectionTime());
  }

  @Test
  void testOutbound_DenmMessage_FromXmlPayload_Success() throws Exception {
    // Register DENM link
    protocol.registerLocalLink("DENM_TX_GROUP");

    // Create ETSI DENM XML payload
    String xmlPayload = "<?xml version=\"1.0\"?>\n" +
        "<denm>\n" +
        "  <denm>\n" +
        "    <management>\n" +
        "      <eventPosition>\n" +
        "        <latitude>524687872</latitude>\n" +
        "        <longitude>-15787688</longitude>\n" +
        "      </eventPosition>\n" +
        "      <validityDuration>40</validityDuration>\n" +
        "      <transmissionInterval>500</transmissionInterval>\n" +
        "      <detectionTime>559397550748</detectionTime>\n" +
        "    </management>\n" +
        "  </denm>\n" +
        "  <situation>\n" +
        "    <eventType>\n" +
        "      <causeCode>2</causeCode>\n" +
        "      <subCauseCode>0</subCauseCode>\n" +
        "    </eventType>\n" +
        "  </situation>\n" +
        "</denm>";

    Message message = createMessageWithOpaqueData(xmlPayload.getBytes());

    // Mock SDK adapter to return sequence number
    when(mockAdapter.triggerDenm(any(DenmParameters.class))).thenReturn(123L);

    // Send outbound message
    protocol.outbound("DENM_TX_GROUP", message);

    // Verify triggerDenm was called with correct parameters
    ArgumentCaptor<DenmParameters> paramsCaptor = ArgumentCaptor.forClass(DenmParameters.class);
    verify(mockAdapter).triggerDenm(paramsCaptor.capture());

    DenmParameters params = paramsCaptor.getValue();
    assertEquals(2, params.getCauseCode());
    assertEquals(0, params.getSubCauseCode());
    // Verify ETSI coordinate conversion (1/10 microdegrees to decimal)
    assertEquals(52.4687872, params.getLatitude(), 0.0001);
    assertEquals(-1.5787688, params.getLongitude(), 0.0001);
    assertEquals(40, params.getValidityDuration());
    assertEquals(500, params.getTransmissionInterval());
    assertEquals(559397550748L, params.getDetectionTime());
  }

  @Test
  void testOutbound_UnregisteredDestination_NoOp() {
    // Create test message
    Map<String, TypedData> dataMap = new HashMap<>();
    dataMap.put("causeCode", createTypedData(2));
    Message message = createMessageWithDataMap(dataMap);

    // Send to unregistered destination (no registerLocalLink called)
    protocol.outbound("UNKNOWN_GROUP", message);

    // Verify no SDK calls were made
    verifyNoInteractions(mockAdapter);
  }

  @Test
  void testOutbound_SdkError_LogsError() throws Exception {
    // Register DENM link
    protocol.registerLocalLink("DENM_TX_GROUP");

    // Create test message
    Map<String, TypedData> dataMap = new HashMap<>();
    dataMap.put("causeCode", createTypedData(2));
    dataMap.put("subCauseCode", createTypedData(0));
    dataMap.put("latitude", createTypedData(52.0));
    dataMap.put("longitude", createTypedData(-1.0));
    dataMap.put("validityDuration", createTypedData(40));
    dataMap.put("transmissionInterval", createTypedData(500));
    dataMap.put("detectionTime", createTypedData(123456789L));
    Message message = createMessageWithDataMap(dataMap);

    // Configure mock to throw exception
    when(mockAdapter.triggerDenm(any(DenmParameters.class)))
        .thenThrow(new IOException("SDK connection error"));

    // Send outbound message - should not throw, but log error
    assertDoesNotThrow(() -> protocol.outbound("DENM_TX_GROUP", message));

    // Verify trigger was attempted
    verify(mockAdapter).triggerDenm(any(DenmParameters.class));
  }

  @Test
  void testOutbound_MissingPayloadData_LogsError() throws Exception {
    // Register DENM link
    protocol.registerLocalLink("DENM_TX_GROUP");

    // Create test message with empty data
    Message message = createMessageWithOpaqueData(null);

    // Send outbound message
    protocol.outbound("DENM_TX_GROUP", message);

    // Verify no SDK calls were made (extraction failed)
    verifyNoInteractions(mockAdapter);
  }

  @Test
  void testRegisterLocalLink_MissingServiceType_ThrowsException() {
    // Create config without service_type
    Map<String, Object> config = new HashMap<>();
    config.put("applicationId", "test-app-id");
    config.put("applicationToken", "test-token");

    Map<String, Object> denmService = new HashMap<>();
    denmService.put("enabled", true);
    denmService.put("publishGroup", "DENM_TX_GROUP");
    denmService.put("subscribeGroup", "DENM_RX_GROUP");
    config.put("denmService", denmService);

    Map<String, Object> linkWithoutServiceType = new HashMap<>();
    linkWithoutServiceType.put("direction", "push");
    linkWithoutServiceType.put("local_namespace", "/v2x/outbound/denm");
    linkWithoutServiceType.put("remote_namespace", "DENM_TX_GROUP");
    // Missing service_type

    config.put("links", List.of(linkWithoutServiceType));

    V2xStepProtocol testProtocol = new V2xStepProtocol("step://DE_DEV_FRANKFURT/", config);

    // Should throw IOException for missing service_type
    IOException exception = assertThrows(IOException.class, () ->
        testProtocol.registerLocalLink("DENM_TX_GROUP")
    );
    assertTrue(exception.getMessage().contains("service_type"));
  }

  @Test
  void testRegisterLocalLink_ServiceDisabled_ThrowsException() {
    // Create config with DENM service disabled
    Map<String, Object> config = new HashMap<>();
    config.put("applicationId", "test-app-id");
    config.put("applicationToken", "test-token");

    Map<String, Object> denmService = new HashMap<>();
    denmService.put("enabled", false); // Disabled
    denmService.put("publishGroup", "DENM_TX_GROUP");
    config.put("denmService", denmService);

    Map<String, Object> denmLink = new HashMap<>();
    denmLink.put("direction", "push");
    denmLink.put("local_namespace", "/v2x/outbound/denm");
    denmLink.put("remote_namespace", "DENM_TX_GROUP");
    denmLink.put("service_type", "DENM");

    config.put("links", List.of(denmLink));

    V2xStepProtocol testProtocol = new V2xStepProtocol("step://DE_DEV_FRANKFURT/", config);

    // Should throw IOException for disabled service
    IOException exception = assertThrows(IOException.class, () ->
        testProtocol.registerLocalLink("DENM_TX_GROUP")
    );
    assertTrue(exception.getMessage().contains("not enabled"));
  }

  @Test
  void testRegisterLocalLink_PerLinkPublishGroupOverride() throws Exception {
    // Create config with per-link publish_group override
    Map<String, Object> config = new HashMap<>();
    config.put("applicationId", "test-app-id");
    config.put("applicationToken", "test-token");

    Map<String, Object> denmService = new HashMap<>();
    denmService.put("enabled", true);
    denmService.put("publishGroup", "DENM_TX_GROUP_DEFAULT");
    denmService.put("subscribeGroup", "DENM_RX_GROUP");
    config.put("denmService", denmService);

    Map<String, Object> denmLink = new HashMap<>();
    denmLink.put("direction", "push");
    denmLink.put("local_namespace", "/v2x/outbound/denm");
    denmLink.put("remote_namespace", "DENM_TX_GROUP");
    denmLink.put("service_type", "DENM");
    denmLink.put("publish_group", "DENM_TX_GROUP_OVERRIDE"); // Override

    config.put("links", List.of(denmLink));

    V2xStepProtocol testProtocol = new V2xStepProtocol("step://DE_DEV_FRANKFURT/", config);
    testProtocol.setSdkAdapter(mockAdapter);

    // Register link
    testProtocol.registerLocalLink("DENM_TX_GROUP");

    // Create and send message
    Map<String, TypedData> dataMap = new HashMap<>();
    dataMap.put("causeCode", createTypedData(2));
    dataMap.put("subCauseCode", createTypedData(0));
    dataMap.put("latitude", createTypedData(52.0));
    dataMap.put("longitude", createTypedData(-1.0));
    dataMap.put("validityDuration", createTypedData(40));
    dataMap.put("transmissionInterval", createTypedData(500));
    dataMap.put("detectionTime", createTypedData(123456789L));
    Message message = createMessageWithDataMap(dataMap);

    when(mockAdapter.triggerDenm(any(DenmParameters.class))).thenReturn(99L);

    testProtocol.outbound("DENM_TX_GROUP", message);

    // Verify trigger was called (publish group is used internally by SDK)
    verify(mockAdapter).triggerDenm(any(DenmParameters.class));
  }

  @Test
  void testRegisterLocalLink_CustomFieldMappings() throws Exception {
    // Create config with custom field mappings
    Map<String, Object> config = new HashMap<>();
    config.put("applicationId", "test-app-id");
    config.put("applicationToken", "test-token");

    Map<String, Object> denmService = new HashMap<>();
    denmService.put("enabled", true);
    denmService.put("publishGroup", "DENM_TX_GROUP");
    denmService.put("subscribeGroup", "DENM_RX_GROUP");
    config.put("denmService", denmService);

    Map<String, Object> fieldMappings = new HashMap<>();
    fieldMappings.put("causeCode", "event.code");
    fieldMappings.put("subCauseCode", "event.subcode");
    fieldMappings.put("latitude", "location.lat");
    fieldMappings.put("longitude", "location.lon");
    fieldMappings.put("validityDuration", "validity");
    fieldMappings.put("transmissionInterval", "interval");
    fieldMappings.put("detectionTime", "timestamp");

    Map<String, Object> denmLink = new HashMap<>();
    denmLink.put("direction", "push");
    denmLink.put("local_namespace", "/v2x/outbound/denm");
    denmLink.put("remote_namespace", "DENM_TX_GROUP");
    denmLink.put("service_type", "DENM");
    denmLink.put("field_mappings", fieldMappings);

    config.put("links", List.of(denmLink));

    V2xStepProtocol testProtocol = new V2xStepProtocol("step://DE_DEV_FRANKFURT/", config);

    // Should register successfully with custom field mappings
    assertDoesNotThrow(() -> testProtocol.registerLocalLink("DENM_TX_GROUP"));
  }

  /**
   * Helper method to create TypedData - uses real object instead of mock
   */
  private TypedData createTypedData(Object value) {
    return new TypedData(value);
  }

  /**
   * Helper method to create test Message with data map - uses real object instead of mock
   */
  private Message createMessageWithDataMap(Map<String, TypedData> dataMap) {
    return new MessageBuilder()
        .setDataMap(dataMap)
        .build();
  }

  /**
   * Helper method to create test Message with opaque data - uses real object instead of mock
   */
  private Message createMessageWithOpaqueData(byte[] data) {
    return new MessageBuilder()
        .setOpaqueData(data)
        .build();
  }
}
