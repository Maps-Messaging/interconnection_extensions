# Kafka Extension

MapsMessaging extension for bridging MAPS namespaces to Apache Kafka topics with configurable routing rules, CloudEvent wrapping, typed metadata preservation, and loop protection.

## What This Extension Does

- Pushes MAPS events to Kafka topics.
- Pulls Kafka records into MAPS namespaces.
- Preserves typed MAPS fields over Kafka via `maps.type.*` and `maps.data.*` headers.
- Supports CloudEvent wrapping (`ce_*` headers) for transformation pipelines.
- Prevents accidental ping-pong loops with configurable loop guard.

## Runtime Libraries and Placement

This module compiles with `kafka-clients` as `provided` scope. That means the Kafka client libraries must exist in your MAPS runtime classpath.

### Required libraries

At minimum, include:

- `kafka-extension-1.0.0-SNAPSHOT.jar` (this plugin)
- `kafka-clients-3.8.1.jar`

Kafka client transitive dependencies are usually also required at runtime (exact set can vary by distribution and broker features), typically including:

- `slf4j-api`
- `lz4-java`
- `snappy-java`
- `zstd-jni`

If your deployment does not already provide these, add them alongside Kafka client jars.

### Where to place files in MAPS installation

Use your MAPS install root as `MAPS_HOME`.

Recommended layout:

```text
MAPS_HOME/
  plugins/
    kafka-extension-1.0.0-SNAPSHOT.jar
  lib/
    kafka-clients-3.8.1.jar
    slf4j-api-*.jar
    lz4-java-*.jar
    snappy-java-*.jar
    zstd-jni-*.jar
```

Notes:

- If your MAPS distribution uses a different shared runtime-lib directory, place Kafka jars there.
- Keep extension jar in `plugins/`.
- Do not place source jars in runtime paths.

### Verify library loading

1. Start MAPS.
2. Confirm extension initialization log lines reference the Kafka endpoint.
3. If you see `ClassNotFoundException` / `NoClassDefFoundError` for Kafka classes, your runtime jars are not on MAPS classpath.

## Build and Install

Build only this module:

```bash
mvn clean install -pl kafka-extension
```

Install to MAPS:

1. Copy `kafka-extension/target/kafka-extension-1.0.0-SNAPSHOT.jar` to `MAPS_HOME/plugins/`.
2. Ensure Kafka runtime jars are in `MAPS_HOME/lib/` (or your MAPS shared lib path).
3. Add endpoint config to `NetworkConnectionManager.yaml`.
4. Restart MAPS.

## Configuration Reference

### Endpoint example

```yaml
- name: kafka_bridge
  url: "kafka://localhost:9092/"
  protocol: kafka
  plugin: true
  config:
    bootstrapServers: "localhost:9092"
    clientId: "maps-kafka-bridge"
    groupId: "maps-kafka"
    loopGuard.enabled: true
    loopGuard.maxHops: 8
```

### Producer settings (`config`)

- `bootstrapServers`: broker list. Fallback: URL host:port.
- `clientId`: producer client ID.
- `acks`: `all`, `1`, `0`.
- `retries`: producer retries.
- `lingerMs`: producer linger.
- `enableIdempotence`: idempotent producer.

### Consumer settings (`config`)

- `groupId`: default consumer group.
- `offsetReset`: `earliest` or `latest`.
- `maxPollRecords`: default consumer batch size.
- `enableAutoCommit`: default `false`.
- `pollTimeoutMs`: default `250`.
- `pollIntervalMs`: default `200`.

### Loop guard settings (`config`)

- `loopGuard.enabled`: default `true`.
- `loopGuard.maxHops`: default `8`.

Per push link:

- `loop.allow_same_topic`: default `false`.

### Routing rules (push link)

- `routing.key_source`: `none` | `fixed` | `header` | `correlation` | `local_namespace`
- `routing.key_value`: used when `routing.key_source=fixed`
- `routing.key_header`: MAPS field name used when `routing.key_source=header`
- `routing.partition`: optional explicit partition
- `routing.timestamp_source`: `none` | `now` | `message`
- `routing.headers`: static Kafka headers map

### Pull link overrides

- `group_id`
- `offset_reset`
- `max_poll_records`
- `consumer_client_id`

### CloudEvent mode (push link)

- `cloud_event.mode`: `none` | `wrap`
- `cloud_event.type`: default event type when wrapping
- `cloud_event.source`: default event source when wrapping

When `cloud_event.mode=wrap`, the extension emits CloudEvent headers and keeps payload unchanged.

## Transformation Patterns

### Kafka -> MAPS -> Kafka (different topic)

1. Pull from Kafka `raw.events` to MAPS `/streams/in/raw`.
2. Transform/enrich in MAPS.
3. Push `/streams/out/enriched` to Kafka `enriched.events`.

### Kafka -> CloudEvent topic -> Kafka

1. Pull raw Kafka events into MAPS.
2. Push with `cloud_event.mode: wrap` to `events.cloudevents`.
3. Push transformed/normalized MAPS output to `events.final`.

### MAPS -> CloudEvent topic -> Kafka

1. MAPS event enters `/cloudevents/out/wrapped`.
2. Extension writes CloudEvent-wrapped record to intermediate Kafka topic.
3. Event re-enters MAPS and is pushed to final Kafka topic, retaining payload and typed metadata.

See full runnable config at:

- `kafka-extension/src/main/resources/NetworkConnectionManager-example.yaml`

## Contribution Requirements

### Prerequisites

- JDK compatible with repository build (project targets Java 21; module compiles for Java 11 bytecode).
- Maven 3.6+

### Code requirements

- Preserve Apache license headers in new Java/resources files.
- Keep extension behavior configuration-driven (no hardcoded env-specific endpoints or credentials).
- Maintain backward compatibility for existing routing keys where possible.
- Keep changes scoped to `kafka-extension` unless cross-module updates are required.

### Test requirements

Before opening a PR:

```bash
mvn -q -pl kafka-extension test
mvn -q -pl kafka-extension -DskipTests compile
```

Expected coverage for behavioral changes:

- Routing rule resolution.
- Loop guard behavior.
- Typed metadata round-trips.
- CloudEvent wrapping paths (when touched).

### Documentation requirements

If behavior/config keys change, update all of:

- `kafka-extension/README.md`
- `kafka-extension/src/main/resources/NetworkConnectionManager-example.yaml`
- `config/NetworkConnectionManager.yaml` (shared template section)

### Pull request checklist

- [ ] Module builds and tests pass.
- [ ] No generated artifacts committed (`target/`, logs, IDE files).
- [ ] New config keys documented with defaults.
- [ ] Example YAML updated for new behavior.
- [ ] Changes verified for both push and pull direction impact.
