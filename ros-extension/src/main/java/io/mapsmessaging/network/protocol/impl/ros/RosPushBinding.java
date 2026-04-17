package io.mapsmessaging.network.protocol.impl.ros;

public record RosPushBinding(String localNamespace, String rosTopic, String rosVersion, String rosPackage,
                             String rosType, String rosQosProfile) {

}