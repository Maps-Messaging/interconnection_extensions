# Redis Extension

Redis Pub/Sub and Streams bridge for MAPS Messaging using Lettuce.

## Guarantees and Role

- Redis Pub/Sub is used as an ephemeral signaling/integration layer.
- Core messaging guarantees (durability, replay, batching policy, schema enforcement, and protocol bridging) remain inside MAPS.
- Do not treat Redis Pub/Sub as a reliable data plane.

## Tested Example Matrix

All scenarios below are automated and currently passing in `redis-extension` tests.

| Scenario | Config Pattern | Test |
|---|---|---|
| Redis -> MAPS pull (Pub/Sub) with typed/header preservation | `direction: pull`, `redis.mode: pubsub` | `registerRemoteLink_PubSub_DeliversInboundMessageToConfiguredLocalNamespace` |
| Redis -> MAPS pull (Streams) with consumer-group ack | `direction: pull`, `redis.mode: stream`, `redis.stream.*` | `registerRemoteLink_Stream_DeliversInboundMessageAndAcks` |
| Redis -> MAPS -> CloudEvent -> MAPS -> Redis (Pub/Sub) | two `push` links with `cloud_event.mode: wrap` on first hop | `pubSubRoundTrip_RedisToMapsToCloudEventToMapsToRedis_RetainsTypesAndHeaders` |
| Redis -> MAPS -> CloudEvent -> MAPS -> Redis (Streams) | two `push` links with `redis.mode: stream` | `streamRoundTrip_RedisToMapsToCloudEventToMapsToRedis_RetainsTypesAndHeaders` |
| Stream reconnect recovery | `redis.reconnect.*` on pull stream link | `registerRemoteLink_Stream_ReconnectsAfterForcedDisconnect_AndContinuesDelivery` |
| Stream throughput tuning variants | `redis.stream.poll_ms`, `redis.stream.min_poll_ms`, `redis.stream.batch_size`, `redis.stream.pending_sample_every` | `RedisProtocolPerformanceBaselineTest` (`@Tag("perf")`) |

## Configuration Examples

Full example file:
- `src/main/resources/NetworkConnectionManager-example.yaml`

### 1) Pull Pub/Sub (lightweight ingest bridge)

```yaml
links:
  - direction: pull
    remote_namespace: "events.inbound"
    local_namespace: "/redis/inbound/raw"
    redis.mode: "pubsub"
```

### 2) Pull Stream (default-safe profile)

```yaml
links:
  - direction: pull
    remote_namespace: "stream.inbound"
    local_namespace: "/redis/inbound/stream"
    redis.mode: "stream"
    redis.stream.group: "maps-redis-group"
    redis.stream.consumer: "maps-redis-consumer"
    redis.stream.poll_ms: 500
    redis.stream.min_poll_ms: 100
    redis.stream.batch_size: 32
    redis.stream.pending_sample_every: 1
```

### 3) Pull Stream (higher-throughput profile)

```yaml
links:
  - direction: pull
    remote_namespace: "stream.inbound.fast"
    local_namespace: "/redis/inbound/stream/fast"
    redis.mode: "stream"
    redis.stream.group: "maps-redis-group-fast"
    redis.stream.consumer: "maps-redis-consumer-fast"
    redis.stream.poll_ms: 25
    redis.stream.min_poll_ms: 1
    redis.stream.batch_size: 256
    redis.stream.pending_sample_every: 20
```

## Third-Party Redis Interop Demos

These are validated using a real Redis server and Lettuce client test harness (independent of MAPS internals except the extension boundary).

### Demo A: Third-party producer -> MAPS (Pub/Sub pull)

- External producer publishes `RedisWireEnvelope` bytes to Redis channel.
- MAPS Redis extension consumes and maps typed/header fields into MAPS data map.
- Verified by test:
  - `registerRemoteLink_PubSub_DeliversInboundMessageToConfiguredLocalNamespace`

### Demo B: Third-party producer -> MAPS (Streams pull)

- External producer does `XADD stream maps <RedisWireEnvelope-bytes>`.
- MAPS Redis extension consumes via consumer group and acknowledges.
- Verified by test:
  - `registerRemoteLink_Stream_DeliversInboundMessageAndAcks`

### Demo C: MAPS -> Redis CloudEvent stream for third-party consumers

- MAPS push link with `cloud_event.mode: wrap` writes CloudEvent headers (`ce_*`) plus payload into Redis envelope.
- Any Redis client can `XREAD` and decode envelope bytes.
- Verified by test:
  - `streamRoundTrip_RedisToMapsToCloudEventToMapsToRedis_RetainsTypesAndHeaders`

## Running Tests

Standard suite:

```bash
mvn -q -pl redis-extension test
```

Perf baselines:

```bash
mvn -q -pl redis-extension -Dsurefire.excludedGroups= -Dgroups=perf test
```

