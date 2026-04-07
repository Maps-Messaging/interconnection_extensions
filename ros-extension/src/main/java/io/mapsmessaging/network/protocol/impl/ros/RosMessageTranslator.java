package io.mapsmessaging.network.protocol.impl.ros;

import io.mapsmessaging.api.MessageBuilder;
import io.mapsmessaging.api.message.Message;
import io.mapsmessaging.api.message.TypedData;
import io.mapsmessaging.logging.Logger;

import java.util.Map;

public class RosMessageTranslator {

  private final Logger logger;

  public RosMessageTranslator(Logger logger) {
    this.logger = logger;
  }

  public RosMessageEnvelope toRosEnvelope(Message message, RosPushBinding binding, RosBridgeConfig config) {
    Map<String, TypedData> dataMap = message.getDataMap();

    String version  = firstString(dataMap, RosSchemaConvention.KEY_ROS_VERSION,  binding.rosVersion(),  config.rosVersion().name());
    String pkg      = firstString(dataMap, RosSchemaConvention.KEY_ROS_PACKAGE,   binding.rosPackage(),  "unknown");
    String type     = firstString(dataMap, RosSchemaConvention.KEY_ROS_TYPE,      binding.rosType(),     "unknown");
    String topic    = firstString(dataMap, RosSchemaConvention.KEY_ROS_TOPIC,     binding.rosTopic(),    binding.rosTopic());
    String md5      = firstString(dataMap, RosSchemaConvention.KEY_ROS_MD5,       null, null);
    String qos      = firstString(dataMap, RosSchemaConvention.KEY_ROS_QOS,       null, null);
    String context  = firstString(dataMap, RosSchemaConvention.KEY_ROS_CONTEXT,   null, null);
    String schemaId = firstString(dataMap, RosSchemaConvention.KEY_SCHEMA_ID,
        RosSchemaConvention.schemaId(version, pkg, type),
        RosSchemaConvention.schemaId(version, pkg, type));

    byte[] payload = message.getOpaqueData();
    if (payload == null) {
      payload = new byte[0];
    }

    return new RosMessageEnvelope(topic, version, pkg, type, md5, qos, context, schemaId, payload);
  }

  public Message toMapsMessage(RosMessageEnvelope envelope) {
    return new MessageBuilder()
        .setOpaqueData(envelope.payload())
        .setContentType(RosSchemaConvention.CONTENT_TYPE)
        .setDataMap(RosSchemaConvention.metadataAsTypedData(envelope))
        .build();
  }

  private String firstString(Map<String, TypedData> dataMap, String key, String preferred, String fallback) {
    if (dataMap != null) {
      TypedData typedData = dataMap.get(key);
      if (typedData != null && typedData.getData() != null) {
        return typedData.getData().toString();
      }
    }
    if (preferred != null && !preferred.trim().isEmpty()) {
      // Only log when preferred differs from fallback — logging every binding default is noise.
      if (!preferred.equals(fallback)) {
        logger.log(RosLogMessages.ROS_MESSAGE_FALLBACK, key, "binding");
      }
      return preferred;
    }
    if (fallback != null) {
      logger.log(RosLogMessages.ROS_MESSAGE_FALLBACK, key, "default");
    }
    return fallback;
  }
}