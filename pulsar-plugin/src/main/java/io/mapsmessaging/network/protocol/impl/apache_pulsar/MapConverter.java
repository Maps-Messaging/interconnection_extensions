package io.mapsmessaging.network.protocol.impl.apache_pulsar;

import java.util.*;
import java.util.stream.Collectors;

public class MapConverter {
  public static Map<String, Object> convertMap(Map<String, String> inputMap) {
    return inputMap.entrySet().stream()
        .collect(Collectors.toMap(
            Map.Entry::getKey,
            entry -> parseValue(entry.getValue())
        ));
  }

  private static Object parseValue(String value) {
    if (value == null) return null;
    if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
      return Boolean.parseBoolean(value);
    }
    try {
      if (value.contains(".")) {
        return Double.parseDouble(value);
      }
      return Integer.parseInt(value);
    } catch (NumberFormatException ignored) {
    }
    return value;
  }
}
