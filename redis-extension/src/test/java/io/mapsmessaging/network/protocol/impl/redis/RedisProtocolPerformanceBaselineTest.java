package io.mapsmessaging.network.protocol.impl.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.mapsmessaging.api.message.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On-demand performance baseline tests for Redis extension paths.
 */
@Testcontainers
@Tag("perf")
class RedisProtocolPerformanceBaselineTest {

  private static final RedisCodec<String, byte[]> STRING_BYTE_CODEC = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

  @Container
  @SuppressWarnings("resource")
  static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.2-alpine")
      .withExposedPorts(6379);

  private RedisProtocol protocol;

  /**
   * Closes protocol resources between test runs.
   *
   * @throws Exception if shutdown fails
   */
  @AfterEach
  void tearDown() throws Exception {
    if (protocol != null) {
      protocol.close();
    }
  }

  /**
   * Measures stream pull throughput baseline for a fixed-size burst.
   *
   * @throws Exception if setup or assertions fail
   */
  @Test
  void baseline_streamPullThroughput() throws Exception {
    runStreamThroughputBaseline("stream throughput small", 5_000, 64, 128, 25, 1, 20, 30);
  }

  /**
   * Measures stream throughput baseline with medium payloads and larger volume.
   *
   * @throws Exception if setup or assertions fail
   */
  @Test
  void baseline_streamPullThroughput_MediumPayload() throws Exception {
    runStreamThroughputBaseline("stream throughput medium", 10_000, 512, 128, 25, 1, 20, 45);
  }

  /**
   * Measures stream throughput baseline with large payloads.
   *
   * @throws Exception if setup or assertions fail
   */
  @Test
  void baseline_streamPullThroughput_LargePayload() throws Exception {
    runStreamThroughputBaseline("stream throughput large", 5_000, 4096, 128, 25, 1, 20, 45);
  }

  /**
   * Measures stream throughput with a higher batch size to validate scheduler/batch ceilings.
   *
   * @throws Exception if setup or assertions fail
   */
  @Test
  void baseline_streamPullThroughput_HighBatch() throws Exception {
    runStreamThroughputBaseline("stream throughput high-batch", 12_000, 512, 512, 25, 1, 20, 45);
  }

  /**
   * Measures stream throughput with default minimum poll guard to expose scheduler ceiling behavior.
   *
   * @throws Exception if setup or assertions fail
   */
  @Test
  void baseline_streamPullThroughput_DefaultMinPollGuard() throws Exception {
    runStreamThroughputBaseline("stream throughput default guard", 6_000, 512, 128, 50, 100, 1, 45);
  }

  /**
   * Measures pub/sub pull latency percentiles for baseline tracking.
   *
   * @throws Exception if setup or assertions fail
   */
  @Test
  void baseline_pubSubPullLatency() throws Exception {
    runPubSubLatencyBaseline("pubsub latency small", 2_000, 64, 30);
  }

  /**
   * Measures pub/sub latency baseline with medium payloads and higher count.
   *
   * @throws Exception if setup or assertions fail
   */
  @Test
  void baseline_pubSubPullLatency_MediumPayload() throws Exception {
    runPubSubLatencyBaseline("pubsub latency medium", 5_000, 512, 45);
  }

  /**
   * Measures pub/sub latency baseline with large payloads.
   *
   * @throws Exception if setup or assertions fail
   */
  @Test
  void baseline_pubSubPullLatency_LargePayload() throws Exception {
    runPubSubLatencyBaseline("pubsub latency large", 3_000, 4096, 45);
  }

