package io.mapsmessaging.network.protocol.impl.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.models.stream.PendingMessages;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.mapsmessaging.api.MessageBuilder;
import io.mapsmessaging.api.message.Message;
import io.mapsmessaging.api.message.TypedData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
/**
 * End-to-end harness for Redis extension behavior across pub/sub and streams paths.
 */
class RedisProtocolRoundTripTest {

  private static final RedisCodec<String, byte[]> STRING_BYTE_CODEC = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

  @Container
  @SuppressWarnings("resource")
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
      .withExposedPorts(6379);

  private RedisProtocol protocol;

  /**
   * Closes protocol resources between tests.
   *
   * @throws Exception when shutdown fails
   */
  @AfterEach
  void tearDown() throws Exception {
    if (protocol != null) {
      protocol.close();
    }
  }

  /**
   * Validates pub/sub round-trip with CloudEvent wrap/unwrap and type/header preservation.
   *
   * @throws Exception test execution error
   */
  @Test
  void pubSubRoundTrip_RedisToMapsToCloudEventToMapsToRedis_RetainsTypesAndHeaders() throws Exception {
    String redisUrl = redisUrl();
    protocol = new RedisProtocol(redisUrl, createPubSubCloudEventConfig());
    protocol.initialise();
    protocol.registerLocalLink("/ce/wrap");
    protocol.registerLocalLink("/ce/final");

    RedisClient client = RedisClient.create(redisUrl);
    try (StatefulRedisConnection<String, byte[]> connection = client.connect(STRING_BYTE_CODEC);
         StatefulRedisPubSubConnection<String, byte[]> pubSub = client.connectPubSub(STRING_BYTE_CODEC)) {

      RedisCommands<String, byte[]> commands = connection.sync();
      BlockingQueue<byte[]> rawQueue = new LinkedBlockingQueue<>();
      BlockingQueue<byte[]> wrapQueue = new LinkedBlockingQueue<>();
      BlockingQueue<byte[]> finalQueue = new LinkedBlockingQueue<>();

      pubSub.addListener(new RedisPubSubAdapter<>() {
        @Override
        public void message(String channel, byte[] message) {
          if ("raw.in".equals(channel)) {
            rawQueue.add(message);
          } else if ("events.cloudevents".equals(channel)) {
            wrapQueue.add(message);
          } else if ("events.final".equals(channel)) {
            finalQueue.add(message);
          }
        }
      });
      pubSub.sync().subscribe("raw.in", "events.cloudevents", "events.final");

      byte[] payload = "payload-pubsub".getBytes(StandardCharsets.UTF_8);
      Map<String, byte[]> inboundHeaders = new LinkedHashMap<>();
      inboundHeaders.put("x-trace-id", "trace-pubsub-1".getBytes(StandardCharsets.UTF_8));
      inboundHeaders.put("x-region", "eu-west".getBytes(StandardCharsets.UTF_8));
      inboundHeaders.put("maps.type.speed", "INT".getBytes(StandardCharsets.UTF_8));
      inboundHeaders.put("maps.data.speed", "88".getBytes(StandardCharsets.UTF_8));
      inboundHeaders.put("maps.type.valid", "BOOLEAN".getBytes(StandardCharsets.UTF_8));
      inboundHeaders.put("maps.data.valid", "true".getBytes(StandardCharsets.UTF_8));
      inboundHeaders.put("maps.contentType", "application/json".getBytes(StandardCharsets.UTF_8));

      byte[] inboundEnvelope = RedisWireEnvelope.of(payload, inboundHeaders).encode();
      commands.publish("raw.in", inboundEnvelope);

      byte[] rawRedisMessage = poll(rawQueue);
      Message afterRedisIngress = protocol.convertInboundEnvelopeForTest(rawRedisMessage, "raw.in", "pubsub");
      protocol.outbound("/ce/wrap", afterRedisIngress);

      byte[] wrappedEnvelopeBytes = poll(wrapQueue);
      RedisWireEnvelope wrappedEnvelope = RedisWireEnvelope.decode(wrappedEnvelopeBytes);
      assertEquals("1.0", utf8(wrappedEnvelope.headers().get("ce_specversion")));
      assertEquals("io.test.redis", utf8(wrappedEnvelope.headers().get("ce_type")));
      assertEquals("/tests/redis", utf8(wrappedEnvelope.headers().get("ce_source")));

      Message afterCloudEvent = protocol.convertInboundEnvelopeForTest(wrappedEnvelopeBytes, "events.cloudevents", "pubsub");
      protocol.outbound("/ce/final", afterCloudEvent);

      byte[] finalEnvelopeBytes = poll(finalQueue);
      RedisWireEnvelope finalEnvelope = RedisWireEnvelope.decode(finalEnvelopeBytes);

      assertArrayEquals(payload, finalEnvelope.payload());
      assertEquals("trace-pubsub-1", utf8(finalEnvelope.headers().get("x-trace-id")));
      assertEquals("eu-west", utf8(finalEnvelope.headers().get("x-region")));
      assertEquals("INT", utf8(finalEnvelope.headers().get("maps.type.speed")));
      assertEquals("88", utf8(finalEnvelope.headers().get("maps.data.speed")));
      assertEquals("BOOLEAN", utf8(finalEnvelope.headers().get("maps.type.valid")));
      assertEquals("true", utf8(finalEnvelope.headers().get("maps.data.valid")));
      assertEquals("application/json", utf8(finalEnvelope.headers().get("maps.contentType")));
      assertEquals("1.0", utf8(finalEnvelope.headers().get("ce_specversion")));
    } finally {
      client.shutdown();
    }
  }

