package io.mapsmessaging.network.protocol.impl.ros;

import io.mapsmessaging.api.MessageBuilder;
import io.mapsmessaging.api.message.Message;
import io.mapsmessaging.api.message.TypedData;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RosMessageTranslatorTest {

  private final RosMessageTranslator translator = new RosMessageTranslator();

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

    RosPushBinding binding = new RosPushBinding("/maps/cmd", "/cmd_vel", "2", "geometry_msgs", "Twist");
    RosBridgeConfig config = new RosBridgeConfig(
        RosBridgeConfig.RosVersion.ROS2,
        RosBridgeConfig.SchemaMode.STRICT,
        "node",
        null);

    RosMessageEnvelope envelope = translator.toRosEnvelope(input, binding, config);

    assertEquals("/cmd_vel", envelope.getTopic());
    assertEquals("2", envelope.getRosVersion());
    assertEquals("geometry_msgs", envelope.getRosPackage());
    assertEquals("Twist", envelope.getRosType());
    assertEquals("ros://2/geometry_msgs/Twist", envelope.getSchemaId());
    assertArrayEquals(new byte[]{1, 2, 3}, envelope.getPayload());
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

    Message output = translator.toMapsMessage(envelope);

    assertEquals(RosSchemaConvention.CONTENT_TYPE, output.getContentType());
    assertArrayEquals(new byte[]{9, 8, 7}, output.getOpaqueData());
    assertEquals("ros", output.getDataMap().get(RosSchemaConvention.KEY_SCHEMA_KIND).getData());
    assertEquals("ros://2/nav_msgs/Odometry", output.getDataMap().get(RosSchemaConvention.KEY_SCHEMA_ID).getData());
    assertEquals("/odom", output.getDataMap().get(RosSchemaConvention.KEY_ROS_TOPIC).getData());
  }
}