  /**
   * Measures recovery time after a forced stream consumer disconnect.
   *
   * @throws Exception if setup or assertions fail
   */
  @Test
  void baseline_streamReconnectRecoveryTime() throws Exception {
    String redisUrl = redisUrl();
    String remoteNamespace = "perf.stream.in.reconnect";

    CapturingRedisProtocol capturing = new CapturingRedisProtocol(
        redisUrl,
        createPullStreamPerfConfig(remoteNamespace, "/perf/maps/in/stream/reconnect", 128, 50, 1, 1)
    );
    protocol = capturing;
    protocol.initialise();
    protocol.registerRemoteLink(remoteNamespace, null);

    RedisClient client = RedisClient.create(redisUrl);
    try (StatefulRedisConnection<String, byte[]> connection = client.connect(STRING_BYTE_CODEC)) {
      RedisCommands<String, byte[]> commands = connection.sync();

      capturing.forceCloseStreamConnectionForTest(remoteNamespace);
      long startNs = System.nanoTime();

      Map<String, byte[]> headers = new LinkedHashMap<>();
      headers.put("maps.type.seq", "INT".getBytes(StandardCharsets.UTF_8));
      headers.put("maps.data.seq", "1".getBytes(StandardCharsets.UTF_8));
      commands.xadd(remoteNamespace, Map.of("maps", RedisWireEnvelope.of("reconnect".getBytes(StandardCharsets.UTF_8), headers).encode()));

      capturing.awaitCount(1, 15);
      long recoveryMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

      RedisProtocol.PullStats stats = capturing.pullStatsForTest(remoteNamespace);
      assertTrue(stats != null && stats.reconnectCount >= 1, "Expected at least one reconnect");
      assertTrue(recoveryMs < 10_000, "Reconnect recovery too slow: " + recoveryMs + " ms");

      System.out.printf("[perf] stream reconnect recovery baseline: recovery=%dms reconnects=%d%n",
          recoveryMs, stats.reconnectCount);
    } finally {
      client.shutdown();
    }
  }

  /**
   * Executes one stream throughput baseline variant.
   *
   * @param label scenario label
   * @param messageCount number of messages
   * @param payloadSize payload size in bytes
   * @param batchSize stream batch size
   * @param pollMs stream poll interval in ms
   * @param minPollMs minimum effective poll interval in ms
   * @param pendingSampleEvery sample pending depth every N consumed messages
   * @param timeoutSeconds await timeout
   * @throws Exception if setup or assertions fail
   */
  private void runStreamThroughputBaseline(
      String label,
      int messageCount,
      int payloadSize,
      int batchSize,
      int pollMs,
      int minPollMs,
      int pendingSampleEvery,
      int timeoutSeconds) throws Exception {
    String redisUrl = redisUrl();

    String remoteNamespace = "perf.stream.in." + Math.abs(label.hashCode());
    String localNamespace = "/perf/maps/in/stream/" + Math.abs(label.hashCode());
    CapturingRedisProtocol capturing = new CapturingRedisProtocol(
        redisUrl,
        createPullStreamPerfConfig(remoteNamespace, localNamespace, batchSize, pollMs, minPollMs, pendingSampleEvery)
    );
    protocol = capturing;
    protocol.initialise();
    protocol.registerRemoteLink(remoteNamespace, null);

    RedisClient client = RedisClient.create(redisUrl);
    try (StatefulRedisConnection<String, byte[]> connection = client.connect(STRING_BYTE_CODEC)) {
      RedisCommands<String, byte[]> commands = connection.sync();

      long startNs = System.nanoTime();
      for (int i = 0; i < messageCount; i++) {
        Map<String, byte[]> headers = new LinkedHashMap<>();
        headers.put("maps.type.seq", "INT".getBytes(StandardCharsets.UTF_8));
        headers.put("maps.data.seq", Integer.toString(i).getBytes(StandardCharsets.UTF_8));
        commands.xadd(remoteNamespace, Map.of("maps", RedisWireEnvelope.of(payloadFor(i, payloadSize), headers).encode()));
      }

      List<InboundCapture> captures = capturing.awaitCount(messageCount, timeoutSeconds);
      long durationNs = System.nanoTime() - startNs;
      double seconds = durationNs / 1_000_000_000.0;
      double throughput = messageCount / seconds;
      double mibPerSecond = ((double) messageCount * payloadSize) / (1024.0 * 1024.0) / seconds;
      double effectivePollMs = Math.max(minPollMs, pollMs);
      double theoreticalMax = batchSize / (effectivePollMs / 1000.0);

      assertEquals(messageCount, captures.size());
      assertTrue(throughput > 100.0, "Throughput baseline unexpectedly low: " + throughput + " msg/s");
      assertTrue(throughput <= (theoreticalMax * 1.30), "Throughput exceeds expected ceiling too far: " + throughput + " msg/s");

      System.out.printf(
          "[perf] %s: messages=%d payload=%dB batch=%d pollMs=%d minPollMs=%d pendingSampleEvery=%d duration=%.3fs throughput=%.1f msg/s (%.2f MiB/s) theoreticalCeiling=%.1f msg/s%n",
          label, messageCount, payloadSize, batchSize, pollMs, minPollMs, pendingSampleEvery, seconds, throughput, mibPerSecond, theoreticalMax);
    } finally {
      client.shutdown();
    }
  }

