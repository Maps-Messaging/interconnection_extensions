# CLAUDE.md

Agent guidance for this repository (`interconnection_extensions`).

## Current Repo State

- Primary branch: `development`
- Maven multi-module project with five extensions:
  - `aws-sns-extension`
  - `ibm-mq-extension`
  - `pulsar-extension`
  - `ros-extension`
  - `v2x-step-extension`
- Current `origin/development` sync: no ahead/behind commits after fetch.

## What This Codebase Does

This repo provides MapsMessaging extension protocols that bridge MAPS topics/namespaces to external systems.
The core pattern in every module is:

1. `*ProtocolFactory` creates and registers the extension protocol.
2. `*Protocol` extends `io.mapsmessaging.network.protocol.impl.extension.Extension`.
3. `*LogMessages` centralizes structured log messages.

Common extension lifecycle:
- `initialise()`
- `registerLocalLink()`
- `registerRemoteLink()`
- `outbound()`
- `close()`

## Build and Test Requirements

- Java/Maven:
  - Parent POM sets `maven.compiler.release=21`.
  - Most module POMs set `maven.compiler.release=11` (respect module-level setting when changing module code).
- Build all modules:
  - `mvn clean install`
- Build one module:
  - `mvn clean install -pl v2x-step-extension`
- Run tests for V2X STEP module:
  - `mvn -pl v2x-step-extension test`
- Optional security scan:
  - `NVD_API_KEY=... mvn dependency-check:check`

## External Dependency Requirements

- MapsMessaging snapshot dependencies resolve from:
  - `https://repository.mapsmessaging.io/repository/maps_snapshots/`
- V2X STEP module requires a local proprietary SDK jar:
  - `v2x-step-extension/lib/v2xsdk4java-3.1.0.jar`
  - Declared as a `system` dependency in `v2x-step-extension/pom.xml`

## V2X STEP: Latest Behavioral Contract (Important)

Do not treat this module as simple payload passthrough. Current behavior is:

- Push flow (`MAPS -> STEP`):
  - `outbound()` resolves binding by `local_namespace` key.
  - Extracts DENM parameters from message (`dataMap` first, then XML payload).
  - Converts ETSI coordinate units when needed.
  - Triggers SDK via `V2xStepSdkAdapter.triggerDenm(...)`.

- Pull flow (`STEP -> MAPS`) is implemented:
  - `registerRemoteLink()` creates `PullBinding`.
  - `DenmEventHandler` receives SDK DENM events.
  - `handleInboundDenm(...)` serializes DENM to JSON (default) or XML.
  - Publishes inbound data via `inbound(destination, message)`.

- Required config invariants:
  - URL format: `step://<STEP_INSTANCE>` (instance is taken from URL host).
  - `denmService.enabled` must be true for DENM links.
  - `service_type` must be present per link (push and pull).
  - `denmService.publishGroup` / `subscribeGroup` required unless per-link override is provided.

- Supported pull-link options:
  - `subscribe_group` (optional override)
  - `filter_own_messages` (default `true`)
  - `output_format` (`json` default, `xml` optional)

- Schema update topics are ignored in outbound:
  - Destination starts with `$schema/` or `$SCHEMA/`.

## Module Notes

- `aws-sns-extension`: SNS bridge using AWS SDK v2.
- `ibm-mq-extension`: IBM MQ bridge using `com.ibm.mq.allclient`.
- `pulsar-extension`: Apache Pulsar bridge using `pulsar-client`.
- `ros-extension`: ROS 1/2 bridge scaffold with schema-aware envelope convention and strict/passthrough schema modes.
- `v2x-step-extension`: richest logic and only module with unit tests currently (`V2xStepProtocolOutboundTest`).

## ROS Extension Contract (New)

- Protocol name / transport: `ros`
- Purpose:
  - Bridge MAPS links to ROS topics with context-preserving payload handling.
  - Preserve raw ROS payload bytes for reinjection (`contentType=application/x-ros-binary`).
  - Emit schema metadata to support cross-protocol translation.

- Schema convention emitted in MAPS `dataMap`:
  - `maps.schema.kind = ros`
  - `maps.schema.id = ros://<version>/<package>/<type>`
  - `ros.version`, `ros.package`, `ros.type`, `ros.topic`
  - `ros.md5` (ROS1, when available), `ros.qos` (ROS2, when available), `ros.context`

- Config behavior:
  - `rosVersion`: `1 | 2 | auto`
  - `schema_mode`: `strict | passthrough` (default strict)
  - In strict mode, `ros_type` is required for both push and pull links.

- Current adapter strategy:
  - Uses `RosClientAdapter` boundary.
  - `ReflectiveJRosAdapter` currently provides compatibility-focused scaffold behavior and loopback testability.
  - Live ROS runtime integration expects jrosclient jars on plugin classpath.

## Agent Working Rules for This Repo

