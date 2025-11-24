# V2X STEP Extension

MapsMessaging extension for triggering DENM (Decentralized Environmental Notification Message) events on the Vodafone V2X STEP platform.

## Overview

This extension integrates MapsMessaging with the Vodafone V2X STEP platform to trigger DENM events from incoming message payloads. The extension:

1. **Extracts DENM parameters** from message payloads using configurable field mappings
2. **Triggers DENM events** via the STEP SDK's `denmTrigger()` method
3. **Returns sequence numbers** assigned by the STEP SDK for tracking

**Architecture approach:** This extension uses a **parameter extraction + SDK trigger** pattern rather than forwarding pre-formed DENM payloads. The STEP SDK generates the actual V2X messages based on the extracted parameters.

## Features

- ✅ **Configurable field mappings** - Extract DENM parameters from any payload structure (XML, JSON, or message properties)
- ✅ **ETSI ITS compatibility** - Default field mappings for ETSI DENM XML format with automatic coordinate conversion
- ✅ **Multiple payload formats** - Supports XML parsing, message data map extraction, with JSON support planned
- ✅ **Per-link customization** - Each routing link can have its own field mappings
- ⏳ **Push links only** - Pull links (STEP → MAPS) not yet implemented

## Quick Start

### 1. Prerequisites

- **Vodafone V2X SDK JAR** (`v2xsdk4java-3.1.0.jar`) - Place in `lib/` directory
- **STEP credentials** - Application ID and token from Vodafone portal
- **STEP instance** - Access to a Vodafone STEP deployment (e.g., `DE_DEV_FRANKFURT`)

### 2. Installation

```bash
# Build the extension
mvn clean install -pl v2x-step-extension

# Copy JAR to MapsMessaging plugins directory
cp v2x-step-extension/target/v2x-step-extension-1.0.0-SNAPSHOT.jar \
   /path/to/mapsmessaging/plugins/
```

### 3. Configuration

Add to `NetworkConnectionManager.yaml`:

```yaml
NetworkConnectionManager:
  data:
    - name: v2x_step_denm
      url: "step://DE_DEV_FRANKFURT"
      protocol: v2x-step
      plugin: true
      config:
        applicationId: "your-app-id"
        applicationToken: "your-token"
        denmService:
          enabled: true
          publishGroup: "DENM_TX_GROUP"
          subscribeGroup: "DENM_RX_GROUP"
      links:
        - direction: push
          local_namespace: "/v2x/outbound/denm"
          remote_namespace: "DENM_TX_GROUP"
          service_type: "DENM"
          include_schema: false
          # Optional: Custom field mappings
          field_mappings:
            causeCode: "denm.situation.eventType.causeCode"
            subCauseCode: "denm.situation.eventType.subCauseCode"
            latitude: "denm.denm.management.eventPosition.latitude"
            longitude: "denm.denm.management.eventPosition.longitude"
            validityDuration: "denm.denm.management.validityDuration"
            transmissionInterval: "denm.denm.management.transmissionInterval"
            detectionTime: "denm.denm.management.detectionTime"
```

### 4. Send Messages

Publish ETSI DENM XML to `/v2x/outbound/denm`:

```xml
<denm>
  <denm>
    <management>
      <eventPosition>
        <latitude>524687872</latitude>  <!-- 52.4687872° in 1/10 microdegrees -->
        <longitude>-15787688</longitude>
      </eventPosition>
      <validityDuration>40</validityDuration>
      <transmissionInterval>500</transmissionInterval>
      <detectionTime>559397550748</detectionTime>
    </management>
  </denm>
  <situation>
    <eventType>
      <causeCode>2</causeCode>  <!-- Accident -->
      <subCauseCode>0</subCauseCode>
    </eventType>
  </situation>
</denm>
```

The extension will:
1. Extract parameters using configured field mappings
2. Convert coordinates from ETSI format (1/10 microdegrees) to decimal degrees
3. Call `sdk.denmTrigger(causeCode, subCauseCode, location, ...)`
4. Log the returned sequence number

## Configuration Details

### STEP Instance

Specified in the URL host component:

```yaml
url: "step://DE_DEV_FRANKFURT"
```

