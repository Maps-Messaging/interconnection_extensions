package io.mapsmessaging.network.protocol.impl.ros;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class RosMessageEnvelopeTest {

  @Test
  void constructorShouldDefensiveCopyPayload() {
    byte[] original = {1, 2, 3};
    RosMessageEnvelope envelope = new RosMessageEnvelope(
        "/scan", "2", "sensor_msgs", "LaserScan", null, null, null,
        "ros://2/sensor_msgs/LaserScan", original);

    original[0] = 99;

    assertArrayEquals(new byte[]{1, 2, 3}, envelope.payload(),
        "Mutating the original array after construction must not affect the stored payload");
  }

  @Test
  void getPayloadShouldReturnDefensiveCopy() {
    RosMessageEnvelope envelope = new RosMessageEnvelope(
        "/cmd_vel", "2", "geometry_msgs", "Twist", null, null, null,
        "ros://2/geometry_msgs/Twist", new byte[]{10, 20, 30});

    byte[] first = envelope.payload();
    byte[] second = envelope.payload();

    assertNotSame(first, second, "Each getPayload() call should return a distinct array");
    assertArrayEquals(first, second, "Both copies should have equal content");

    first[0] = 99;
    assertArrayEquals(new byte[]{10, 20, 30}, envelope.payload(),
        "Mutating a returned payload must not affect subsequent calls");
  }

  @Test
  void nullPayloadShouldBeStoredAsEmptyArray() {
    RosMessageEnvelope envelope = new RosMessageEnvelope(
        "/tf", "2", "tf2_msgs", "TFMessage", null, null, null,
        "ros://2/tf2_msgs/TFMessage", null);

    assertArrayEquals(new byte[0], envelope.payload());
  }

  @Test
  void accessorsShouldReturnConstructorValues() {
    RosMessageEnvelope envelope = new RosMessageEnvelope(
        "/odom", "2", "nav_msgs", "Odometry",
        "deadbeef", "reliable", "{\"frame\":\"map\"}",
        "ros://2/nav_msgs/Odometry",
        new byte[]{5, 6});

    assertEquals("/odom", envelope.topic());
    assertEquals("2", envelope.rosVersion());
    assertEquals("nav_msgs", envelope.rosPackage());
    assertEquals("Odometry", envelope.rosType());
    assertEquals("deadbeef", envelope.rosMd5());
    assertEquals("reliable", envelope.rosQos());
    assertEquals("{\"frame\":\"map\"}", envelope.rosContextJson());
    assertEquals("ros://2/nav_msgs/Odometry", envelope.schemaId());
    assertArrayEquals(new byte[]{5, 6}, envelope.payload());
  }
}