package io.mapsmessaging.network.protocol.impl.ros;

import id.jrosmessages.Message;
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

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Static registry of ROS2 message types supported by this extension.
 *
 * <p><b>Library-provided types</b> (from {@code jrosmessages} / {@code jros2messages}):
 * <ul>
 *   <li>{@code std_msgs/String} – {@code id.jrosmessages.std_msgs.StringMessage}</li>
 *   <li>{@code geometry_msgs/Twist} – {@code id.jrosmessages.geometry_msgs.TwistMessage}</li>
 * </ul>
 *
 * <p><b>Extension-owned types</b> (hand-written POJOs in the {@code messages} sub-package;
 * not present in the upstream jrosmessages or jros2messages libraries at version 12.0/10.0):
 * <ul>
 *   <li>{@code std_msgs/Bool}, {@code std_msgs/Float32}</li>
 *   <li>{@code sensor_msgs/LaserScan}</li>
 *   <li>{@code nav_msgs/Odometry}</li>
 *   <li>{@code tf2_msgs/TFMessage}</li>
 *   <li>{@code nav2_msgs/Costmap} (including {@code CostmapMetaData})</li>
 *   <li>{@code nav2_msgs/action/NavigateToPose_FeedbackMessage}</li>
 *   <li>{@code action_msgs/GoalStatusArray} (including {@code GoalStatus}, {@code GoalInfo})</li>
 * </ul>
 *
 * <p>When upgrading {@code jrosmessages} or {@code jros2messages}, check whether any
 * extension-owned types have been added to the library and remove the local copy if so.
 */
final class Ros2MessageTypeRegistry {

  private static final Map<String, Class<? extends Message>> SUPPORTED_TYPES = new TreeMap<>();

  static {
    // -- Library-provided -------------------------------------------------
    register("std_msgs", "String", StringMessage.class);
    register("geometry_msgs", "Twist", TwistMessage.class);

    // -- Extension-owned --------------------------------------------------
    register("std_msgs", "Bool", BoolMessage.class);
    register("std_msgs", "Float32", Float32Message.class);
    register("nav_msgs", "Odometry", OdometryMessage.class);
    register("sensor_msgs", "LaserScan", LaserScanMessage.class);
    register("tf2_msgs", "TFMessage", TFMessage.class);
    register("nav2_msgs", "Costmap", CostmapMessage.class);
    register("nav2_msgs/action", "NavigateToPose_FeedbackMessage", NavigateToPose_FeedbackMessage.class);
    register("action_msgs", "GoalStatusArray", GoalStatusArrayMessage.class);
  }

  private Ros2MessageTypeRegistry() {
  }

  static Class<? extends Message> resolve(String rosPackage, String rosType) throws IOException {
    String key = key(rosPackage, rosType);
    Class<? extends Message> type = SUPPORTED_TYPES.get(key);
    if (type != null) {
      return type;
    }
    throw new IOException(
        "Unsupported ROS2 message type '" + rosPackage + "/" + rosType + "'. "
            + "Supported types: " + String.join(", ", SUPPORTED_TYPES.keySet()));
  }

  private static void register(String rosPackage, String rosType, Class<? extends Message> type) {
    SUPPORTED_TYPES.put(key(rosPackage, rosType), type);
  }

  private static String key(String rosPackage, String rosType) {
    String normalizedPackage = normalizePackage(rosPackage);
    String normalizedType = normalizeType(rosType);
    return normalizedPackage + "/" + normalizedType;
  }

  private static String normalizePackage(String rosPackage) {
    return rosPackage == null
        ? ""
        : rosPackage.trim()
            .replace('.', '/')
            .replaceAll("/{2,}", "/")
            .replaceAll("^/|/$", "")
            .toLowerCase(Locale.ROOT);
  }

  private static String normalizeType(String rosType) {
    if (rosType == null) {
      return "";
    }
    String normalized = rosType.trim();
    if (normalized.toLowerCase(Locale.ROOT).endsWith("message")) {
      normalized = normalized.substring(0, normalized.length() - "Message".length());
    }
    return normalized.toLowerCase(Locale.ROOT);
  }
}