  /**
   * Executes one pub/sub latency baseline variant.
   *
   * @param label scenario label
   * @param messageCount number of messages
   * @param payloadSize payload size in bytes
   * @param timeoutSeconds await timeout
   * @throws Exception if setup or assertions fail
   */
  private void runPubSubLatencyBaseline(String label, int messageCount, int payloadSize, int timeoutSeconds) throws Exception {
    String redisUrl = redisUrl();

    CapturingRedisProtocol capturing = new CapturingRedisProtocol(redisUrl, createPullPubSubPerfConfig());
    protocol = capturing;
    protocol.initialise();
    protocol.registerRemoteLink("perf.pubsub.in", null);

    RedisClient client = RedisClient.create(redisUrl);
    try (StatefulRedisConnection<String, byte[]> connection = client.connect(STRING_BYTE_CODEC)) {
      RedisCommands<String, byte[]> commands = connection.sync();

      for (int i = 0; i < messageCount; i++) {
        long sentNs = System.nanoTime();
        Map<String, byte[]> headers = new LinkedHashMap<>();
        headers.put("maps.type.sent_ns", "LONG".getBytes(StandardCharsets.UTF_8));
        headers.put("maps.data.sent_ns", Long.toString(sentNs).getBytes(StandardCharsets.UTF_8));
        headers.put("maps.type.seq", "INT".getBytes(StandardCharsets.UTF_8));
        headers.put("maps.data.seq", Integer.toString(i).getBytes(StandardCharsets.UTF_8));
        commands.publish("perf.pubsub.in", RedisWireEnvelope.of(payloadFor(i, payloadSize), headers).encode());
      }

      List<InboundCapture> captures = capturing.awaitCount(messageCount, timeoutSeconds);
      assertEquals(messageCount, captures.size());

      List<Double> latenciesMs = new ArrayList<>(messageCount);
      for (InboundCapture capture : captures) {
        Object sentNsObj = capture.message.getDataMap().get("sent_ns").getData();
        long sentNs = Long.parseLong(String.valueOf(sentNsObj));
        latenciesMs.add((capture.receivedAtNs - sentNs) / 1_000_000.0);
      }

      Collections.sort(latenciesMs);
      double p50 = percentile(latenciesMs, 50);
      double p95 = percentile(latenciesMs, 95);
      double p99 = percentile(latenciesMs, 99);

      assertTrue(p99 < 2000.0, "p99 latency unexpectedly high: " + p99 + " ms");
      System.out.printf("[perf] %s: count=%d payload=%dB p50=%.2fms p95=%.2fms p99=%.2fms%n",
          label, messageCount, payloadSize, p50, p95, p99);
    } finally {
      client.shutdown();
    }
  }

