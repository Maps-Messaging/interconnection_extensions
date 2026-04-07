package io.mapsmessaging.network.protocol.impl.ros;

import io.mapsmessaging.logging.LoggerFactory;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RosBridgeConfigTest {

  @Test
  void shouldParseVpnDdsConfigOptions() {
    Map<String, Object> config = new HashMap<>();
    config.put("rosVersion", "2");
    config.put("schema_mode", "strict");
    config.put("ros_domain_id", "25");
    config.put("network_interface", "eth0");

    RosBridgeConfig parsed = RosBridgeConfig.fromMap(config);

    assertEquals(RosBridgeConfig.RosVersion.ROS2, parsed.rosVersion());
    assertEquals(25, parsed.rosDomainId());
    assertEquals("eth0", parsed.networkInterface());
  }

  @Test
  void shouldFallbackToCamelCaseKeys() {
    Map<String, Object> config = new HashMap<>();
    config.put("rosDomainId", 9);
    config.put("networkInterface", "en0");

    RosBridgeConfig parsed = RosBridgeConfig.fromMap(config);

    assertEquals(9, parsed.rosDomainId());
    assertEquals("en0", parsed.networkInterface());
  }

  @Test
  void shouldAllowUnsetOptionalDdsConfig() {
    RosBridgeConfig parsed = RosBridgeConfig.fromMap(new HashMap<>());

    assertEquals(RosBridgeConfig.RosVersion.ROS2, parsed.rosVersion());
    assertEquals(RosBridgeConfig.SchemaMode.STRICT, parsed.schemaMode());
    assertNull(parsed.rosDomainId());
    assertNull(parsed.networkInterface());
  }

  @Test
  void shouldAcceptYamlParsedNumericRos2Version() {
    Map<String, Object> config = new HashMap<>();
    config.put("rosVersion", 2.0d);

    RosBridgeConfig parsed = RosBridgeConfig.fromMap(config);

    assertEquals(RosBridgeConfig.RosVersion.ROS2, parsed.rosVersion());
  }

  @Test
  void shouldRejectNegativeRosDomainId() {
    RosBridgeConfig config = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2,
        RosBridgeConfig.SchemaMode.STRICT,
        -1,
        null
    );

    JRos2ClientAdapter adapter = new JRos2ClientAdapter(LoggerFactory.getLogger(getClass()), config);
    IOException error = assertThrows(IOException.class, adapter::buildClientConfiguration);
    assertEquals("ros_domain_id must be >= 0", error.getMessage());
  }

  @Test
  void shouldBuildDefaultJRos2Configuration() throws IOException {
    RosBridgeConfig config = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2,
        RosBridgeConfig.SchemaMode.STRICT,
        null,
        null
    );

    JRos2ClientAdapter adapter = new JRos2ClientAdapter(LoggerFactory.getLogger(getClass()), config);
    assertNotNull(adapter.buildClientConfiguration());
  }

  @Test
  void shouldRejectNonRos2Versions() {
    Map<String, Object> ros1 = new HashMap<>();
    ros1.put("rosVersion", "1");
    assertThrows(IllegalArgumentException.class, () -> RosBridgeConfig.fromMap(ros1));

    Map<String, Object> auto = new HashMap<>();
    auto.put("rosVersion", "auto");
    assertThrows(IllegalArgumentException.class, () -> RosBridgeConfig.fromMap(auto));
  }

  @Test
  void shouldRejectUnknownNetworkInterfaceName() {
    RosBridgeConfig config = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2,
        RosBridgeConfig.SchemaMode.STRICT,
        null,
        "eth_this_interface_does_not_exist_xyz"
    );

    JRos2ClientAdapter adapter = new JRos2ClientAdapter(LoggerFactory.getLogger(getClass()), config);
    IOException error = assertThrows(IOException.class, adapter::buildClientConfiguration);
    assertTrue(error.getMessage().contains("network_interface not found"));
  }

  // ---------------------------------------------------------------------------
  // Binding builder validation
  // ---------------------------------------------------------------------------

  @Test
  void strictPushBindingShouldRequireRosPackage() {
    RosBridgeConfig strict = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2, RosBridgeConfig.SchemaMode.STRICT, null, null);

    Map<String, Object> attrs = new HashMap<>();
    attrs.put("ros_type", "Twist");
    // ros_package intentionally absent

    IOException error = assertThrows(IOException.class,
        () -> RosProtocol.buildPushBinding("/maps/cmd_vel", attrs, strict));
    assertTrue(error.getMessage().contains("ros_package is required in strict schema mode"));
  }

  @Test
  void strictPushBindingShouldRequireRosType() {
    RosBridgeConfig strict = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2, RosBridgeConfig.SchemaMode.STRICT, null, null);

    Map<String, Object> attrs = new HashMap<>();
    attrs.put("ros_package", "geometry_msgs");
    // ros_type intentionally absent

    IOException error = assertThrows(IOException.class,
        () -> RosProtocol.buildPushBinding("/maps/cmd_vel", attrs, strict));
    assertTrue(error.getMessage().contains("ros_type is required in strict schema mode"));
  }

  @Test
  void strictPullBindingShouldRequireRosPackage() {
    RosBridgeConfig strict = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2, RosBridgeConfig.SchemaMode.STRICT, null, null);

    Map<String, Object> attrs = new HashMap<>();
    attrs.put("local_namespace", "/maps/odom");
    attrs.put("ros_type", "Odometry");
    // ros_package intentionally absent

    IOException error = assertThrows(IOException.class,
        () -> RosProtocol.buildPullBinding("/odom", attrs, strict));
    assertTrue(error.getMessage().contains("ros_package is required in strict schema mode"));
  }

  @Test
  void passthroughModeShouldAllowMissingPackageAndType() throws IOException {
    RosBridgeConfig passthrough = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2, RosBridgeConfig.SchemaMode.PASSTHROUGH, null, null);

    Map<String, Object> pushAttrs = new HashMap<>();
    // No ros_package or ros_type
    RosPushBinding push = RosProtocol.buildPushBinding("/maps/cmd", pushAttrs, passthrough);
    assertEquals("unknown", push.rosPackage());
    assertEquals("unknown", push.rosType());

    Map<String, Object> pullAttrs = new HashMap<>();
    pullAttrs.put("local_namespace", "/maps/odom");
    RosPullBinding pull = RosProtocol.buildPullBinding("/odom", pullAttrs, passthrough);
    assertEquals("unknown", pull.rosPackage());
    assertEquals("unknown", pull.rosType());
  }

  @Test
  void bindingBuilderShouldParseQosProfile() throws IOException {
    RosBridgeConfig config = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2, RosBridgeConfig.SchemaMode.PASSTHROUGH, null, null);

    Map<String, Object> pushAttrs = new HashMap<>();
    pushAttrs.put("ros_qos", "sensor_data");
    RosPushBinding push = RosProtocol.buildPushBinding("/maps/scan", pushAttrs, config);
    assertEquals("sensor_data", push.rosQosProfile());

    Map<String, Object> pullAttrs = new HashMap<>();
    pullAttrs.put("local_namespace", "/maps/tf_static");
    pullAttrs.put("ros_qos", "transient_local");
    RosPullBinding pull = RosProtocol.buildPullBinding("/tf_static", pullAttrs, config);
    assertEquals("transient_local", pull.rosQosProfile());
  }

  @Test
  void bindingBuilderShouldDefaultToNullQosWhenNotConfigured() throws IOException {
    RosBridgeConfig config = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2, RosBridgeConfig.SchemaMode.PASSTHROUGH, null, null);

    Map<String, Object> attrs = new HashMap<>();
    RosPushBinding push = RosProtocol.buildPushBinding("/maps/cmd", attrs, config);
    assertNull(push.rosQosProfile());
  }

  // ---------------------------------------------------------------------------
  // QoS mapping
  // ---------------------------------------------------------------------------

  @Test
  void shouldMapQosProfileToPublisherQos() {
    assertNotNull(JRos2ClientAdapter.publisherQos(null));
    assertNotNull(JRos2ClientAdapter.publisherQos("default"));
    assertNotNull(JRos2ClientAdapter.publisherQos("sensor_data"));
    assertNotNull(JRos2ClientAdapter.publisherQos("reliable"));
    assertNotNull(JRos2ClientAdapter.publisherQos("transient_local"));
    // Unknown values fall back to default without throwing
    assertNotNull(JRos2ClientAdapter.publisherQos("unknown_profile"));
  }

  @Test
  void shouldMapQosProfileToSubscriberQos() {
    assertNotNull(JRos2ClientAdapter.subscriberQos(null));
    assertNotNull(JRos2ClientAdapter.subscriberQos("sensor_data"));
    assertNotNull(JRos2ClientAdapter.subscriberQos("transient_local"));
    assertNotNull(JRos2ClientAdapter.subscriberQos("reliable"));
    // Unknown values fall back to default without throwing
    assertNotNull(JRos2ClientAdapter.subscriberQos("unknown_profile"));
  }

  // ---------------------------------------------------------------------------
  // Connection health
  // ---------------------------------------------------------------------------

  @Test
  void newAdapterShouldReportNotConnected() {
    // Before connect() the client reference is null so isConnected() uses the null guard.
    RosBridgeConfig config = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2, RosBridgeConfig.SchemaMode.STRICT, null, null);
    JRos2ClientAdapter adapter = new JRos2ClientAdapter(LoggerFactory.getLogger(getClass()), config);
    assertFalse(adapter.isConnected());
  }

  @Test
  void closedAdapterShouldReportNotConnected() {
    // After close(), isConnected() must return false even though the reference was once non-null.
    // This exercises the client.isClosed() path rather than the null guard alone.
    RosBridgeConfig config = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2, RosBridgeConfig.SchemaMode.STRICT, null, null);
    JRos2ClientAdapter adapter = new JRos2ClientAdapter(LoggerFactory.getLogger(getClass()), config);
    adapter.close(); // close on a null client must not throw
    assertFalse(adapter.isConnected());
  }

  @Test
  void doubleCloseShouldNotThrow() {
    RosBridgeConfig config = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2, RosBridgeConfig.SchemaMode.STRICT, null, null);
    JRos2ClientAdapter adapter = new JRos2ClientAdapter(LoggerFactory.getLogger(getClass()), config);
    adapter.close();
    adapter.close(); // second close must be safe
    assertFalse(adapter.isConnected());
  }

  @Test
  void publishToUnregisteredTopicShouldThrow() {
    RosBridgeConfig config = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2, RosBridgeConfig.SchemaMode.STRICT, null, null);
    JRos2ClientAdapter adapter = new JRos2ClientAdapter(LoggerFactory.getLogger(getClass()), config);

    RosMessageEnvelope envelope = new RosMessageEnvelope(
        "/no_such_topic", "2", "std_msgs", "String",
        null, null, null, "ros://2/std_msgs/String", new byte[0]);

    IOException error = assertThrows(IOException.class,
        () -> adapter.publish("/no_such_topic", envelope));
    assertTrue(error.getMessage().contains("not connected"));
  }

  // ---------------------------------------------------------------------------
  // RosProtocol version normalisation
  // ---------------------------------------------------------------------------

  @Test
  void buildPushBindingShouldRejectInvalidRosVersion() {
    RosBridgeConfig config = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2, RosBridgeConfig.SchemaMode.PASSTHROUGH, null, null);

    Map<String, Object> attrs = new HashMap<>();
    attrs.put("ros_version", "ros1");

    IOException error = assertThrows(IOException.class,
        () -> RosProtocol.buildPushBinding("/maps/cmd", attrs, config));
    assertTrue(error.getMessage().contains("Only ROS2 is supported"));
  }

  @Test
  void buildPullBindingShouldRejectInvalidRosVersion() {
    RosBridgeConfig config = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2, RosBridgeConfig.SchemaMode.PASSTHROUGH, null, null);

    Map<String, Object> attrs = new HashMap<>();
    attrs.put("local_namespace", "/maps/odom");
    attrs.put("ros_version", "auto");

    IOException error = assertThrows(IOException.class,
        () -> RosProtocol.buildPullBinding("/odom", attrs, config));
    assertTrue(error.getMessage().contains("Only ROS2 is supported"));
  }
}