  /**
   * Validates stream round-trip including binary payload preservation and CloudEvent headers.
   *
   * @throws Exception test execution error
   */
  @Test
  void streamRoundTrip_RedisToMapsToCloudEventToMapsToRedis_RetainsTypesAndHeaders() throws Exception {
    String redisUrl = redisUrl();
    protocol = new RedisProtocol(redisUrl, createStreamCloudEventConfig());
    protocol.initialise();
    protocol.registerLocalLink("/stream/ce/wrap");
    protocol.registerLocalLink("/stream/ce/final");

    RedisClient client = RedisClient.create(redisUrl);
    try (StatefulRedisConnection<String, byte[]> connection = client.connect(STRING_BYTE_CODEC)) {
      RedisCommands<String, byte[]> commands = connection.sync();

      byte[] payload = new byte[]{0x00, 0x01, 0x7F, (byte) 0xFE};
      Map<String, byte[]> inboundHeaders = new LinkedHashMap<>();
      inboundHeaders.put("x-trace-id", "trace-stream-1".getBytes(StandardCharsets.UTF_8));
      inboundHeaders.put("maps.type.count", "LONG".getBytes(StandardCharsets.UTF_8));
      inboundHeaders.put("maps.data.count", "123".getBytes(StandardCharsets.UTF_8));
      inboundHeaders.put("maps.type.active", "BOOLEAN".getBytes(StandardCharsets.UTF_8));
      inboundHeaders.put("maps.data.active", "true".getBytes(StandardCharsets.UTF_8));
      inboundHeaders.put("maps.contentType", "application/octet-stream".getBytes(StandardCharsets.UTF_8));

      byte[] inboundEnvelope = RedisWireEnvelope.of(payload, inboundHeaders).encode();
      commands.xadd("stream.raw.in", Map.of("maps", inboundEnvelope));

      byte[] rawEnvelope = readSingleEnvelope(commands, "stream.raw.in");
      Message afterRedisIngress = protocol.convertInboundEnvelopeForTest(rawEnvelope, "stream.raw.in", "stream");
      protocol.outbound("/stream/ce/wrap", afterRedisIngress);

      byte[] wrappedEnvelope = readSingleEnvelope(commands, "stream.events.cloudevents");
      RedisWireEnvelope wrappedDecoded = RedisWireEnvelope.decode(wrappedEnvelope);
      assertEquals("1.0", utf8(wrappedDecoded.headers().get("ce_specversion")));
      assertEquals("io.test.redis.stream", utf8(wrappedDecoded.headers().get("ce_type")));

      Message afterCloudEvent = protocol.convertInboundEnvelopeForTest(wrappedEnvelope, "stream.events.cloudevents", "stream");
      protocol.outbound("/stream/ce/final", afterCloudEvent);

      byte[] finalEnvelopeBytes = readSingleEnvelope(commands, "stream.events.final");
      RedisWireEnvelope finalEnvelope = RedisWireEnvelope.decode(finalEnvelopeBytes);

      assertArrayEquals(payload, finalEnvelope.payload());
      assertEquals("trace-stream-1", utf8(finalEnvelope.headers().get("x-trace-id")));
      assertEquals("LONG", utf8(finalEnvelope.headers().get("maps.type.count")));
      assertEquals("123", utf8(finalEnvelope.headers().get("maps.data.count")));
      assertEquals("BOOLEAN", utf8(finalEnvelope.headers().get("maps.type.active")));
      assertEquals("true", utf8(finalEnvelope.headers().get("maps.data.active")));
      assertEquals("application/octet-stream", utf8(finalEnvelope.headers().get("maps.contentType")));
      assertEquals("1.0", utf8(finalEnvelope.headers().get("ce_specversion")));
    } finally {
      client.shutdown();
    }
  }

