package io.mapsmessaging.network.protocol.impl.ros;

public class RosPullBinding {
  private final String localNamespace;
  private final String rosTopic;
  private final String rosVersion;
  private final String rosPackage;
  private final String rosType;

  public RosPullBinding(String localNamespace, String rosTopic, String rosVersion, String rosPackage, String rosType) {
    this.localNamespace = localNamespace;
    this.rosTopic = rosTopic;
    this.rosVersion = rosVersion;
    this.rosPackage = rosPackage;
    this.rosType = rosType;
  }

  public String getLocalNamespace() {
    return localNamespace;
  }

  public String getRosTopic() {
    return rosTopic;
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
}
