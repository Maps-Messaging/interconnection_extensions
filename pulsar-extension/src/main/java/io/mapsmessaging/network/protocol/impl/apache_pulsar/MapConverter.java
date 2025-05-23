/*
 *  Copyright [ 2020 - 2024 ] Matthew Buckton
 *  Copyright [ 2024 - 2025 ] MapsMessaging B.V.
 *
 *  Licensed under the Apache License, Version 2.0 with the Commons Clause
 *  (the "License"); you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *      https://commonsclause.com/
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

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