  /**
   * Validates CloudEvent default field generation when optional values are missing.
   *
   * @throws Exception test execution error
   */
  @Test
  void pubSubRoundTrip_WrapGeneratesCloudEventDefaults_WhenOptionalFieldsMissing() throws Exception {
    String redisUrl = redisUrl();
    protocol = new RedisProtocol(redisUrl, createPubSubCloudEventConfig());
    protocol.initialise();
    protocol.registerLocalLink("/ce/wrap");

    RedisClient client = RedisClient.create(redisUrl);
    try (StatefulRedisConnection<String, byte[]> connection = client.connect(STRING_BYTE_CODEC);
         StatefulRedisPubSubConnection<String, byte[]> pubSub = client.connectPubSub(STRING_BYTE_CODEC)) {
      RedisCommands<String, byte[]> commands = connection.sync();
      BlockingQueue<byte[]> wrapQueue = new LinkedBlockingQueue<>();

      pubSub.addListener(new RedisPubSubAdapter<>() {
        @Override
        public void message(String channel, byte[] message) {
          if ("events.cloudevents".equals(channel)) {
            wrapQueue.add(message);
          }
        }
      });
      pubSub.sync().subscribe("events.cloudevents");

      byte[] payload = "defaults".getBytes(StandardCharsets.UTF_8);
      Map<String, byte[]> inboundHeaders = new LinkedHashMap<>();
      inboundHeaders.put("maps.type.count", "INT".getBytes(StandardCharsets.UTF_8));
      inboundHeaders.put("maps.data.count", "1".getBytes(StandardCharsets.UTF_8));
      inboundHeaders.put("maps.contentType", "application/json".getBytes(StandardCharsets.UTF_8));

      Message ingress = protocol.convertInboundEnvelopeForTest(
          RedisWireEnvelope.of(payload, inboundHeaders).encode(),
          "raw.in",
          "pubsub"
      );
      assertNull(ingress.getDataMap().get("cloudevents.id"));
      assertNull(ingress.getDataMap().get("cloudevents.time"));

      protocol.outbound("/ce/wrap", ingress);
      RedisWireEnvelope wrapped = RedisWireEnvelope.decode(poll(wrapQueue));

      assertEquals("1.0", utf8(wrapped.headers().get("ce_specversion")));
      assertEquals("io.test.redis", utf8(wrapped.headers().get("ce_type")));
      assertEquals("/tests/redis", utf8(wrapped.headers().get("ce_source")));
      assertNotNull(utf8(wrapped.headers().get("ce_id")));
      assertNotNull(utf8(wrapped.headers().get("ce_time")));
      assertEquals("application/json", utf8(wrapped.headers().get("ce_datacontenttype")));
    } finally {
      client.shutdown();
    }
  }

  /**
   * Validates outbound behavior across transient bridge failures without metadata drift.
   */
  @Test
  void outbound_RecoversAfterTransientBridgeFailure_WithoutTypeOrHeaderLoss() {
    protocol = new RedisProtocol("redis://localhost:6379/", createPubSubCloudEventConfig());
    protocol.registerLocalLink("/ce/final");

    FlakyBridge bridge = new FlakyBridge();
    protocol.setRedisBridgeForTest(bridge);
    bridge.fail = true;

    Map<String, TypedData> map = new LinkedHashMap<>();
    map.put("speed", new TypedData(88));
    map.put("active", new TypedData(true));
    map.put("redis.header.x-trace-id", new TypedData("trace-reconnect"));
    Message outbound = new MessageBuilder()
        .setDataMap(map)
        .setOpaqueData("payload-reconnect".getBytes(StandardCharsets.UTF_8))
        .setContentType("application/json")
        .build();

    assertDoesNotThrow(() -> protocol.outbound("/ce/final", outbound));
    assertTrue(bridge.published.isEmpty());

    bridge.fail = false;
    assertDoesNotThrow(() -> protocol.outbound("/ce/final", outbound));

    byte[] envelope = bridge.published.get("events.final");
    assertNotNull(envelope);
    RedisWireEnvelope decoded = RedisWireEnvelope.decode(envelope);
    assertArrayEquals("payload-reconnect".getBytes(StandardCharsets.UTF_8), decoded.payload());
    assertEquals("trace-reconnect", utf8(decoded.headers().get("x-trace-id")));
    assertEquals("INT", utf8(decoded.headers().get("maps.type.speed")));
    assertEquals("88", utf8(decoded.headers().get("maps.data.speed")));
    assertEquals("BOOLEAN", utf8(decoded.headers().get("maps.type.active")));
    assertEquals("true", utf8(decoded.headers().get("maps.data.active")));
    assertEquals("application/json", utf8(decoded.headers().get("maps.contentType")));
  }

