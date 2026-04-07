package io.mapsmessaging.network.protocol.impl.ros;

import id.jros2messages.Ros2MessageSerializationUtils;
import id.jros2messages.unique_identifier_msgs.UUIDMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.action_msgs.GoalStatusArrayMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.action_msgs.GoalStatusMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.nav2_msgs.CostmapMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.nav2_msgs.action.NavigateToPose_FeedbackMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.nav_msgs.OdometryMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.sensor_msgs.LaserScanMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.std_msgs.BoolMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.std_msgs.Float32Message;
import io.mapsmessaging.network.protocol.impl.ros.messages.tf2_msgs.TFMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Ros2CustomMessageSerializationTest {

  private final Ros2MessageSerializationUtils serializer = new Ros2MessageSerializationUtils();

  @Test
  void shouldSerializeAndDeserializeLaserScan() {
    LaserScanMessage message = new LaserScanMessage();
    message.angle_min = -1.57f;
    message.angle_max = 1.57f;
    message.withRanges(1.0f, 2.0f, 3.0f);

    byte[] bytes = serializer.write(message);
    LaserScanMessage read = serializer.read(bytes, LaserScanMessage.class);

    assertEquals(3, read.ranges.length);
    assertEquals(-1.57f, read.angle_min);
    assertEquals(1.57f, read.angle_max);
  }

  @Test
  void shouldSerializeAndDeserializeCollisionOutputMessages() {
    BoolMessage collision = new BoolMessage();
    collision.data = true;
    byte[] collisionBytes = serializer.write(collision);
    BoolMessage collisionRead = serializer.read(collisionBytes, BoolMessage.class);
    assertTrue(collisionRead.data);

    Float32Message nearestObstacle = new Float32Message();
    nearestObstacle.data = 0.85f;
    byte[] nearestObstacleBytes = serializer.write(nearestObstacle);
    Float32Message nearestObstacleRead = serializer.read(nearestObstacleBytes, Float32Message.class);
    assertEquals(0.85f, nearestObstacleRead.data);
  }

  @Test
  void shouldSerializeAndDeserializeOdometry() {
    OdometryMessage message = new OdometryMessage();
    message.child_frame_id = "base_link";
    message.pose.covariance = new double[36];
    message.twist.covariance = new double[36];

    byte[] bytes = serializer.write(message);
    OdometryMessage read = serializer.read(bytes, OdometryMessage.class);

    assertEquals("base_link", read.child_frame_id);
    assertNotNull(read.pose);
    assertNotNull(read.twist);
  }

  @Test
  void shouldSerializeAndDeserializeTfMessage() {
    TFMessage message = new TFMessage();
    byte[] bytes = serializer.write(message);
    TFMessage read = serializer.read(bytes, TFMessage.class);
    assertNotNull(read.transforms);
  }

  @Test
  void shouldSerializeAndDeserializeCostmap() {
    CostmapMessage message = new CostmapMessage().withData((byte) 1, (byte) 2, (byte) 3);
    message.metadata.layer = "obstacles";
    message.metadata.size_x = 2;
    message.metadata.size_y = 2;

    byte[] bytes = serializer.write(message);
    CostmapMessage read = serializer.read(bytes, CostmapMessage.class);

    assertEquals("obstacles", read.metadata.layer);
    assertEquals(3, read.data.length);
  }

  @Test
  void shouldSerializeAndDeserializeActionMonitoringMessages() {
    NavigateToPose_FeedbackMessage feedback = new NavigateToPose_FeedbackMessage();
    feedback.goal_id = UUIDMessage.generate();
    feedback.feedback.distance_remaining = 12.5f;
    feedback.feedback.number_of_recoveries = 2;
    byte[] feedbackBytes = serializer.write(feedback);
    NavigateToPose_FeedbackMessage feedbackRead =
        serializer.read(feedbackBytes, NavigateToPose_FeedbackMessage.class);
    assertEquals(12.5f, feedbackRead.feedback.distance_remaining);
    assertEquals(2, feedbackRead.feedback.number_of_recoveries);

    GoalStatusArrayMessage status = new GoalStatusArrayMessage();
    status.status_list = new GoalStatusMessage[]{new GoalStatusMessage()};
    status.status_list[0].goal_info.goal_id = UUIDMessage.generate();
    status.status_list[0].status = GoalStatusMessage.STATUS_EXECUTING;
    byte[] statusBytes = serializer.write(status);
    GoalStatusArrayMessage statusRead = serializer.read(statusBytes, GoalStatusArrayMessage.class);
    assertEquals(1, statusRead.status_list.length);
    assertEquals(GoalStatusMessage.STATUS_EXECUTING, statusRead.status_list[0].status);
  }

  @Test
  void shouldSerializeAndDeserializeLaserScanWithEmptyRanges() {
    LaserScanMessage message = new LaserScanMessage();
    // Default ranges is already empty; ensure round-trip works
    byte[] bytes = serializer.write(message);
    LaserScanMessage read = serializer.read(bytes, LaserScanMessage.class);
    assertEquals(0, read.ranges.length);
    assertEquals(0, read.intensities.length);
  }

  @Test
  void shouldRoundTripAllGoalStatusConstants() {
    byte[] statusValues = {
        GoalStatusMessage.STATUS_UNKNOWN,
        GoalStatusMessage.STATUS_ACCEPTED,
        GoalStatusMessage.STATUS_EXECUTING,
        GoalStatusMessage.STATUS_CANCELING,
        GoalStatusMessage.STATUS_SUCCEEDED,
        GoalStatusMessage.STATUS_CANCELED,
        GoalStatusMessage.STATUS_ABORTED,
    };

    for (byte statusValue : statusValues) {
      GoalStatusArrayMessage array = new GoalStatusArrayMessage();
      GoalStatusMessage status = new GoalStatusMessage();
      status.goal_info.goal_id = UUIDMessage.generate();
      status.status = statusValue;
      array.status_list = new GoalStatusMessage[]{status};

      byte[] bytes = serializer.write(array);
      GoalStatusArrayMessage read = serializer.read(bytes, GoalStatusArrayMessage.class);
      assertEquals(statusValue, read.status_list[0].status, "Status constant " + statusValue + " did not survive round-trip");
    }
  }

  @Test
  void shouldSerializeAndDeserializeStringMessageFromJrosLibrary() {
    id.jrosmessages.std_msgs.StringMessage original = new id.jrosmessages.std_msgs.StringMessage();
    original.data = "hello-from-maps-ros-extension";

    byte[] bytes = serializer.write(original);
    id.jrosmessages.std_msgs.StringMessage decoded =
        serializer.read(bytes, id.jrosmessages.std_msgs.StringMessage.class);

    assertEquals(original.data, decoded.data,
        "jros2client round-trip should preserve the String payload");
  }
}
