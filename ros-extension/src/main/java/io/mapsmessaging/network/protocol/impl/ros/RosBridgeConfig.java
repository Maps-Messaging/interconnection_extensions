package io.mapsmessaging.network.protocol.impl.ros;

import java.util.Map;

public class RosBridgeConfig {

  public enum RosVersion {
    AUTO,
    ROS1,
    ROS2;

    static RosVersion from(Object value) {
      if (value == null) {
        return AUTO;
      }
      String text = value.toString().trim().toUpperCase();
      if ("1".equals(text) || "ROS1".equals(text)) {
        return ROS1;
      }
      if ("2".equals(text) || "ROS2".equals(text)) {
        return ROS2;
      }
      return AUTO;
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

  private final RosVersion rosVersion;
  private final SchemaMode schemaMode;
  private final String nodeName;
  private final String rosEndpoint;

  public RosBridgeConfig(RosVersion rosVersion, SchemaMode schemaMode, String nodeName, String rosEndpoint) {
    this.rosVersion = rosVersion;
    this.schemaMode = schemaMode;
    this.nodeName = nodeName;
    this.rosEndpoint = rosEndpoint;
  }

  public RosVersion getRosVersion() {
    return rosVersion;
  }

  public SchemaMode getSchemaMode() {
    return schemaMode;
  }

  public String getNodeName() {
    return nodeName;
  }

  public String getRosEndpoint() {
    return rosEndpoint;
  }

  @SuppressWarnings("unchecked")
  public static RosBridgeConfig fromMap(Map<String, Object> config) {
    RosVersion version = RosVersion.from(config.get("rosVersion"));
    SchemaMode mode = SchemaMode.from(config.get("schema_mode"));
    String node = config.getOrDefault("nodeName", "maps-ros-bridge").toString();
    Object endpointObj = config.get("endpoint");
    String endpoint = endpointObj == null ? null : endpointObj.toString();
    return new RosBridgeConfig(version, mode, node, endpoint);
  }
}