  /**
   * Validates that pub/sub pull links route inbound messages to configured MAPS namespace.
   *
   * @throws Exception test execution error
   */
  @Test
  void registerRemoteLink_PubSub_DeliversInboundMessageToConfiguredLocalNamespace() throws Exception {
    String redisUrl = redisUrl();
    CapturingRedisProtocol capturing = new CapturingRedisProtocol(redisUrl, createPullPubSubConfig());
    protocol = capturing;
    protocol.initialise();
    protocol.registerRemoteLink("pull.raw.in", null);

    RedisClient client = RedisClient.create(redisUrl);
    try (StatefulRedisConnection<String, byte[]> connection = client.connect(STRING_BYTE_CODEC)) {
      RedisCommands<String, byte[]> commands = connection.sync();

      Map<String, byte[]> headers = new LinkedHashMap<>();
      headers.put("maps.type.speed", "INT".getBytes(StandardCharsets.UTF_8));
      headers.put("maps.data.speed", "57".getBytes(StandardCharsets.UTF_8));
      headers.put("x-trace-id", "trace-pull-pubsub".getBytes(StandardCharsets.UTF_8));
      byte[] payload = "pull-pubsub".getBytes(StandardCharsets.UTF_8);

      commands.publish("pull.raw.in", RedisWireEnvelope.of(payload, headers).encode());

      InboundCapture received = capturing.await();
      assertEquals("/maps/in/pubsub", received.localNamespace);
      assertArrayEquals(payload, received.message.getOpaqueData());
      assertEquals("pubsub", received.message.getDataMap().get("redis.transport").getData());
      assertEquals(57, received.message.getDataMap().get("speed").getData());
      assertEquals("trace-pull-pubsub", received.message.getDataMap().get("redis.header.x-trace-id").getData());
      assertNotNull(received.message.getDataMap().get("redis.pull.received_count"));
      assertEquals(1L, received.message.getDataMap().get("redis.pull.received_count").getData());
    } finally {
      client.shutdown();
    }
  }

  /**
   * Validates stream pull links consume, route and ack records.
   *
   * @throws Exception test execution error
   */
  @Test
  void registerRemoteLink_Stream_DeliversInboundMessageAndAcks() throws Exception {
    String redisUrl = redisUrl();
    CapturingRedisProtocol capturing = new CapturingRedisProtocol(redisUrl, createPullStreamConfig());
    protocol = capturing;
    protocol.initialise();
    protocol.registerRemoteLink("pull.stream.in", null);

    RedisClient client = RedisClient.create(redisUrl);
    try (StatefulRedisConnection<String, byte[]> connection = client.connect(STRING_BYTE_CODEC)) {
      RedisCommands<String, byte[]> commands = connection.sync();

      Map<String, byte[]> headers = new LinkedHashMap<>();
      headers.put("maps.type.active", "BOOLEAN".getBytes(StandardCharsets.UTF_8));
      headers.put("maps.data.active", "true".getBytes(StandardCharsets.UTF_8));
      headers.put("x-trace-id", "trace-pull-stream".getBytes(StandardCharsets.UTF_8));
      byte[] payload = "pull-stream".getBytes(StandardCharsets.UTF_8);

      commands.xadd("pull.stream.in", Map.of("maps", RedisWireEnvelope.of(payload, headers).encode()));

      InboundCapture received = capturing.await();
      assertEquals("/maps/in/stream", received.localNamespace);
      assertArrayEquals(payload, received.message.getOpaqueData());
      assertEquals("stream", received.message.getDataMap().get("redis.transport").getData());
      assertEquals(true, received.message.getDataMap().get("active").getData());
      assertEquals("trace-pull-stream", received.message.getDataMap().get("redis.header.x-trace-id").getData());
      assertNotNull(received.message.getDataMap().get("redis.stream.ack_latency_ms"));

      PendingMessages pending = commands.xpending("pull.stream.in", "maps-pull-group");
      assertEquals(0, pending.getCount());

      RedisProtocol.PullStats stats = capturing.pullStatsForTest("pull.stream.in");
      assertNotNull(stats);
      assertTrue(stats.receivedCount >= 1);
      assertEquals(0L, stats.pendingCount);
    } finally {
      client.shutdown();
    }
  }

