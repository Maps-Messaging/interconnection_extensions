package io.mapsmessaging.network.protocol.impl.ros;

public record RosPullBinding(String localNamespace, String rosTopic, String rosVersion, String rosPackage,
                             String rosType, String rosQosProfile) {

}