- Prefer targeted module builds/tests instead of full reactor builds for quick validation.
- Keep link-config behavior backward compatible (`links` lookup + top-level fallback logic in V2X STEP).
- Preserve structured logging patterns (`Logger` + `*LogMessages` constants).
- Avoid committing generated artifacts and local IDE/state files (e.g. `target/`, `.idea/`, `.vscode/`, logs).
- When changing V2X STEP extraction or routing logic, update/add tests in:
  - `v2x-step-extension/src/test/java/io/mapsmessaging/network/protocol/impl/v2x_step/V2xStepProtocolOutboundTest.java`

## Quick Checklist Before Finalizing Changes

- Does the extension still honor configured link direction and namespaces?
- Are required config fields validated with clear errors?
- Are resources closed safely in `close()` (including SDK-backed services)?
- Did you run module-level build/tests for touched modules?
- [ ] Handle exceptions gracefully (log and wrap in `IOException` when appropriate)

### Logging Pattern

```java
// Define log messages
public class MyLogMessages {
  public static final LogMessage MY_INIT =
      new LogMessage("INIT", "Extension initialized");
  public static final LogMessage MY_ERROR =
      new LogMessage("ERROR", "Error: {}", LogLevel.ERROR);
}

// Use in protocol
logger.log(MyLogMessages.MY_INIT);
logger.log(MyLogMessages.MY_ERROR, exception);
```

### Configuration Access Pattern

```java
Map<String, Object> cfg = protocolConfig.getConfig();
String param = cfg.getOrDefault("paramName", "defaultValue").toString();
boolean flag = Boolean.parseBoolean(cfg.getOrDefault("flag", false).toString());
int number = Integer.parseInt(cfg.get("number").toString());
```

## Dependencies

### Provided by MapsMessaging Server

These dependencies are `scope: provided` and available at runtime:

- `io.mapsmessaging:maps` - Core messaging API
- `io.mapsmessaging:simple_logging` - Logging framework
- `io.mapsmessaging:dynamic_storage` - Storage abstractions
- `io.mapsmessaging:jms_selector_parser` - Message filtering
- `io.mapsmessaging:non_block_task_scheduler` - Async task scheduling

### External Client Libraries

Each extension includes its own client library dependency:
- V2X STEP: `com.vodafone:v2xsdk4java` (system scope, local JAR)
- Pulsar: `org.apache.pulsar:pulsar-client`
- AWS SNS: `software.amazon.awssdk:sns`
- IBM MQ: IBM MQ client JARs
- ROS: jrosclient ecosystem (runtime-provided; adapter boundary in code)

## Deployment

1. Build extension: `mvn clean install -pl extension-name`
2. Copy JAR from `target/` to MapsMessaging `plugins/` directory
3. Add extension configuration to MapsMessaging `NetworkConnectionManager.yaml`
4. Restart MapsMessaging server
5. Verify initialization in MapsMessaging logs

Extensions are loaded as plugins via Java ServiceLoader mechanism and registered with the protocol factory registry.

## Repository Structure

```
interconnection_extensions/
├── pom.xml                        # Parent POM (multi-module)
├── README.md                      # Repository overview
├── CLAUDE.md                      # This file
├── aws-sns-extension/             # AWS SNS extension module
├── ibm-mq-extension/              # IBM MQ extension module
├── pulsar-extension/              # Apache Pulsar extension module
├── ros-extension/                 # ROS 1/2 extension module
└── v2x-step-extension/            # Vodafone V2X STEP extension module
    ├── pom.xml
    ├── README.md                  # Module-specific documentation
    ├── lib/                       # Proprietary SDK JARs (not in Git)
    └── src/main/
        ├── java/.../v2x_step/
        │   ├── V2xStepProtocol.java
        │   ├── V2xStepProtocolFactory.java
        │   ├── V2xStepLogMessages.java
        │   └── FakeLocationProvider.java
        └── resources/
            └── NetworkConnectionManager-example.yaml
```

## Important Constraints

### Extension Scope Philosophy

Extensions are **pure routing components**. They should:
- Route messages between MapsMessaging and external systems
- Handle protocol-specific connection management
- Perform minimal necessary format conversion (byte[] ↔ external format)

Extensions should NOT:
- Transform message semantics or schemas
- Provide business logic or enrichment services
- Implement location providers or other domain-specific services (unless required by external SDK)
- Validate message content beyond what's needed for routing

This keeps extensions simple, maintainable, and focused on integration.

### Configuration Philosophy

- All extension behavior must be configurable via `NetworkConnectionManager.yaml`
- No hardcoded credentials, endpoints, or service parameters
- Provide sensible defaults where possible
- Document all configuration parameters in example YAML files

### Java Version

- Parent POM specifies JDK 21 (`maven.compiler.release: 21`)
- Individual modules may override (e.g., V2X STEP uses JDK 11)
- Target platform is JDK 17+ for runtime compatibility