  /**
   * Validates stream pull reconnect path after forced disconnect.
   *
   * @throws Exception test execution error
   */
  @Test
  void registerRemoteLink_Stream_ReconnectsAfterForcedDisconnect_AndContinuesDelivery() throws Exception {
    String redisUrl = redisUrl();
    CapturingRedisProtocol capturing = new CapturingRedisProtocol(redisUrl, createPullStreamConfig());
    protocol = capturing;
    protocol.initialise();
    protocol.registerRemoteLink("pull.stream.in", null);

    RedisClient client = RedisClient.create(redisUrl);
    try (StatefulRedisConnection<String, byte[]> connection = client.connect(STRING_BYTE_CODEC)) {
      RedisCommands<String, byte[]> commands = connection.sync();

      capturing.forceCloseStreamConnectionForTest("pull.stream.in");

      Map<String, byte[]> headers = new LinkedHashMap<>();
      headers.put("maps.type.count", "LONG".getBytes(StandardCharsets.UTF_8));
      headers.put("maps.data.count", "222".getBytes(StandardCharsets.UTF_8));
      headers.put("x-trace-id", "trace-reconnect-stream".getBytes(StandardCharsets.UTF_8));
      byte[] payload = "pull-stream-reconnect".getBytes(StandardCharsets.UTF_8);
      commands.xadd("pull.stream.in", Map.of("maps", RedisWireEnvelope.of(payload, headers).encode()));

      InboundCapture received = capturing.await();
      assertEquals("/maps/in/stream", received.localNamespace);
      assertArrayEquals(payload, received.message.getOpaqueData());
      assertEquals(222L, received.message.getDataMap().get("count").getData());
      assertEquals("trace-reconnect-stream", received.message.getDataMap().get("redis.header.x-trace-id").getData());

      RedisProtocol.PullStats stats = capturing.pullStatsForTest("pull.stream.in");
      assertNotNull(stats);
      assertTrue(stats.reconnectCount >= 1);
      assertTrue(stats.receivedCount >= 1);
    } finally {
      client.shutdown();
    }
  }

  /**
   * Validates periodic MAPS-native metrics publication and reconnect alert flagging.
   *
   * @throws Exception test execution error
   */
  @Test
  void metricsPublisher_EmitsMapsNativeMetricEvents_WithReconnectAlert() throws Exception {
    String redisUrl = redisUrl();
    CapturingRedisProtocol capturing = new CapturingRedisProtocol(redisUrl, createPullStreamMetricsConfig());
    protocol = capturing;
    protocol.initialise();
    protocol.registerRemoteLink("pull.stream.in", null);

    RedisClient client = RedisClient.create(redisUrl);
    try (StatefulRedisConnection<String, byte[]> connection = client.connect(STRING_BYTE_CODEC)) {
      RedisCommands<String, byte[]> commands = connection.sync();

      capturing.forceCloseStreamConnectionForTest("pull.stream.in");
      Map<String, byte[]> headers = new LinkedHashMap<>();
      headers.put("maps.type.count", "INT".getBytes(StandardCharsets.UTF_8));
      headers.put("maps.data.count", "5".getBytes(StandardCharsets.UTF_8));
      commands.xadd("pull.stream.in", Map.of("maps", RedisWireEnvelope.of("metric".getBytes(StandardCharsets.UTF_8), headers).encode()));

      InboundCapture metric = capturing.awaitMatching(c -> c.localNamespace.startsWith("/metrics/redis/pull/"));
      assertEquals("/metrics/redis/pull/pull.stream.in", metric.localNamespace);
      assertEquals(true, metric.message.getDataMap().get("redis.alert.reconnect").getData());
      assertNotNull(metric.message.getDataMap().get("redis.pull.reconnect_count"));
      assertNotNull(metric.message.getDataMap().get("redis.stream.pending_count"));
    } finally {
      client.shutdown();
    }
  }

