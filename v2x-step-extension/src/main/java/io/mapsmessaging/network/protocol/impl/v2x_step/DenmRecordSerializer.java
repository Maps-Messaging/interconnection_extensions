package io.mapsmessaging.network.protocol.impl.v2x_step;

import com.vodafone.v2xsdk4javav2.facade.records.denm.DENMRecord;
import java.nio.charset.StandardCharsets;

/**
 * Serializes DENMRecord objects to various formats for publishing to MAPS topics.
 */
public class DenmRecordSerializer {

  /**
   * Serialize a DENMRecord to JSON format.
   *
   * @param denm The DENM record to serialize
   * @return JSON representation as byte array
   */
  public static byte[] toJson(DENMRecord denm) {
    // Build JSON manually to avoid external dependencies
    StringBuilder json = new StringBuilder();
    json.append("{");
    json.append("\"stationID\":").append(denm.getOriginatorID()).append(",");
    json.append("\"sequenceNumber\":").append(denm.getSequenceNumber()).append(",");
    json.append("\"causeCode\":").append(denm.getCauseCode()).append(",");
    json.append("\"subCauseCode\":").append(denm.getSubCauseCode()).append(",");
    json.append("\"latitude\":").append(denm.getLatitude()).append(",");
    json.append("\"longitude\":").append(denm.getLongitude());

    // Optional fields (only if getter exists)
    if (denm.getAltitude() != null) {
      json.append(",\"altitude\":").append(denm.getAltitude());
    }

    json.append("}");
    return json.toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Serialize a DENMRecord to XML format (ETSI format).
   *
   * @param denm The DENM record to serialize
   * @return XML representation as byte array
   */
  public static byte[] toXml(DENMRecord denm) {
    // Build ETSI-style XML manually
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.append("<denm>\n");

    // Management container
    xml.append("  <management>\n");
    xml.append("    <actionID>\n");
    xml.append("      <originatingStationID>").append(denm.getOriginatorID()).append("</originatingStationID>\n");
    xml.append("      <sequenceNumber>").append(denm.getSequenceNumber()).append("</sequenceNumber>\n");
    xml.append("    </actionID>\n");

    // Event position
    xml.append("    <eventPosition>\n");
    xml.append("      <latitude>").append(convertToEtsiCoordinate(denm.getLatitude())).append("</latitude>\n");
    xml.append("      <longitude>").append(convertToEtsiCoordinate(denm.getLongitude())).append("</longitude>\n");
    if (denm.getAltitude() != null) {
      xml.append("      <altitude>").append(denm.getAltitude()).append("</altitude>\n");
    }
    xml.append("    </eventPosition>\n");

    xml.append("  </management>\n");

    // Situation container
    xml.append("  <situation>\n");
    xml.append("    <eventType>\n");
    xml.append("      <causeCode>").append(denm.getCauseCode()).append("</causeCode>\n");
    xml.append("      <subCauseCode>").append(denm.getSubCauseCode()).append("</subCauseCode>\n");
    xml.append("    </eventType>\n");
    xml.append("  </situation>\n");

    xml.append("</denm>");
    return xml.toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Convert decimal degrees to ETSI 1/10 microdegree format.
   * ETSI coordinates are in 1/10th of a microdegree (multiply by 10,000,000).
   *
   * @param decimalDegrees Coordinate in decimal degrees
   * @return ETSI coordinate value
   */
  private static long convertToEtsiCoordinate(double decimalDegrees) {
    return Math.round(decimalDegrees * 10_000_000.0);
  }
}