  /**
   * Builds deterministic payload bytes for a sequence id and size.
   *
   * @param sequence sequence index
   * @param payloadSize target size in bytes
   * @return payload bytes
   */
  private byte[] payloadFor(int sequence, int payloadSize) {
    byte[] payload = new byte[payloadSize];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) ((sequence + i) & 0x7F);
    }
    return payload;
  }

  /**
   * Builds Redis URL from container mapping.
   *
   * @return redis URL
   */
  private String redisUrl() {
    return "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379) + "/";
  }

  /**
   * Creates stream pull performance configuration for one scenario.
   *
   * @param remoteNamespace remote stream namespace
   * @param localNamespace mapped local namespace
   * @param batchSize stream batch size
   * @param pollMs stream poll interval in ms
   * @param minPollMs minimum effective poll interval in ms
   * @param pendingSampleEvery sample pending depth every N consumed messages
   * @return stream pull performance config
   */
  private Map<String, Object> createPullStreamPerfConfig(
      String remoteNamespace,
      String localNamespace,
      int batchSize,
      int pollMs,
      int minPollMs,
      int pendingSampleEvery) {
    Map<String, Object> config = new HashMap<>();

    Map<String, Object> pullLink = new HashMap<>();
    pullLink.put("direction", "pull");
    pullLink.put("remote_namespace", remoteNamespace);
    pullLink.put("local_namespace", localNamespace);
    pullLink.put("redis.mode", "stream");
    pullLink.put("redis.stream.group", "perf-stream-group-" + Math.abs(remoteNamespace.hashCode()));
    pullLink.put("redis.stream.consumer", "perf-stream-consumer");
    pullLink.put("redis.stream.poll_ms", pollMs);
    pullLink.put("redis.stream.min_poll_ms", minPollMs);
    pullLink.put("redis.stream.batch_size", batchSize);
    pullLink.put("redis.stream.pending_sample_every", pendingSampleEvery);
    pullLink.put("redis.reconnect.initial_ms", 100);
    pullLink.put("redis.reconnect.max_ms", 1000);

    config.put("links", List.of(pullLink));
    return config;
  }

  /**
   * @return pub/sub pull performance config
   */
  private Map<String, Object> createPullPubSubPerfConfig() {
    Map<String, Object> config = new HashMap<>();

    Map<String, Object> pullLink = new HashMap<>();
    pullLink.put("direction", "pull");
    pullLink.put("remote_namespace", "perf.pubsub.in");
    pullLink.put("local_namespace", "/perf/maps/in/pubsub");
    pullLink.put("redis.mode", "pubsub");

    config.put("links", List.of(pullLink));
    return config;
  }

  /**
   * Computes a nearest-rank percentile from sorted latency values.
   *
   * @param values sample values
   * @param percentile percentile rank (0-100)
   * @return percentile value
   */
  private double percentile(List<Double> values, int percentile) {
    if (values.isEmpty()) {
      return 0.0;
    }
    int index = (int) Math.ceil((percentile / 100.0) * values.size()) - 1;
    index = Math.max(0, Math.min(values.size() - 1, index));
    return values.get(index);
  }

  /**
   * Inbound event capture with receive timestamp.
   */
  private static final class InboundCapture {
    private final String localNamespace;
    private final Message message;
    private final long receivedAtNs;

    /**
     * Creates one inbound capture.
     *
     * @param localNamespace routed local namespace
     * @param message inbound message
     * @param receivedAtNs receive timestamp in nanoseconds
     */
    private InboundCapture(String localNamespace, Message message, long receivedAtNs) {
      this.localNamespace = localNamespace;
      this.message = message;
      this.receivedAtNs = receivedAtNs;
    }
  }

  /**
   * RedisProtocol variant that records inbound events into a queue.
   */
  private static final class CapturingRedisProtocol extends RedisProtocol {
    private final BlockingQueue<InboundCapture> queue = new LinkedBlockingQueue<>();

    /**
     * Creates capturing protocol from plain URL/config.
     *
     * @param urlString redis URL
     * @param configMap protocol config
     */
    private CapturingRedisProtocol(String urlString, Map<String, Object> configMap) {
      super(urlString, configMap);
    }

    /**
     * Captures inbound messages.
     *
     * @param destination target namespace
     * @param message inbound message
     * @throws IOException not used in this test implementation
     */
    @Override
    protected void inbound(String destination, Message message) throws IOException {
      queue.add(new InboundCapture(destination, message, System.nanoTime()));
    }

    /**
     * Waits for an exact number of inbound captures.
     *
     * @param expectedCount expected capture count
     * @param timeoutSeconds timeout in seconds
     * @return collected captures
     * @throws InterruptedException if interrupted while waiting
     */
    private List<InboundCapture> awaitCount(int expectedCount, int timeoutSeconds) throws InterruptedException {
      List<InboundCapture> captures = new ArrayList<>(expectedCount);
      long deadline = System.currentTimeMillis() + (timeoutSeconds * 1000L);
      while (captures.size() < expectedCount && System.currentTimeMillis() < deadline) {
        InboundCapture capture = queue.poll(200, TimeUnit.MILLISECONDS);
        if (Objects.nonNull(capture)) {
          captures.add(capture);
        }
      }
      if (captures.size() < expectedCount) {
        throw new AssertionError("Timed out waiting for " + expectedCount + " captures, got " + captures.size());
      }
      return captures;
    }
  }
}
