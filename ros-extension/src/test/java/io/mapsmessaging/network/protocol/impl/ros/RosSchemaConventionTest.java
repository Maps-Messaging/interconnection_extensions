package io.mapsmessaging.network.protocol.impl.ros;

import io.mapsmessaging.api.message.TypedData;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RosSchemaConventionTest {

  // ---------------------------------------------------------------------------
  // schemaId
  // ---------------------------------------------------------------------------

  @Test
  void schemaIdShouldProduceCanonicalUri() {
    assertEquals("ros://2/geometry_msgs/Twist", RosSchemaConvention.schemaId("2", "geometry_msgs", "Twist"));
  }

  @Test
  void schemaIdShouldFallbackToAutoVersionWhenNull() {
    assertEquals("ros://auto/nav_msgs/Odometry", RosSchemaConvention.schemaId(null, "nav_msgs", "Odometry"));
  }

  @Test
  void schemaIdShouldFallbackToAutoVersionWhenBlank() {
    assertEquals("ros://auto/sensor_msgs/LaserScan", RosSchemaConvention.schemaId("  ", "sensor_msgs", "LaserScan"));
  }

  @Test
  void schemaIdShouldFallbackToUnknownPackageWhenNull() {
    assertEquals("ros://2/unknown/Twist", RosSchemaConvention.schemaId("2", null, "Twist"));
  }

  @Test
  void schemaIdShouldFallbackToUnknownPackageWhenBlank() {
    assertEquals("ros://2/unknown/Twist", RosSchemaConvention.schemaId("2", "", "Twist"));
  }

  @Test
  void schemaIdShouldFallbackToUnknownTypeWhenNull() {
    assertEquals("ros://2/geometry_msgs/unknown", RosSchemaConvention.schemaId("2", "geometry_msgs", null));
  }

  @Test
  void schemaIdShouldFallbackToUnknownTypeWhenBlank() {
    assertEquals("ros://2/geometry_msgs/unknown", RosSchemaConvention.schemaId("2", "geometry_msgs", "  "));
  }

  @Test
  void schemaIdAllNullShouldReturnFullySubstituted() {
    assertEquals("ros://auto/unknown/unknown", RosSchemaConvention.schemaId(null, null, null));
  }

  // ---------------------------------------------------------------------------
  // metadataAsTypedData
  // ---------------------------------------------------------------------------

  @Test
  void metadataAsTypedDataShouldIncludeAllMandatoryKeys() {
    RosMessageEnvelope envelope = new RosMessageEnvelope(
        "/scan", "2", "sensor_msgs", "LaserScan",
        null, null, null, "ros://2/sensor_msgs/LaserScan", new byte[0]);

    Map<String, TypedData> data = RosSchemaConvention.metadataAsTypedData(envelope);

    assertEquals("ros", data.get(RosSchemaConvention.KEY_SCHEMA_KIND).getData());
    assertEquals("ros://2/sensor_msgs/LaserScan", data.get(RosSchemaConvention.KEY_SCHEMA_ID).getData());
    assertEquals("2", data.get(RosSchemaConvention.KEY_ROS_VERSION).getData());
    assertEquals("sensor_msgs", data.get(RosSchemaConvention.KEY_ROS_PACKAGE).getData());
    assertEquals("LaserScan", data.get(RosSchemaConvention.KEY_ROS_TYPE).getData());
    assertEquals("/scan", data.get(RosSchemaConvention.KEY_ROS_TOPIC).getData());
  }

  @Test
  void metadataAsTypedDataShouldOmitNullOptionalKeys() {
    RosMessageEnvelope envelope = new RosMessageEnvelope(
        "/tf", "2", "tf2_msgs", "TFMessage",
        null, null, null, "ros://2/tf2_msgs/TFMessage", new byte[0]);

    Map<String, TypedData> data = RosSchemaConvention.metadataAsTypedData(envelope);

    assertNull(data.get(RosSchemaConvention.KEY_ROS_MD5));
    assertNull(data.get(RosSchemaConvention.KEY_ROS_QOS));
    assertNull(data.get(RosSchemaConvention.KEY_ROS_CONTEXT));
  }

  @Test
  void metadataAsTypedDataShouldIncludeOptionalKeysWhenPresent() {
    RosMessageEnvelope envelope = new RosMessageEnvelope(
        "/odom", "2", "nav_msgs", "Odometry",
        "abc123", "reliable", "{\"frame\":\"map\"}",
        "ros://2/nav_msgs/Odometry", new byte[]{1, 2});

    Map<String, TypedData> data = RosSchemaConvention.metadataAsTypedData(envelope);

    assertNotNull(data.get(RosSchemaConvention.KEY_ROS_MD5));
    assertEquals("abc123", data.get(RosSchemaConvention.KEY_ROS_MD5).getData());
    assertNotNull(data.get(RosSchemaConvention.KEY_ROS_QOS));
    assertEquals("reliable", data.get(RosSchemaConvention.KEY_ROS_QOS).getData());
    assertNotNull(data.get(RosSchemaConvention.KEY_ROS_CONTEXT));
    assertEquals("{\"frame\":\"map\"}", data.get(RosSchemaConvention.KEY_ROS_CONTEXT).getData());
  }

  @Test
  void metadataAsTypedDataShouldPreserveInsertionOrder() {
    RosMessageEnvelope envelope = new RosMessageEnvelope(
        "/cmd_vel", "2", "geometry_msgs", "Twist",
        null, null, null, "ros://2/geometry_msgs/Twist", new byte[0]);

    Map<String, TypedData> data = RosSchemaConvention.metadataAsTypedData(envelope);

    String[] expectedKeys = {
        RosSchemaConvention.KEY_SCHEMA_KIND,
        RosSchemaConvention.KEY_SCHEMA_ID,
        RosSchemaConvention.KEY_ROS_VERSION,
        RosSchemaConvention.KEY_ROS_PACKAGE,
        RosSchemaConvention.KEY_ROS_TYPE,
        RosSchemaConvention.KEY_ROS_TOPIC,
    };
    String[] actualKeys = data.keySet().toArray(new String[0]);
    for (int i = 0; i < expectedKeys.length; i++) {
      assertEquals(expectedKeys[i], actualKeys[i], "Key at index " + i + " should match");
    }
  }

  @Test
  void contentTypeConstantShouldBeRosBinaryMimeType() {
    assertEquals("application/x-ros-binary", RosSchemaConvention.CONTENT_TYPE);
  }

  @Test
  void mandatoryDataMapShouldNotContainOptionalKeysWhenAbsent() {
    RosMessageEnvelope envelope = new RosMessageEnvelope(
        "/scan", "2", "sensor_msgs", "LaserScan",
        null, null, null, "ros://2/sensor_msgs/LaserScan", new byte[0]);

    Map<String, TypedData> data = RosSchemaConvention.metadataAsTypedData(envelope);

    assertFalse(data.containsKey(RosSchemaConvention.KEY_ROS_MD5));
    assertFalse(data.containsKey(RosSchemaConvention.KEY_ROS_QOS));
    assertFalse(data.containsKey(RosSchemaConvention.KEY_ROS_CONTEXT));
  }
}