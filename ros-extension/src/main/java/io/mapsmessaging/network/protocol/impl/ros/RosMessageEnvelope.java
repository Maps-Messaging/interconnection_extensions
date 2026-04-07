package io.mapsmessaging.network.protocol.impl.ros;

import java.util.Arrays;

public record RosMessageEnvelope(String topic, String rosVersion, String rosPackage, String rosType, String rosMd5,
                                 String rosQos, String rosContextJson, String schemaId, byte[] payload) {

  public RosMessageEnvelope(String topic,
                            String rosVersion,
                            String rosPackage,
                            String rosType,
                            String rosMd5,
                            String rosQos,
                            String rosContextJson,
                            String schemaId,
                            byte[] payload) {
    this.topic = topic;
    this.rosVersion = rosVersion;
    this.rosPackage = rosPackage;
    this.rosType = rosType;
    this.rosMd5 = rosMd5;
    this.rosQos = rosQos;
    this.rosContextJson = rosContextJson;
    this.schemaId = schemaId;
    this.payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
  }

  @Override
  public byte[] payload() {
    return Arrays.copyOf(payload, payload.length);
  }
}
