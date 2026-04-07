package io.mapsmessaging.network.protocol.impl.ros;

import id.jrosmessages.geometry_msgs.TwistMessage;
import id.jrosmessages.std_msgs.StringMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.action_msgs.GoalStatusArrayMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.nav2_msgs.CostmapMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.nav2_msgs.action.NavigateToPose_FeedbackMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.nav_msgs.OdometryMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.sensor_msgs.LaserScanMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.std_msgs.BoolMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.std_msgs.Float32Message;
import io.mapsmessaging.network.protocol.impl.ros.messages.tf2_msgs.TFMessage;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ros2MessageTypeRegistryTest {

  @Test
  void shouldResolveSupportedMessageTypes() throws Exception {
    assertEquals(StringMessage.class, Ros2MessageTypeRegistry.resolve("std_msgs", "String"));
    assertEquals(BoolMessage.class, Ros2MessageTypeRegistry.resolve("std_msgs", "Bool"));
    assertEquals(Float32Message.class, Ros2MessageTypeRegistry.resolve("std_msgs", "Float32"));
    assertEquals(TwistMessage.class, Ros2MessageTypeRegistry.resolve("geometry_msgs", "Twist"));
    assertEquals(OdometryMessage.class, Ros2MessageTypeRegistry.resolve("nav_msgs", "Odometry"));
    assertEquals(LaserScanMessage.class, Ros2MessageTypeRegistry.resolve("sensor_msgs", "LaserScan"));
    assertEquals(TFMessage.class, Ros2MessageTypeRegistry.resolve("tf2_msgs", "TFMessage"));
    assertEquals(CostmapMessage.class, Ros2MessageTypeRegistry.resolve("nav2_msgs", "Costmap"));
    assertEquals(
        NavigateToPose_FeedbackMessage.class,
        Ros2MessageTypeRegistry.resolve("nav2_msgs/action", "NavigateToPose_FeedbackMessage"));
    assertEquals(GoalStatusArrayMessage.class, Ros2MessageTypeRegistry.resolve("action_msgs", "GoalStatusArray"));
  }

  @Test
  void shouldNormalizeMessageSuffixAndPackageSeparators() throws Exception {
    assertEquals(TFMessage.class, Ros2MessageTypeRegistry.resolve("tf2_msgs", "TF"));
    assertEquals(StringMessage.class, Ros2MessageTypeRegistry.resolve("std_msgs", "StringMessage"));
    assertEquals(
        NavigateToPose_FeedbackMessage.class,
        Ros2MessageTypeRegistry.resolve("nav2_msgs.action", "NavigateToPose_Feedback"));
  }

  @Test
  void shouldFailFastOnUnsupportedType() {
    IOException error = assertThrows(IOException.class,
        () -> Ros2MessageTypeRegistry.resolve("std_msgs", "Int32"));
    assertTrue(error.getMessage().contains("Unsupported ROS2 message type"));
    assertTrue(error.getMessage().contains("Supported types"));
  }

  @Test
  void shouldHandleNullPackageAndTypeThroughNormalization() {
    // null package and type both normalise to "" which will not match any registration
    assertThrows(IOException.class, () -> Ros2MessageTypeRegistry.resolve(null, "String"));
    assertThrows(IOException.class, () -> Ros2MessageTypeRegistry.resolve("std_msgs", null));
    assertThrows(IOException.class, () -> Ros2MessageTypeRegistry.resolve(null, null));
  }

  @Test
  void shouldHandleUppercasePackageAndType() throws Exception {
    // Registry normalises to lowercase so uppercase package inputs also resolve
    assertEquals(StringMessage.class, Ros2MessageTypeRegistry.resolve("STD_MSGS", "String"));
  }

  @Test
  void shouldHandleRepeatedSlashesInPackage() throws Exception {
    // nav2_msgs//action should normalise to nav2_msgs/action
    assertEquals(
        NavigateToPose_FeedbackMessage.class,
        Ros2MessageTypeRegistry.resolve("nav2_msgs//action", "NavigateToPose_FeedbackMessage"));
  }

  @Test
  void shouldHandleEmptyStringInputs() {
    assertThrows(IOException.class, () -> Ros2MessageTypeRegistry.resolve("", "String"));
    assertThrows(IOException.class, () -> Ros2MessageTypeRegistry.resolve("std_msgs", ""));
  }

  @Test
  void errorMessageShouldListSupportedTypes() {
    IOException error = assertThrows(IOException.class,
        () -> Ros2MessageTypeRegistry.resolve("fake_pkg", "FakeType"));
    // Should list at least some known types in the error
    assertTrue(error.getMessage().contains("std_msgs"));
  }
}
