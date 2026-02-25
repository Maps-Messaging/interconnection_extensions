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

### Kafka Extension

**Module:** `kafka-extension/`

Bidirectional Apache Kafka bridge with configurable per-link routing rules (key strategy, partition, headers, and consumer overrides).

**Documentation:** See [kafka-extension/README.md](kafka-extension/README.md)

### Redis Extension

**Module:** `redis-extension/`

Redis Pub/Sub and Streams bridge using Lettuce, including a Redis -> MAPS -> CloudEvent -> MAPS -> Redis round-trip test harness that verifies payload, header, and typed metadata preservation.
Includes pull-side Pub/Sub and Streams consumer-group support with configurable stream polling and reconnect backoff controls.
Also supports MAPS-native pull metrics publication (`redis.metrics.*`) with alert thresholds for reconnect churn and stream pending depth.
Stream pull tuning is configurable per link (or top-level defaults) with:
`redis.stream.poll_ms` (default `500`), `redis.stream.min_poll_ms` (default `100`),
`redis.stream.batch_size` (default `32`), and `redis.stream.pending_sample_every` (default `1`).
Performance baselines are available in `redis-extension` as `@Tag("perf")` tests and can be run with `mvn -pl redis-extension -Dgroups=perf test`.

**Documentation and tested examples:** See [redis-extension/README.md](redis-extension/README.md)

**Example Configuration:** [redis-extension/src/main/resources/NetworkConnectionManager-example.yaml](redis-extension/src/main/resources/NetworkConnectionManager-example.yaml)

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

## Ops Quickstart (Kafka)

For full Kafka dependency placement, runtime library requirements, and contribution guidance, see:

- [kafka-extension/README.md](kafka-extension/README.md)

### Basic Kafka bridge config

```yaml
NetworkConnectionManager:
  global:

  data:
    -
      name: kafka_connection
      url: "kafka://localhost:9092"
      protocol: kafka
      plugin: true
      config:
        bootstrapServers: "localhost:9092"
        clientId: "maps-kafka-bridge"
        groupId: "maps-kafka-consumers"
        loopGuard.enabled: true
        loopGuard.maxHops: 8
      links:
        - direction: pull
          remote_namespace: "raw.events"
          local_namespace: "/streams/in/raw"
          include_schema: false
        - direction: push
          local_namespace: "/streams/out/enriched"
          remote_namespace: "enriched.events"
          include_schema: false
          routing.key_source: "header"
          routing.key_header: "tenantId"
```

### CloudEvent pipeline config

```yaml
NetworkConnectionManager:
  global:

  data:
    -
      name: kafka_cloudevents_connection
      url: "kafka://localhost:9092"
      protocol: kafka
      plugin: true
      config:
        bootstrapServers: "localhost:9092"
        loopGuard.enabled: true
        loopGuard.maxHops: 8
      links:
        - direction: pull
          remote_namespace: "raw.telemetry"
          local_namespace: "/cloudevents/in/raw"
          include_schema: false
          group_id: "maps-kafka-ce-raw"
        - direction: push
          local_namespace: "/cloudevents/out/wrapped"
          remote_namespace: "events.cloudevents"
          include_schema: false
          cloud_event.mode: "wrap"
          cloud_event.type: "io.maps.telemetry.event"
          cloud_event.source: "/maps/telemetry"
        - direction: push
          local_namespace: "/cloudevents/out/final"
          remote_namespace: "events.final"
          include_schema: false
          routing.key_source: "header"
          routing.key_header: "tenantId"
```

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