**Valid instances:**
- `DE_DEV_FRANKFURT` - Development (Frankfurt, Germany)
- `DE_PROD_FRANKFURT` - Production (Frankfurt, Germany)
- `DE_TEST_ALDENHOVEN` - Test (Aldenhoven, Germany)
- `ES_DEV_GRANADA` - Development (Granada, Spain)
- `IT_DEV_MILANO` - Development (Milano, Italy)
- `IT_DEV_BOLOGNA` - Development (Bologna, Italy)
- `FR_TEST_IOT` - Test IoT (France)
- `FR_TEST_V2X` - Test V2X (France)

### Field Mappings

Field mappings use **dot-notation paths** to navigate payload structures:

```yaml
field_mappings:
  causeCode: "denm.situation.eventType.causeCode"
  latitude: "denm.denm.management.eventPosition.latitude"
```

**Default mappings** (used if `field_mappings` is omitted):
- `causeCode`: `denm.situation.eventType.causeCode`
- `subCauseCode`: `denm.situation.eventType.subCauseCode`
- `latitude`: `denm.denm.management.eventPosition.latitude`
- `longitude`: `denm.denm.management.eventPosition.longitude`
- `validityDuration`: `denm.denm.management.validityDuration`
- `transmissionInterval`: `denm.denm.management.transmissionInterval`
- `detectionTime`: `denm.denm.management.detectionTime`

### Extraction Sources

The extension tries extraction methods in this order:

1. **Message data map** - Pre-parsed properties (highest priority)
   ```java
   // If message has these properties set, field mappings are ignored
   message.getDataMap().put("causeCode", 2);
   message.getDataMap().put("latitude", 52.4687872);
   // ... etc
   ```

2. **XML payload** - Parsed using field mapping paths
   ```xml
   <denm>
     <situation>
       <eventType>
         <causeCode>2</causeCode>  <!-- Extracted via path -->
       </eventType>
     </situation>
   </denm>
   ```

3. **JSON payload** - (Planned for future release)

### Coordinate Conversion

**ETSI ITS format:** Latitude/longitude in 1/10 microdegree units

- Value `524687872` = `52.4687872°` decimal
- Value `-15787688` = `-1.5787688°` decimal

The extension **automatically converts** to decimal degrees when calling the SDK.

### DENM Parameters

| Parameter | Type | Description | ETSI Source |
|-----------|------|-------------|-------------|
| `causeCode` | int | Event cause (e.g., 2 = accident) | ETSI ITS cause code |
| `subCauseCode` | int | Event sub-cause | ETSI ITS sub-cause |
| `latitude` | double | Event latitude (decimal degrees) | eventPosition.latitude |
| `longitude` | double | Event longitude (decimal degrees) | eventPosition.longitude |
| `validityDuration` | int | Seconds the DENM is valid | management.validityDuration |
| `transmissionInterval` | int | Milliseconds between transmissions | management.transmissionInterval |
| `detectionTime` | long | Milliseconds since epoch | management.detectionTime |

## Architecture

### Message Flow

```
MAPS Topic                           V2X STEP Extension                    Vodafone STEP
/v2x/outbound/denm                                                         Network
     │                                                                          │
     │  1. Message arrives                                                     │
     ├──────────────────────────────►                                          │
     │                                                                          │
     │                              2. Extract parameters                      │
     │                                 using field mappings                    │
     │                                 (XML parse or data map)                 │
     │                                         │                               │
     │                              3. sdk.denmTrigger()                       │
     │                                 (causeCode, location,                   │
     │                                  validityDuration, ...)                 │
     │                                         │                               │
     │                                         ├───────────────────────────────►
     │                                         │                               │
     │                              4. Return sequence number                  │
     │                                         ◄───────────────────────────────┤
     │                                         │                               │
     │                              5. Log success                             │
     │                                 (sequenceNumber)                        │
```

### Class Structure

```
V2xStepProtocol (main extension)
    ├── PayloadFieldExtractor
    │   └── Extracts parameters from payloads
    ├── DenmFieldMapping
    │   └── Configures field paths
    ├── V2xStepSdkAdapter
    │   └── Wraps SDK calls (mockable for tests)
    └── PushBinding
        └── Per-link metadata (service type, publish group, field mapping)
```

## Example Payloads

### ETSI DENM XML (Full)

