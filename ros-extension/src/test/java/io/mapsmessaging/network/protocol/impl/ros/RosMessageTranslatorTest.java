package io.mapsmessaging.network.protocol.impl.ros;

import io.mapsmessaging.api.MessageBuilder;
import io.mapsmessaging.api.message.Message;
import io.mapsmessaging.api.message.TypedData;
import io.mapsmessaging.logging.LoggerFactory;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RosMessageTranslatorTest {

  private final RosMessageTranslator translator =
      new RosMessageTranslator(LoggerFactory.getLogger(RosMessageTranslatorTest.class));

  @Test
  void shouldBuildRosEnvelopeUsingSchemaConvention() {
    Map<String, TypedData> map = new LinkedHashMap<>();
    map.put(RosSchemaConvention.KEY_ROS_VERSION, new TypedData("2"));
    map.put(RosSchemaConvention.KEY_ROS_PACKAGE, new TypedData("geometry_msgs"));
    map.put(RosSchemaConvention.KEY_ROS_TYPE, new TypedData("Twist"));

    Message input = new MessageBuilder()
        .setOpaqueData(new byte[]{1, 2, 3})
        .setDataMap(map)
        .build();

    RosPushBinding binding = new RosPushBinding("/maps/cmd", "/cmd_vel", "2", "geometry_msgs", "Twist", null);
    RosBridgeConfig config = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2,
        RosBridgeConfig.SchemaMode.STRICT,
        RosBridgeConfig.PayloadFormat.CDR,
        null,
        null);

    RosMessageEnvelope envelope = translator.toRosEnvelope(input, binding, config);

    assertEquals("/cmd_vel", envelope.topic());
    assertEquals("2", envelope.rosVersion());
    assertEquals("geometry_msgs", envelope.rosPackage());
    assertEquals("Twist", envelope.rosType());
    assertEquals("ros://2/geometry_msgs/Twist", envelope.schemaId());
    assertArrayEquals(new byte[]{1, 2, 3}, envelope.payload());
  }

  @Test
  void shouldTranslateInboundEnvelopeToMapsMessageWithContext() {
    RosMessageEnvelope envelope = new RosMessageEnvelope(
        "/odom",
        "2",
        "nav_msgs",
        "Odometry",
        null,
        "reliable",
        "{\"frame_id\":\"map\"}",
        "ros://2/nav_msgs/Odometry",
        new byte[]{9, 8, 7});

    Message output = translator.toMapsMessage(envelope, RosBridgeConfig.PayloadFormat.CDR);

    assertEquals(RosSchemaConvention.CONTENT_TYPE, output.getContentType());
    assertArrayEquals(new byte[]{9, 8, 7}, output.getOpaqueData());
    assertEquals("ros", output.getDataMap().get(RosSchemaConvention.KEY_SCHEMA_KIND).getData());
    assertEquals("ros://2/nav_msgs/Odometry", output.getDataMap().get(RosSchemaConvention.KEY_SCHEMA_ID).getData());
    assertEquals("/odom", output.getDataMap().get(RosSchemaConvention.KEY_ROS_TOPIC).getData());
  }

  @Test
  void shouldFallBackToBindingWhenDataMapHasNoEntry() {
    // Empty dataMap — should use binding values
    Message input = new MessageBuilder()
        .setOpaqueData(new byte[]{5})
        .build();

    RosPushBinding binding = new RosPushBinding("/maps/scan", "/scan", "2", "sensor_msgs", "LaserScan", "sensor_data");
    RosBridgeConfig config = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2,
        RosBridgeConfig.SchemaMode.STRICT,
        RosBridgeConfig.PayloadFormat.CDR,
        null,
        null);

    RosMessageEnvelope envelope = translator.toRosEnvelope(input, binding, config);

    assertEquals("sensor_msgs", envelope.rosPackage());
    assertEquals("LaserScan", envelope.rosType());
    assertEquals("/scan", envelope.topic());
    assertEquals("ros://2/sensor_msgs/LaserScan", envelope.schemaId());
  }

  @Test
  void shouldFallBackToConfigVersionWhenDataMapAndBindingHaveNoVersion() {
    Message input = new MessageBuilder()
        .setOpaqueData(new byte[0])
        .build();

    RosPushBinding binding = new RosPushBinding("/maps/tf", "/tf", null, "tf2_msgs", "TFMessage", null);
    RosBridgeConfig config = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2,
        RosBridgeConfig.SchemaMode.STRICT,
        RosBridgeConfig.PayloadFormat.CDR,
        null,
        null);

    RosMessageEnvelope envelope = translator.toRosEnvelope(input, binding, config);

    // Should fall back to config version name "ROS2" then return via normalisation
    assertEquals("ROS2", envelope.rosVersion());
  }

  @Test
  void shouldHandleNullOpaqueDataAsEmptyPayload() {
    Message input = new MessageBuilder()
        .setDataMap(new LinkedHashMap<>())
        .build();  // no setOpaqueData — returns null

    RosPushBinding binding = new RosPushBinding("/maps/cmd", "/cmd_vel", "2", "geometry_msgs", "Twist", null);
    RosBridgeConfig config = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2,
        RosBridgeConfig.SchemaMode.STRICT,
        RosBridgeConfig.PayloadFormat.CDR,
        null,
        null);

    RosMessageEnvelope envelope = translator.toRosEnvelope(input, binding, config);

    assertArrayEquals(new byte[0], envelope.payload());
  }

  @Test
  void shouldUseSchemaIdFromDataMapWhenPresent() {
    Map<String, TypedData> map = new LinkedHashMap<>();
    map.put(RosSchemaConvention.KEY_SCHEMA_ID, new TypedData("ros://2/custom_pkg/CustomType"));
    map.put(RosSchemaConvention.KEY_ROS_VERSION, new TypedData("2"));
    map.put(RosSchemaConvention.KEY_ROS_PACKAGE, new TypedData("geometry_msgs"));
    map.put(RosSchemaConvention.KEY_ROS_TYPE, new TypedData("Twist"));

    Message input = new MessageBuilder()
        .setOpaqueData(new byte[]{1})
        .setDataMap(map)
        .build();

    RosPushBinding binding = new RosPushBinding("/maps/cmd", "/cmd_vel", "2", "geometry_msgs", "Twist", null);
    RosBridgeConfig config = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2,
        RosBridgeConfig.SchemaMode.STRICT,
        RosBridgeConfig.PayloadFormat.CDR,
        null,
        null);

    RosMessageEnvelope envelope = translator.toRosEnvelope(input, binding, config);

    assertEquals("ros://2/custom_pkg/CustomType", envelope.schemaId());
  }

  @Test
  void toMapsMessageShouldSetJsonContentTypeWhenFormatIsJson() {
    RosMessageEnvelope envelope = new RosMessageEnvelope(
        "/cmd_vel", "2", "geometry_msgs", "Twist",
        null, null, null, "ros://2/geometry_msgs/Twist",
        "{\"linear\":{\"x\":1.0}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    Message output = translator.toMapsMessage(envelope, RosBridgeConfig.PayloadFormat.JSON);

    assertEquals(RosSchemaConvention.CONTENT_TYPE_JSON, output.getContentType());
  }

  @Test
  void toMapsMessageShouldSetCdrContentTypeWhenFormatIsCdr() {
    RosMessageEnvelope envelope = new RosMessageEnvelope(
        "/cmd_vel", "2", "geometry_msgs", "Twist",
        null, null, null, "ros://2/geometry_msgs/Twist", new byte[]{1, 2, 3});

    Message output = translator.toMapsMessage(envelope, RosBridgeConfig.PayloadFormat.CDR);

    assertEquals(RosSchemaConvention.CONTENT_TYPE, output.getContentType());
  }

  @Test
  void shouldPreserveNullOptionalFieldsWhenEnvelopeHasNoQosOrContext() {
    RosMessageEnvelope envelope = new RosMessageEnvelope(
        "/scan", "2", "sensor_msgs", "LaserScan",
        null, null, null,
        "ros://2/sensor_msgs/LaserScan",
        new byte[]{1});

    Message output = translator.toMapsMessage(envelope, RosBridgeConfig.PayloadFormat.CDR);

    assertNull(output.getDataMap().get(RosSchemaConvention.KEY_ROS_QOS));
    assertNull(output.getDataMap().get(RosSchemaConvention.KEY_ROS_CONTEXT));
    assertNull(output.getDataMap().get(RosSchemaConvention.KEY_ROS_MD5));
  }
}