  /**
   * Reads a single envelope field from a stream.
   *
   * @param commands redis commands
   * @param stream stream name
   * @return encoded envelope bytes
   */
  private byte[] readSingleEnvelope(RedisCommands<String, byte[]> commands, String stream) {
    List<StreamMessage<String, byte[]>> records = commands.xread(
        XReadArgs.Builder.block(Duration.ofSeconds(5)).count(1),
        XReadArgs.StreamOffset.from(stream, "0-0")
    );

    assertNotNull(records);
    assertEquals(1, records.size());
    assertNotNull(records.get(0).getBody().get("maps"));
    return records.get(0).getBody().get("maps");
  }

  /**
   * Polls a queue with timeout.
   *
   * @param queue source queue
   * @return dequeued bytes
   * @throws InterruptedException if interrupted while waiting
   */
  private byte[] poll(BlockingQueue<byte[]> queue) throws InterruptedException {
    byte[] data = queue.poll(5, TimeUnit.SECONDS);
    assertNotNull(data);
    return data;
  }

  /**
   * Decodes UTF-8 bytes for assertions.
   *
   * @param bytes encoded bytes
   * @return decoded text or {@code null}
   */
  private String utf8(byte[] bytes) {
    return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
  }

  /**
   * Builds Redis URL from running container mapping.
   *
   * @return redis URL
   */
  private String redisUrl() {
    return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379) + "/";
  }

  /**
   * @return push-link config for pub/sub CloudEvent pipeline tests
   */
  private Map<String, Object> createPubSubCloudEventConfig() {
    Map<String, Object> config = new HashMap<>();

    Map<String, Object> wrapLink = new HashMap<>();
    wrapLink.put("direction", "push");
    wrapLink.put("local_namespace", "/ce/wrap");
    wrapLink.put("remote_namespace", "events.cloudevents");
    wrapLink.put("redis.mode", "pubsub");
    wrapLink.put("cloud_event.mode", "wrap");
    wrapLink.put("cloud_event.type", "io.test.redis");
    wrapLink.put("cloud_event.source", "/tests/redis");

    Map<String, Object> finalLink = new HashMap<>();
    finalLink.put("direction", "push");
    finalLink.put("local_namespace", "/ce/final");
    finalLink.put("remote_namespace", "events.final");
    finalLink.put("redis.mode", "pubsub");

    config.put("links", List.of(wrapLink, finalLink));
    return config;
  }

  /**
   * @return push-link config for stream CloudEvent pipeline tests
   */
  private Map<String, Object> createStreamCloudEventConfig() {
    Map<String, Object> config = new HashMap<>();

    Map<String, Object> wrapLink = new HashMap<>();
    wrapLink.put("direction", "push");
    wrapLink.put("local_namespace", "/stream/ce/wrap");
    wrapLink.put("remote_namespace", "stream.events.cloudevents");
    wrapLink.put("redis.mode", "stream");
    wrapLink.put("cloud_event.mode", "wrap");
    wrapLink.put("cloud_event.type", "io.test.redis.stream");
    wrapLink.put("cloud_event.source", "/tests/redis/stream");

    Map<String, Object> finalLink = new HashMap<>();
    finalLink.put("direction", "push");
    finalLink.put("local_namespace", "/stream/ce/final");
    finalLink.put("remote_namespace", "stream.events.final");
    finalLink.put("redis.mode", "stream");

    config.put("links", List.of(wrapLink, finalLink));
    return config;
  }

  /**
   * @return pull-link config for pub/sub ingress tests
   */
  private Map<String, Object> createPullPubSubConfig() {
    Map<String, Object> config = new HashMap<>();

    Map<String, Object> pullLink = new HashMap<>();
    pullLink.put("direction", "pull");
    pullLink.put("remote_namespace", "pull.raw.in");
    pullLink.put("local_namespace", "/maps/in/pubsub");
    pullLink.put("redis.mode", "pubsub");

    config.put("links", List.of(pullLink));
    return config;
  }

  /**
   * @return pull-link config for stream ingress tests
   */
  private Map<String, Object> createPullStreamConfig() {
    Map<String, Object> config = new HashMap<>();

    Map<String, Object> pullLink = new HashMap<>();
    pullLink.put("direction", "pull");
    pullLink.put("remote_namespace", "pull.stream.in");
    pullLink.put("local_namespace", "/maps/in/stream");
    pullLink.put("redis.mode", "stream");
    pullLink.put("redis.stream.group", "maps-pull-group");
    pullLink.put("redis.stream.consumer", "maps-pull-consumer");
    pullLink.put("redis.stream.poll_ms", 100);
    pullLink.put("redis.reconnect.initial_ms", 100);
    pullLink.put("redis.reconnect.max_ms", 500);

    config.put("links", List.of(pullLink));
    return config;
  }

  /**
   * @return stream pull config with metrics and alert settings enabled
   */
  private Map<String, Object> createPullStreamMetricsConfig() {
    Map<String, Object> config = createPullStreamConfig();
    config.put("redis.metrics.enabled", true);
    config.put("redis.metrics.namespace", "/metrics/redis/pull");
    config.put("redis.metrics.publish_ms", 250);
    config.put("redis.alert.reconnect_delta_threshold", 1);
    config.put("redis.alert.pending_threshold", 1000);
    return config;
  }

  /**
   * Test double that simulates transient Redis send failures.
   */
  private static final class FlakyBridge implements RedisProtocol.RedisBridge {
    private final Map<String, byte[]> published = new LinkedHashMap<>();
    private boolean fail;

    /**
     * Publishes to an in-memory map, optionally failing.
     *
     * @param channel channel key
     * @param envelope envelope payload
     */
    @Override
    public void publish(String channel, byte[] envelope) {
      if (fail) {
        throw new RuntimeException("simulated redis publish failure");
      }
      published.put(channel, envelope);
    }

    /**
     * Appends to an in-memory map, optionally failing.
     *
     * @param stream stream key
     * @param envelope envelope payload
     */
    @Override
    public void appendStream(String stream, byte[] envelope) {
      if (fail) {
        throw new RuntimeException("simulated redis stream failure");
      }
      published.put(stream, envelope);
    }

    /**
     * No-op close for in-memory bridge.
     */
    @Override
    public void close() {
      // no-op
    }
  }

  /**
   * Captured inbound event tuple used by assertions.
   */
  private static final class InboundCapture {
    private final String localNamespace;
    private final Message message;

    /**
     * Creates one capture tuple.
     *
     * @param localNamespace routed local namespace
     * @param message inbound message
     */
    private InboundCapture(String localNamespace, Message message) {
      this.localNamespace = localNamespace;
      this.message = message;
    }
  }

  /**
   * RedisProtocol test subclass that captures inbound events.
   */
  private static final class CapturingRedisProtocol extends RedisProtocol {
    private final BlockingQueue<InboundCapture> queue = new LinkedBlockingQueue<>();

    /**
     * Creates a capturing protocol with plain URL/config inputs.
     *
     * @param urlString redis URL
     * @param configMap protocol config
     */
    private CapturingRedisProtocol(String urlString, Map<String, Object> configMap) {
      super(urlString, configMap);
    }

    /**
     * Captures inbound messages instead of forwarding into MAPS runtime.
     *
     * @param destination destination namespace
     * @param message inbound message
     * @throws IOException not used by this test implementation
     */
    @Override
    protected void inbound(String destination, Message message) throws IOException {
      queue.add(new InboundCapture(destination, message));
    }

    /**
     * Waits for any captured inbound message.
     *
     * @return capture object
     * @throws InterruptedException if interrupted
     */
    private InboundCapture await() throws InterruptedException {
      InboundCapture capture = queue.poll(5, TimeUnit.SECONDS);
      if (Objects.isNull(capture)) {
        throw new AssertionError("Timed out waiting for inbound capture");
      }
      return capture;
    }

    /**
     * Waits for a captured inbound message matching a predicate.
     *
     * @param predicate match function
     * @return matching capture
     * @throws InterruptedException if interrupted
     */
    private InboundCapture awaitMatching(Predicate<InboundCapture> predicate) throws InterruptedException {
      long deadline = System.currentTimeMillis() + 5000L;
      while (System.currentTimeMillis() < deadline) {
        InboundCapture capture = queue.poll(200, TimeUnit.MILLISECONDS);
        if (capture == null) {
          continue;
        }
        if (predicate.test(capture)) {
          return capture;
        }
      }
      throw new AssertionError("Timed out waiting for matching inbound capture");
    }
  }
}
