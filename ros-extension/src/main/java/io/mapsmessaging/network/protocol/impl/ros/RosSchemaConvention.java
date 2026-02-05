package io.mapsmessaging.network.protocol.impl.ros;

import io.mapsmessaging.api.message.TypedData;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RosSchemaConvention {

  public static final String CONTENT_TYPE = "application/x-ros-binary";
  public static final String KEY_SCHEMA_ID = "maps.schema.id";
  public static final String KEY_SCHEMA_KIND = "maps.schema.kind";
  public static final String KEY_ROS_VERSION = "ros.version";
  public static final String KEY_ROS_PACKAGE = "ros.package";
  public static final String KEY_ROS_TYPE = "ros.type";
  public static final String KEY_ROS_TOPIC = "ros.topic";
  public static final String KEY_ROS_MD5 = "ros.md5";
  public static final String KEY_ROS_QOS = "ros.qos";
  public static final String KEY_ROS_CONTEXT = "ros.context";

  private RosSchemaConvention() {
  }

  public static String schemaId(String rosVersion, String rosPackage, String rosType) {
    String v = rosVersion == null || rosVersion.trim().isEmpty() ? "auto" : rosVersion.toLowerCase();
    String pkg = rosPackage == null || rosPackage.trim().isEmpty() ? "unknown" : rosPackage.trim();
    String type = rosType == null || rosType.trim().isEmpty() ? "unknown" : rosType.trim();
    return "ros://" + v + "/" + pkg + "/" + type;
  }

  public static Map<String, TypedData> metadataAsTypedData(RosMessageEnvelope envelope) {
    Map<String, TypedData> data = new LinkedHashMap<>();
    data.put(KEY_SCHEMA_KIND, new TypedData("ros"));
    data.put(KEY_SCHEMA_ID, new TypedData(envelope.getSchemaId()));
    data.put(KEY_ROS_VERSION, new TypedData(envelope.getRosVersion()));
    data.put(KEY_ROS_PACKAGE, new TypedData(envelope.getRosPackage()));
    data.put(KEY_ROS_TYPE, new TypedData(envelope.getRosType()));
    data.put(KEY_ROS_TOPIC, new TypedData(envelope.getTopic()));
    if (envelope.getRosMd5() != null) {
      data.put(KEY_ROS_MD5, new TypedData(envelope.getRosMd5()));
    }
    if (envelope.getRosQos() != null) {
      data.put(KEY_ROS_QOS, new TypedData(envelope.getRosQos()));
    }
    if (envelope.getRosContextJson() != null) {
      data.put(KEY_ROS_CONTEXT, new TypedData(envelope.getRosContextJson()));
    }
    return data;
  }
}
