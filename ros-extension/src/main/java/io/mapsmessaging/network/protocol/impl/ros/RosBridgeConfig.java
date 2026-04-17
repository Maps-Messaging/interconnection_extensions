package io.mapsmessaging.network.protocol.impl.ros;

import java.util.Map;

/**
 * Runtime configuration for the ROS2 bridge.
 *
 * <p>The {@code ros://...} URL is a MAPS extension endpoint identifier, not a
 * TCP target for ROS. ROS2 connectivity is DDS based, so the effective network
 * controls here are the DDS domain id, network interface, QoS on individual
 * links, and the configured ROS topic/type metadata.
 */
public record RosBridgeConfig(RosVersion rosVersion, SchemaMode schemaMode, PayloadFormat payloadFormat,
                              Integer rosDomainId, String networkInterface) {

  public enum RosVersion {
    ROS2;

    static RosVersion from(Object value) {
      if (value == null) {
        return ROS2;
      }
      if (value instanceof Number && ((Number) value).doubleValue() == 2.0d) {
        return ROS2;
      }
      String text = value.toString().trim().toUpperCase();
      if ("2".equals(text) || "2.0".equals(text) || "ROS2".equals(text)) {
        return ROS2;
      }
      throw new IllegalArgumentException(
              "Unsupported rosVersion '" + value + "'. Only ROS2 is supported.");
    }
  }

  public enum SchemaMode {
    STRICT,
    PASSTHROUGH;

    static SchemaMode from(Object value) {
      if (value == null) {
        return STRICT;
      }
      String text = value.toString().trim().toUpperCase();
      if ("PASSTHROUGH".equals(text)) {
        return PASSTHROUGH;
      }
      return STRICT;
    }
  }

  public enum PayloadFormat {
    CDR,
    JSON;

    static PayloadFormat from(Object value) {
      if (value == null) {
        return CDR;
      }
      if ("json".equalsIgnoreCase(value.toString().trim())) {
        return JSON;
      }
      return CDR;
    }
  }

  public static RosBridgeConfig fromMap(Map<String, Object> config) {
    RosVersion version = RosVersion.from(config.get("rosVersion"));
    SchemaMode mode = SchemaMode.from(config.get("schema_mode"));
    PayloadFormat format = PayloadFormat.from(config.get("payload_format"));
    Integer rosDomainId = asInteger(config, "ros_domain_id", "rosDomainId");
    String networkInterface = asString(config, "network_interface", "networkInterface");
    return new RosBridgeConfig(
            version,
            mode,
            format,
            rosDomainId,
            networkInterface);
  }

  private static String asString(Map<String, Object> config, String... keys) {
    for (String key : keys) {
      Object value = config.get(key);
      if (value != null) {
        String text = value.toString().trim();
        if (!text.isEmpty()) {
          return text;
        }
      }
    }
    return null;
  }

  private static Integer asInteger(Map<String, Object> config, String... keys) {
    for (String key : keys) {
      Object value = config.get(key);
      if (value == null) {
        continue;
      }
      if (value instanceof Number) {
        return ((Number) value).intValue();
      }
      try {
        return Integer.parseInt(value.toString().trim());
      } catch (NumberFormatException ignored) {
      }
    }
    return null;
  }
}
