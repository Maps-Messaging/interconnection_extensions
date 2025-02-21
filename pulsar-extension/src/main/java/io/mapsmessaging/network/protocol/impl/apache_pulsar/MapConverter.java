package io.mapsmessaging.network.protocol.impl.apache_pulsar;

import io.mapsmessaging.api.message.TypedData;

import java.util.*;
import java.util.stream.Collectors;

public class MapConverter {
  public static Map<String, TypedData> convertMap(Map<String, String> inputMap) {
    return inputMap.entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> parseValue(entry.getValue())
        ));
  }

  private static TypedData parseValue(String value) {
    if (value == null) return null;
    if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
      return new TypedData(Boolean.parseBoolean(value));
    }
    try {
      if (value.contains(".")) {
        return new TypedData(Double.parseDouble(value));
      }
      return new TypedData(Integer.parseInt(value));
    } catch (NumberFormatException ignored) {
    }
    return new TypedData(value);
  }
}
