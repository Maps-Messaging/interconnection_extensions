package io.mapsmessaging.network.protocol.impl.ros;

import java.util.Arrays;

public class RosMessageEnvelope {

  private final String topic;
  private final String rosVersion;
  private final String rosPackage;
  private final String rosType;
  private final String rosMd5;
  private final String rosQos;
  private final String rosContextJson;
  private final String schemaId;
  private final byte[] payload;

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

  public String getTopic() {
    return topic;
  }

  public String getRosVersion() {
    return rosVersion;
  }

  public String getRosPackage() {
    return rosPackage;
  }

  public String getRosType() {
    return rosType;
  }

  public String getRosMd5() {
    return rosMd5;
  }

  public String getRosQos() {
    return rosQos;
  }

  public String getRosContextJson() {
    return rosContextJson;
  }

  public String getSchemaId() {
    return schemaId;
  }

  public byte[] getPayload() {
    return Arrays.copyOf(payload, payload.length);
  }
}