```xml
<DenmEtsi>
  <gbcGacHeader>
    <basicHeader>
      <version>1</version>
      <nextHeader>1</nextHeader>
      <lifeTime>
        <multiplier>19</multiplier>
        <base>0</base>
      </lifeTime>
    </basicHeader>
    <sequenceNumber>262</sequenceNumber>
  </gbcGacHeader>
  <denm>
    <header>
      <protocolVersion>2</protocolVersion>
      <messageID>1</messageID>
      <stationID>212822854</stationID>
    </header>
    <denm>
      <management>
        <actionID>
          <originatingStationID>212822854</originatingStationID>
          <sequenceNumber>4</sequenceNumber>
        </actionID>
        <detectionTime>559397550748</detectionTime>
        <referenceTime>559397550750</referenceTime>
        <eventPosition>
          <latitude>524687872</latitude>
          <longitude>-15787688</longitude>
          <positionConfidenceEllipse>
            <semiMajorConfidence>283</semiMajorConfidence>
            <semiMinorConfidence>283</semiMinorConfidence>
            <semiMajorOrientation>0</semiMajorOrientation>
          </positionConfidenceEllipse>
          <altitude>
            <altitudeValue>0</altitudeValue>
            <altitudeConfidence>
              <alt-000-02 />
            </altitudeConfidence>
          </altitude>
        </eventPosition>
        <relevanceDistance>
          <lessThan200m />
        </relevanceDistance>
        <validityDuration>40</validityDuration>
        <transmissionInterval>500</transmissionInterval>
        <stationType>5</stationType>
      </management>
      <situation>
        <informationQuality>0</informationQuality>
        <eventType>
          <causeCode>2</causeCode>
          <subCauseCode>0</subCauseCode>
        </eventType>
      </situation>
      <location>
        <eventSpeed>
          <speedValue>6666</speedValue>
          <speedConfidence>127</speedConfidence>
        </eventSpeed>
      </location>
    </denm>
  </denm>
</DenmEtsi>
```

### Custom JSON Format

```yaml
# Custom field mappings for JSON payload
field_mappings:
  causeCode: "event.type.code"
  subCauseCode: "event.type.subcode"
  latitude: "location.lat"
  longitude: "location.lon"
  validityDuration: "validity.seconds"
  transmissionInterval: "transmission.interval_ms"
  detectionTime: "timestamps.detected"
```

```json
{
  "event": {
    "type": {
      "code": 2,
      "subcode": 0
    }
  },
  "location": {
    "lat": 52.4687872,
    "lon": -1.5787688
  },
  "validity": {
    "seconds": 40
  },
  "transmission": {
    "interval_ms": 500
  },
  "timestamps": {
    "detected": 1609459200000
  }
}
```

## Logging

Log messages use the `V2X_STEP_CATEGORY.PROTOCOL` category:

- **INFO**: Initialization, link registration
- **DEBUG**: Successful DENM triggers with sequence numbers
- **WARN**: Messages to unregistered destinations
- **ERROR**: Extraction failures, SDK errors

Example log output:
```
[INFO] V2X STEP protocol initialized
[INFO] Registered local STEP subscription for: DENM_TX_GROUP -> PushBinding{serviceType=DENM, publishGroup='DENM_TX_GROUP', fieldMapping=...}
[DEBUG] Triggered DENM in group DENM_TX_GROUP, sequenceNumber=42
```

## Limitations & Future Work

### Current Limitations

- ❌ **No pull links** - Cannot receive DENM events from STEP → MAPS
- ❌ **No JSON parsing** - Only XML and message data map supported
- ❌ **No DENM updates/terminations** - Only initial trigger supported

### Planned Features

- 🔄 Pull link implementation (STEP → MAPS event subscription)
- 🔄 JSON payload parsing
- 🔄 DENM update/terminate operations

## Development

### Build

```bash
mvn clean install -pl v2x-step-extension
```

### Run Tests

```bash
mvn test -pl v2x-step-extension
```

### Dependencies

- **Vodafone V2X SDK** (v3.1.0) - System-scoped, local JAR in `lib/`
- **MapsMessaging Core** (provided at runtime)
- **JUnit 5** + **Mockito** (test scope)

## License

Apache License 2.0 with Commons Clause

See [LICENSE](../LICENSE) for details.

## Support

- **Issues**: https://github.com/maps-messaging/interconnection_extensions/issues
- **Documentation**: https://docs.mapsmessaging.io/
- **STEP Portal**: Contact Vodafone for credentials and documentation
