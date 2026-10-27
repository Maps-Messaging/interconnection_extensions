package io.mapsmessaging.network.protocol.impl.v2x_step;

import io.mapsmessaging.api.message.Message;
import io.mapsmessaging.api.message.TypedData;
import io.mapsmessaging.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Extracts field values from message payloads using configured field paths.
 * Supports extraction from:
 * 1. Message data map (highest priority)
 * 2. XML payload parsing
 * 3. JSON payload parsing (future)
 */
public class PayloadFieldExtractor {

  private final Logger logger;

  public PayloadFieldExtractor(Logger logger) {
    this.logger = logger;
  }

  /**
   * Extract DENM parameters from a message using field mapping configuration.
   *
   * @param message The incoming message
   * @param fieldMapping The field mapping configuration
   * @return Extracted DENM parameters
   * @throws IOException if extraction fails
   */
  public DenmParameters extractDenmParameters(Message message, DenmFieldMapping fieldMapping) throws IOException {
    // First try to extract from message data map (for pre-parsed messages)
    Map<String, TypedData> dataMap = message.getDataMap();
    if (dataMap != null && !dataMap.isEmpty()) {
      DenmParameters params = tryExtractFromDataMap(dataMap, fieldMapping);
      if (params != null) {
        return params;
      }
    }

    // Fall back to parsing raw payload
    byte[] payload = message.getOpaqueData();
    if (payload == null || payload.length == 0) {
      throw new IOException("Message has no payload data");
    }

    // Try XML parsing
    try {
      return extractFromXml(payload, fieldMapping);
    } catch (Exception e) {
      if (logger != null) {
        logger.log(V2xStepLogMessages.V2X_STEP_ERROR, "Failed to parse payload as XML: " + e.getMessage());
      }
      throw new IOException("Failed to extract DENM parameters from payload", e);
    }
  }

  /**
   * Try to extract parameters from message data map.
   */
  private DenmParameters tryExtractFromDataMap(Map<String, TypedData> dataMap, DenmFieldMapping fieldMapping) {
    try {
      Integer causeCode = getIntFromDataMap(dataMap, "causeCode");
      Integer subCauseCode = getIntFromDataMap(dataMap, "subCauseCode");
      Double latitude = getDoubleFromDataMap(dataMap, "latitude");
      Double longitude = getDoubleFromDataMap(dataMap, "longitude");
      Integer validityDuration = getIntFromDataMap(dataMap, "validityDuration");
      Integer transmissionInterval = getIntFromDataMap(dataMap, "transmissionInterval");
      Long detectionTime = getLongFromDataMap(dataMap, "detectionTime");

      if (causeCode != null && subCauseCode != null && latitude != null && longitude != null &&
          validityDuration != null && transmissionInterval != null && detectionTime != null) {
        return new DenmParameters(causeCode, subCauseCode, latitude, longitude,
            validityDuration, transmissionInterval, detectionTime);
      }
    } catch (Exception e) {
      // Extraction from data map failed, will try payload parsing
    }
    return null;
  }

  /**
   * Extract parameters from XML payload using field path notation.
   */
  private DenmParameters extractFromXml(byte[] payload, DenmFieldMapping fieldMapping) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    DocumentBuilder builder = factory.newDocumentBuilder();
    Document doc = builder.parse(new ByteArrayInputStream(payload));

    int causeCode = getIntValueFromXPath(doc, fieldMapping.getCauseCodeField());
    int subCauseCode = getIntValueFromXPath(doc, fieldMapping.getSubCauseCodeField());

    // ETSI ITS latitude/longitude are in 1/10 microdegree units
    // Convert to decimal degrees: value / 10_000_000.0
    int latitudeMicroDeg = getIntValueFromXPath(doc, fieldMapping.getLatitudeField());
    int longitudeMicroDeg = getIntValueFromXPath(doc, fieldMapping.getLongitudeField());
    double latitude = latitudeMicroDeg / 10_000_000.0;
    double longitude = longitudeMicroDeg / 10_000_000.0;

    int validityDuration = getIntValueFromXPath(doc, fieldMapping.getValidityDurationField());
    int transmissionInterval = getIntValueFromXPath(doc, fieldMapping.getTransmissionIntervalField());
    long detectionTime = getLongValueFromXPath(doc, fieldMapping.getDetectionTimeField());

    return new DenmParameters(causeCode, subCauseCode, latitude, longitude,
        validityDuration, transmissionInterval, detectionTime);
  }

  /**
   * Get integer value from XML using dot-notation path (e.g., "denm.situation.eventType.causeCode").
   */
  private int getIntValueFromXPath(Document doc, String path) throws IOException {
    String value = getValueFromXPath(doc, path);
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new IOException("Field '" + path + "' value '" + value + "' is not a valid integer");
    }
  }

  /**
   * Get long value from XML using dot-notation path.
   */
  private long getLongValueFromXPath(Document doc, String path) throws IOException {
    String value = getValueFromXPath(doc, path);
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new IOException("Field '" + path + "' value '" + value + "' is not a valid long");
    }
  }

  /**
   * Get text value from XML using dot-notation path.
   * Example: "denm.situation.eventType.causeCode" navigates through XML elements.
   */
  private String getValueFromXPath(Document doc, String path) throws IOException {
    String[] parts = path.split("\\.");
    Node current = doc.getDocumentElement();

    for (String part : parts) {
      if (current == null) {
        throw new IOException("Field path '" + path + "' not found in XML (null at '" + part + "')");
      }

      Node found = null;
      NodeList children = current.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        Node child = children.item(i);
        if (child.getNodeType() == Node.ELEMENT_NODE && child.getNodeName().equals(part)) {
          found = child;
          break;
        }
      }

      if (found == null) {
        throw new IOException("Field path '" + path + "' not found in XML (element '" + part + "' missing)");
      }
      current = found;
    }

    if (current == null) {
      throw new IOException("Field path '" + path + "' resolved to null");
    }

    String textContent = current.getTextContent();
    if (textContent == null || textContent.trim().isEmpty()) {
      throw new IOException("Field path '" + path + "' has no text content");
    }

    return textContent.trim();
  }

  private Integer getIntFromDataMap(Map<String, TypedData> dataMap, String key) {
    TypedData data = dataMap.get(key);
    if (data == null) return null;
    Object value = data.getData();
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    try {
      return Integer.parseInt(value.toString());
    } catch (Exception e) {
      return null;
    }
  }

  private Double getDoubleFromDataMap(Map<String, TypedData> dataMap, String key) {
    TypedData data = dataMap.get(key);
    if (data == null) return null;
    Object value = data.getData();
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    try {
      return Double.parseDouble(value.toString());
    } catch (Exception e) {
      return null;
    }
  }

  private Long getLongFromDataMap(Map<String, TypedData> dataMap, String key) {
    TypedData data = dataMap.get(key);
    if (data == null) return null;
    Object value = data.getData();
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    try {
      return Long.parseLong(value.toString());
    } catch (Exception e) {
      return null;
    }
  }
}
