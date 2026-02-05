# Interconnection Extensions

This repository provides server-to-server connection plugins for MapsMessaging, enabling integration with third-party messaging platforms and specialized communication protocols.

## Available Extensions

### V2X STEP Extension

**Module:** `v2x-step-extension/`

Provides routing integration between MapsMessaging and the Vodafone STEP (Service and Technology Enablement Platform) for V2X (Vehicle-to-Everything) communications. Supports bidirectional message flow for CAM (Cooperative Awareness Messages) and DENM (Decentralized Environmental Notification Messages).

**Key Features:**
- Pure routing between MAPS namespaces and STEP service groups
- Support for CAM and DENM message types
- Configurable push/pull links with service type selectors
- Integration with Vodafone V2X STEP Java SDK

**Important:** This is a routing-only extension. It does NOT transform message payloads, provide GNSS/location services, or perform schema conversion. Messages must arrive pre-formatted as valid CAM/DENM fields sets so that they can be emmitted as ETSI v2x by the vodafone STEP SDK.

**Documentation:** See [v2x-step-extension/README.md](v2x-step-extension/README.md) for complete setup, configuration, and usage instructions.

**Example Configuration:** [v2x-step-extension/src/main/resources/NetworkConnectionManager-example.yaml](v2x-step-extension/src/main/resources/NetworkConnectionManager-example.yaml)

### AWS SNS Extension

**Module:** `aws-sns-extension/`

_(Documentation pending)_

### IBM MQ Extension

**Module:** `ibm-mq-extension/`

_(Documentation pending)_

### Pulsar Extension

**Module:** `pulsar-extension/`

_(Documentation pending)_

### ROS Extension

**Module:** `ros-extension/`

Bridges MAPS topics with ROS topics (ROS 1 and ROS 2 convention support) while preserving ROS message context through a schema-aware envelope convention.

**Documentation:** See [ros-extension/README.md](ros-extension/README.md)

## Building

Build all extensions:
```bash
mvn clean install
```

Build a specific extension:
```bash
cd v2x-step-extension
mvn clean install
```

## Deployment

1. Build the extension module
2. Copy the resulting JAR from `target/` to your MapsMessaging `plugins/` directory
3. Configure the extension in MapsMessaging's `NetworkConnectionManager.yaml`
4. Restart MapsMessaging server

See individual extension README files for specific deployment instructions.

## Development Requirements

- JDK 11 or later (JDK 21 for main project)
- Maven 3.6+
- MapsMessaging Server 4.2.1 or later
- Extension-specific dependencies (see individual README files)

## License

Copyright [2025] MapsMessaging B.V.

Licensed under the Apache License, Version 2.0 with the Commons Clause.
See LICENSE file for details.

## Support

For issues, questions, or contributions, please open an issue in this repository.
