/*
 *  Copyright [ 2020 - 2024 ] Matthew Buckton
 *  Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 *
 *  Licensed under the Apache License, Version 2.0 with the Commons Clause
 *  (the "License"); you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *      https://commonsclause.com/
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package io.mapsmessaging.network.protocol.impl.redis;

import io.lettuce.core.Consumer;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XGroupCreateArgs;
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
import io.mapsmessaging.dto.rest.config.protocol.impl.ExtensionConfigDTO;
import io.mapsmessaging.logging.Logger;
import io.mapsmessaging.logging.LoggerFactory;
import io.mapsmessaging.network.EndPointURL;
import io.mapsmessaging.network.io.EndPoint;
import io.mapsmessaging.network.protocol.impl.extension.Extension;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis extension protocol that bridges MAPS namespaces with Redis Pub/Sub and Streams.
 * <p>
 * The implementation preserves MAPS typed headers, supports CloudEvent wrapping on outbound
 * links, consumes pull links via pub/sub and stream consumer groups, and emits optional
 * MAPS-native pull-path metrics.
 */
public class RedisProtocol extends Extension {

  private static final String REDIS_MODE = "redis.mode";
  private static final String CLOUD_EVENT_MODE = "cloud_event.mode";
  private static final String CLOUD_EVENT_TYPE = "cloud_event.type";
  private static final String CLOUD_EVENT_SOURCE = "cloud_event.source";

  private static final String REDIS_STREAM_GROUP = "redis.stream.group";
  private static final String REDIS_STREAM_CONSUMER = "redis.stream.consumer";
  private static final String REDIS_STREAM_POLL_MS = "redis.stream.poll_ms";
  private static final String REDIS_STREAM_MIN_POLL_MS = "redis.stream.min_poll_ms";
  private static final String REDIS_STREAM_BATCH_SIZE = "redis.stream.batch_size";
  private static final String REDIS_STREAM_PENDING_SAMPLE_EVERY = "redis.stream.pending_sample_every";
  private static final String REDIS_RECONNECT_INITIAL_MS = "redis.reconnect.initial_ms";
  private static final String REDIS_RECONNECT_MAX_MS = "redis.reconnect.max_ms";
  private static final String REDIS_METRICS_ENABLED = "redis.metrics.enabled";
  private static final String REDIS_METRICS_NAMESPACE = "redis.metrics.namespace";
  private static final String REDIS_METRICS_PUBLISH_MS = "redis.metrics.publish_ms";
  private static final String REDIS_ALERT_PENDING_THRESHOLD = "redis.alert.pending_threshold";
  private static final String REDIS_ALERT_RECONNECT_DELTA = "redis.alert.reconnect_delta_threshold";

  private static final String MAPS_DATA_HEADER_PREFIX = "maps.data.";
  private static final String MAPS_TYPE_HEADER_PREFIX = "maps.type.";
  private static final String MAPS_CONTENT_TYPE_HEADER = "maps.contentType";

  private static final RedisCodec<String, byte[]> STRING_BYTE_CODEC = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);

  private final Logger logger;
  private final EndPointURL url;
  private final ExtensionConfigDTO protocolConfig;
  private final Map<String, PushBinding> pushBindings;
  private final Map<String, PullBinding> pullBindings;
  private final Map<String, ScheduledFuture<?>> streamTasks;

  private RedisBridge redisBridge;
  private ScheduledExecutorService streamScheduler;
  private ScheduledFuture<?> metricsTask;
  private boolean metricsEnabled;
  private String metricsNamespace;
  private long metricsPublishMs;
  private long alertPendingThreshold;
  private long alertReconnectDeltaThreshold;

  private RedisClient pullPubSubClient;
  private StatefulRedisPubSubConnection<String, byte[]> pullPubSubConnection;

  /**
   * Constructs the protocol from a runtime endpoint.
   *
   * @param endPoint runtime endpoint
   * @param protocolConfigDTO extension config DTO
   */
  public RedisProtocol(@NonNull @NotNull EndPoint endPoint, ExtensionConfigDTO protocolConfigDTO) {
    this.url = new EndPointURL(endPoint.getConfig().getUrl());
    this.protocolConfig = protocolConfigDTO;
    this.logger = LoggerFactory.getLogger(RedisProtocol.class);
    this.pushBindings = new ConcurrentHashMap<>();
    this.pullBindings = new ConcurrentHashMap<>();
    this.streamTasks = new ConcurrentHashMap<>();
  }

  /**
   * Test-focused constructor that uses a raw URL and plain config map.
   *
   * @param urlString redis URL
   * @param configMap protocol configuration
   */
  RedisProtocol(String urlString, Map<String, Object> configMap) {
    this.url = new EndPointURL(urlString);
    this.protocolConfig = new ExtensionConfigDTO() {
      @Override
      public Map<String, Object> getConfig() {
        return configMap;
      }
    };
    this.logger = LoggerFactory.getLogger(RedisProtocol.class);
    this.pushBindings = new ConcurrentHashMap<>();
    this.pullBindings = new ConcurrentHashMap<>();
    this.streamTasks = new ConcurrentHashMap<>();
  }

  /**
   * Injects a custom bridge implementation used by unit and integration tests.
   *
   * @param bridge bridge implementation
   */
  void setRedisBridgeForTest(RedisBridge bridge) {
    this.redisBridge = bridge;
  }

  /**
   * Decodes a raw wire envelope into a MAPS message for tests.
   *
   * @param envelope encoded envelope bytes
   * @param sourceNamespace source namespace/channel/stream
   * @param transport transport mode ({@code pubsub} or {@code stream})
   * @return decoded MAPS message
   */
  Message convertInboundEnvelopeForTest(byte[] envelope, String sourceNamespace, String transport) {
    return convertInboundEnvelope(envelope, sourceNamespace, transport);
  }

  /**
   * Forcibly closes the stream consumer connection for a pull binding to trigger reconnect logic.
   *
   * @param remoteNamespace stream namespace used by the pull binding
   */
  void forceCloseStreamConnectionForTest(String remoteNamespace) {
    PullBinding binding = pullBindings.get(remoteNamespace);
    if (binding == null || !"stream".equals(binding.mode)) {
      return;
    }
    markStreamDisconnected(binding);
  }

  /**
   * Returns snapshot pull statistics for the requested remote namespace.
   *
   * @param remoteNamespace pull binding key
   * @return stats snapshot or {@code null} if no binding exists
   */
  PullStats pullStatsForTest(String remoteNamespace) {
    PullBinding binding = pullBindings.get(remoteNamespace);
    if (binding == null) {
      return null;
    }
    return new PullStats(
        binding.receivedCount.get(),
        binding.errorCount.get(),
        binding.reconnectCount.get(),
        binding.lastPendingCount.get(),
        binding.lastAckLatencyMs.get()
    );
  }

  /**
   * Initializes Redis clients, scheduler infrastructure, and optional metrics publisher.
   */
  @Override
  public void initialise() {
    logger.log(RedisLogMessages.INITIALISE_REDIS_ENDPOINT, url.toString());
    if (redisBridge == null) {
      redisBridge = new LettuceRedisBridge(url.toString());
    }
    if (streamScheduler == null) {
      streamScheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "redis-stream-poller");
        thread.setDaemon(true);
        return thread;
      });
    }

    Map<String, Object> config = currentConfig();
    metricsEnabled = parseBoolean(config.getOrDefault(REDIS_METRICS_ENABLED, false), false);
    metricsNamespace = valueAsString(config.getOrDefault(REDIS_METRICS_NAMESPACE, "/metrics/redis/pull"), "/metrics/redis/pull");
    metricsPublishMs = Math.max(250L, parseLong(config.getOrDefault(REDIS_METRICS_PUBLISH_MS, 5000), 5000L));
    alertPendingThreshold = Math.max(0L, parseLong(config.getOrDefault(REDIS_ALERT_PENDING_THRESHOLD, 100), 100L));
    alertReconnectDeltaThreshold = Math.max(1L, parseLong(config.getOrDefault(REDIS_ALERT_RECONNECT_DELTA, 1), 1L));

    if (metricsEnabled && metricsTask == null) {
      metricsTask = streamScheduler.scheduleAtFixedRate(
          this::publishPullMetrics,
          metricsPublishMs,
          metricsPublishMs,
          TimeUnit.MILLISECONDS
      );
    }
    logger.log(RedisLogMessages.REDIS_ENDPOINT_INITIALIZED, url.toString());
  }

  /**
   * Closes producer/consumer resources and background tasks.
   *
   * @throws IOException propagated from extension super-class shutdown
   */
  @Override
  public void close() throws IOException {
    for (Map.Entry<String, ScheduledFuture<?>> entry : streamTasks.entrySet()) {
      ScheduledFuture<?> task = entry.getValue();
      if (task != null) {
        task.cancel(true);
      }
    }
    streamTasks.clear();
    if (metricsTask != null) {
      metricsTask.cancel(true);
      metricsTask = null;
    }

    for (PullBinding binding : pullBindings.values()) {
      closeQuietly(binding.streamConnection);
      closeQuietly(binding.streamClient);
    }
    pullBindings.clear();

    if (pullPubSubConnection != null) {
      try {
        pullPubSubConnection.sync().unsubscribe();
      } catch (Throwable ignored) {
        // no-op
      }
    }
    closeQuietly(pullPubSubConnection);
    closeQuietly(pullPubSubClient);
    pullPubSubConnection = null;
    pullPubSubClient = null;

    if (streamScheduler != null) {
      streamScheduler.shutdownNow();
      streamScheduler = null;
    }

    if (redisBridge != null) {
      try {
        redisBridge.close();
      } catch (Throwable e) {
        logger.log(RedisLogMessages.REDIS_ENDPOINT_CLOSE_ERROR, e);
      }
    }
    redisBridge = null;
    pushBindings.clear();

    logger.log(RedisLogMessages.REDIS_ENDPOINT_CLOSED);
    super.close();
  }

  /**
   * @return extension name
   */
  @Override
  public @NonNull String getName() {
    return "RedisProtocol";
  }

  /**
   * @return extension protocol version
   */
  @Override
  public String getVersion() {
    return "1.0";
  }

  /**
   * @return {@code false}; remote filtering is not delegated to Redis.
   */
  @Override
  public boolean supportsRemoteFiltering() {
    return false;
  }

  /**
   * Registers a MAPS local outbound link mapped to a Redis destination.
   *
   * @param destination local namespace
   */
  @Override
  public void registerLocalLink(@NonNull @NotNull String destination) {
    Map<String, Object> attrs = findLinkAttributes(destination, "push", true);
    String namespace = valueAsString(attrs.getOrDefault("remote_namespace", destination), destination);

    PushBinding binding = new PushBinding();
    binding.localNamespace = destination;
    binding.remoteNamespace = namespace;
    binding.mode = parseMode(attrs.getOrDefault(REDIS_MODE, "pubsub"), destination);
    binding.cloudEventMode = valueAsString(attrs.getOrDefault(CLOUD_EVENT_MODE, "none"), "none").toLowerCase(Locale.ROOT);
    binding.cloudEventType = valueAsString(attrs.getOrDefault(CLOUD_EVENT_TYPE, "io.mapsmessaging.event"), "io.mapsmessaging.event");
    binding.cloudEventSource = valueAsString(attrs.getOrDefault(CLOUD_EVENT_SOURCE, "/" + destination), "/" + destination);

    pushBindings.put(destination, binding);
    logger.log(RedisLogMessages.REDIS_SUBSCRIBE_LOCAL_SUCCESS, destination, namespace);
  }

  /**
   * Registers a Redis pull link and starts corresponding consumers.
   *
   * @param destination remote namespace key
   * @param filter optional filter (unused)
   * @throws IOException if registration fails
   */
  @Override
  public void registerRemoteLink(@NotNull @NotNull String destination, String filter) throws IOException {
    Map<String, Object> attrs = findLinkAttributes(destination, "pull", false);
    String remoteNamespace = valueAsString(attrs.getOrDefault("remote_namespace", destination), destination);
    String localNamespace = valueAsString(attrs.getOrDefault("local_namespace", destination), destination);
    String mode = parseMode(attrs.getOrDefault(REDIS_MODE, "pubsub"), destination);

    PullBinding existing = pullBindings.remove(remoteNamespace);
    if (existing != null) {
      stopPullBinding(existing);
    }

    PullBinding binding = new PullBinding();
    binding.remoteNamespace = remoteNamespace;
    binding.localNamespace = localNamespace;
    binding.mode = mode;

    if ("stream".equals(mode)) {
      binding.streamGroup = valueAsString(attrs.getOrDefault(REDIS_STREAM_GROUP, "maps-redis-group"), "maps-redis-group");
      binding.streamConsumer = valueAsString(attrs.getOrDefault(REDIS_STREAM_CONSUMER, "maps-redis-consumer"), "maps-redis-consumer");
      binding.streamPollMs = parseLong(attrs.getOrDefault(REDIS_STREAM_POLL_MS, 500), 500L);
      binding.streamMinPollMs = Math.max(1L, parseLong(attrs.getOrDefault(REDIS_STREAM_MIN_POLL_MS, 100), 100L));
      binding.streamBatchSize = (int) Math.max(1L, parseLong(attrs.getOrDefault(REDIS_STREAM_BATCH_SIZE, 32), 32L));
      binding.streamPendingSampleEvery = (int) Math.max(1L, parseLong(attrs.getOrDefault(REDIS_STREAM_PENDING_SAMPLE_EVERY, 1), 1L));
      binding.reconnectInitialMs = Math.max(100L, parseLong(attrs.getOrDefault(REDIS_RECONNECT_INITIAL_MS, 250), 250L));
      binding.reconnectMaxMs = Math.max(binding.reconnectInitialMs, parseLong(attrs.getOrDefault(REDIS_RECONNECT_MAX_MS, 5000), 5000L));
      binding.currentReconnectMs = binding.reconnectInitialMs;
      startStreamPull(binding);
    } else {
      startPubSubPull(binding);
    }

    pullBindings.put(remoteNamespace, binding);
    logger.log(RedisLogMessages.REDIS_SUBSCRIBE_REMOTE_SUCCESS, remoteNamespace, localNamespace);
  }

  /**
   * Publishes a MAPS outbound message to Redis using the configured link mode.
   *
   * @param destinationName local namespace
   * @param message outbound MAPS message
   */
  @Override
  public void outbound(@NonNull @NotNull String destinationName, @NonNull @NotNull Message message) {
    PushBinding binding = pushBindings.get(destinationName);
    if (binding == null) {
      return;
    }

    if (redisBridge == null) {
      logger.log(RedisLogMessages.REDIS_FAILED_TO_SEND_MESSAGE, binding.remoteNamespace, new IllegalStateException("Redis bridge not initialized"));
      return;
    }

    Map<String, byte[]> headers = buildHeaders(message);
    applyCloudEventHeaders(binding, message, headers);

    byte[] payload = message.getOpaqueData() == null ? new byte[0] : message.getOpaqueData();
    byte[] envelope = RedisWireEnvelope.of(payload, headers).encode();

    try {
      if ("stream".equals(binding.mode)) {
        redisBridge.appendStream(binding.remoteNamespace, envelope);
      } else {
        redisBridge.publish(binding.remoteNamespace, envelope);
      }
      logger.log(RedisLogMessages.REDIS_SEND_MESSAGE, binding.remoteNamespace, binding.mode);
    } catch (RuntimeException e) {
      logger.log(RedisLogMessages.REDIS_FAILED_TO_SEND_MESSAGE, binding.remoteNamespace, e);
    }
  }

  /**
   * Starts a pub/sub pull subscription for a binding.
   *
   * @param binding pull binding
   */
  private void startPubSubPull(PullBinding binding) {
    ensurePubSubPullConnection();
    pullPubSubConnection.sync().subscribe(binding.remoteNamespace);
  }

  /**
   * Lazily creates a shared pub/sub connection and listener.
   */
  private void ensurePubSubPullConnection() {
    if (pullPubSubConnection != null) {
      return;
    }

    pullPubSubClient = RedisClient.create(url.toString());
    pullPubSubConnection = pullPubSubClient.connectPubSub(STRING_BYTE_CODEC);
    pullPubSubConnection.addListener(new RedisPubSubAdapter<String, byte[]>() {
      @Override
      public void message(String channel, byte[] message) {
        PullBinding binding = pullBindings.get(channel);
        if (binding == null || !"pubsub".equals(binding.mode)) {
          return;
        }

        try {
          Message inboundMessage = convertInboundEnvelope(message, channel, "pubsub");
          binding.receivedCount.incrementAndGet();
          enrichInboundMetrics(binding, inboundMessage);
          inbound(binding.localNamespace, inboundMessage);
        } catch (Throwable e) {
          binding.errorCount.incrementAndGet();
          logger.log(RedisLogMessages.REDIS_FAILED_TO_PROCESS_INCOMING_EVENT, channel, e);
        }
      }
    });
  }

  /**
   * Starts a stream consumer-group poller for a binding.
   *
   * @param binding pull binding
   */
  private void startStreamPull(PullBinding binding) {
    if (streamScheduler == null) {
      streamScheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "redis-stream-poller");
        thread.setDaemon(true);
        return thread;
      });
    }

    connectStreamBinding(binding);

    ScheduledFuture<?> task = streamScheduler.scheduleAtFixedRate(
        new StreamPoller(binding),
        0,
        Math.max(binding.streamMinPollMs, binding.streamPollMs),
        TimeUnit.MILLISECONDS
    );
    binding.streamTask = task;
    streamTasks.put(binding.remoteNamespace, task);
  }

  /**
   * Stops tasks and network resources associated with a pull binding.
   *
   * @param binding pull binding
   */
  private void stopPullBinding(PullBinding binding) {
    ScheduledFuture<?> task = streamTasks.remove(binding.remoteNamespace);
    if (task != null) {
      task.cancel(true);
    }

    if ("pubsub".equals(binding.mode) && pullPubSubConnection != null) {
      try {
        pullPubSubConnection.sync().unsubscribe(binding.remoteNamespace);
      } catch (Throwable ignored) {
        // no-op
      }
    }

    closeQuietly(binding.streamConnection);
    closeQuietly(binding.streamClient);
    binding.streamConnection = null;
    binding.streamClient = null;
    binding.streamCommands = null;
  }

  /**
   * Opens stream connection resources and ensures consumer group exists.
   *
   * @param binding pull binding
   */
  private void connectStreamBinding(PullBinding binding) {
    binding.streamClient = RedisClient.create(url.toString());
    binding.streamConnection = binding.streamClient.connect(STRING_BYTE_CODEC);
    binding.streamCommands = binding.streamConnection.sync();
    ensureStreamGroup(binding);
    binding.currentReconnectMs = binding.reconnectInitialMs;
    binding.nextReconnectAttemptAtMs = 0;
  }

  /**
   * Creates the stream consumer group if missing.
   *
   * @param binding pull binding
   */
  private void ensureStreamGroup(PullBinding binding) {
    try {
      binding.streamCommands.xgroupCreate(
          XReadArgs.StreamOffset.from(binding.remoteNamespace, "0-0"),
          binding.streamGroup,
          new XGroupCreateArgs().mkstream(true)
      );
    } catch (RedisBusyException ignored) {
      // group already exists
    }
  }

  /**
   * Marks a stream binding disconnected and schedules reconnect backoff.
   *
   * @param binding pull binding
   */
  private void markStreamDisconnected(PullBinding binding) {
    closeQuietly(binding.streamConnection);
    closeQuietly(binding.streamClient);
    binding.streamConnection = null;
    binding.streamClient = null;
    binding.streamCommands = null;

    long now = System.currentTimeMillis();
    if (binding.nextReconnectAttemptAtMs == 0L) {
      binding.nextReconnectAttemptAtMs = now + binding.currentReconnectMs;
    }
  }

  /**
   * Attempts reconnect for a disconnected stream binding respecting backoff.
   *
   * @param binding pull binding
   */
  private void tryReconnectStreamBinding(PullBinding binding) {
    long now = System.currentTimeMillis();
    if (binding.streamCommands != null || now < binding.nextReconnectAttemptAtMs) {
      return;
    }

    try {
      connectStreamBinding(binding);
      binding.reconnectCount.incrementAndGet();
    } catch (Throwable e) {
      binding.errorCount.incrementAndGet();
      long next = Math.min(binding.currentReconnectMs * 2L, binding.reconnectMaxMs);
      binding.currentReconnectMs = next;
      binding.nextReconnectAttemptAtMs = now + next;
      logger.log(RedisLogMessages.REDIS_CONSUMER_ERROR, binding.remoteNamespace, e);
    }
  }

  /**
   * Converts encoded Redis envelope bytes into a MAPS message.
   *
   * @param envelope encoded envelope
   * @param sourceNamespace source channel/stream
   * @param transport source transport
   * @return decoded message
   */
  private Message convertInboundEnvelope(byte[] envelope, String sourceNamespace, String transport) {
    RedisWireEnvelope decoded = RedisWireEnvelope.decode(envelope);
    MessageBuilder builder = new MessageBuilder().setOpaqueData(decoded.payload());

    Map<String, TypedData> dataMap = new LinkedHashMap<>();
    applyTypedHeaders(dataMap, decoded.headers());
    dataMap.put("redis.source", new TypedData(sourceNamespace));
    dataMap.put("redis.transport", new TypedData(transport));

    String contentType = headerValue(decoded.headers(), MAPS_CONTENT_TYPE_HEADER);
    if (contentType != null) {
      builder.setContentType(contentType);
    }

    builder.setDataMap(dataMap);
    return builder.build();
  }

  /**
   * Reads typed headers and raw headers from envelope metadata.
   *
   * @param dataMap destination map
   * @param headers source headers
   */
  private void applyTypedHeaders(Map<String, TypedData> dataMap, Map<String, byte[]> headers) {
    Map<String, String> typeMap = new LinkedHashMap<>();
    Map<String, String> valueMap = new LinkedHashMap<>();

    for (Map.Entry<String, byte[]> entry : headers.entrySet()) {
      if (entry.getValue() == null) {
        continue;
      }

      String headerKey = entry.getKey();
      String headerValue = new String(entry.getValue(), StandardCharsets.UTF_8);

      if (headerKey.startsWith(MAPS_TYPE_HEADER_PREFIX)) {
        typeMap.put(headerKey.substring(MAPS_TYPE_HEADER_PREFIX.length()), headerValue);
      } else if (headerKey.startsWith(MAPS_DATA_HEADER_PREFIX)) {
        valueMap.put(headerKey.substring(MAPS_DATA_HEADER_PREFIX.length()), headerValue);
      } else {
        dataMap.put("redis.header." + headerKey, new TypedData(headerValue));
        if (headerKey.startsWith("ce_")) {
          dataMap.put("cloudevents." + headerKey.substring(3), new TypedData(headerValue));
        }
      }
    }

    for (Map.Entry<String, String> entry : valueMap.entrySet()) {
      String field = entry.getKey();
      String typeName = typeMap.get(field);
      dataMap.put(field, parseTypedValue(typeName, entry.getValue()));
    }
  }

  /**
   * Parses a typed header value into a MAPS {@link TypedData} value.
   *
   * @param typeName serialized MAPS type name
   * @param value string form
   * @return parsed typed data
   */
  private TypedData parseTypedValue(String typeName, String value) {
    if (typeName == null || typeName.trim().isEmpty()) {
      return new TypedData(value);
    }

    String upper = typeName.toUpperCase(Locale.ROOT);
    try {
      switch (upper) {
        case "STRING":
          return new TypedData(value);
        case "INT":
          return new TypedData(Integer.parseInt(value));
        case "LONG":
          return new TypedData(Long.parseLong(value));
        case "FLOAT":
          return new TypedData(Float.parseFloat(value));
        case "DOUBLE":
          return new TypedData(Double.parseDouble(value));
        case "BOOLEAN":
          return new TypedData(Boolean.parseBoolean(value));
        case "SHORT":
          return new TypedData(Short.parseShort(value));
        case "BYTE":
          return new TypedData(Byte.parseByte(value));
        case "CHAR":
          return value.isEmpty() ? new TypedData("") : new TypedData(String.valueOf(value.charAt(0)));
        default:
          return new TypedData(value);
      }
    } catch (RuntimeException ignored) {
      return new TypedData(value);
    }
  }

  /**
   * Builds outbound headers from MAPS typed data and reserved metadata fields.
   *
   * @param message source message
   * @return header byte map
   */
  private Map<String, byte[]> buildHeaders(Message message) {
    Map<String, byte[]> headers = new LinkedHashMap<>();

    if (message.getDataMap() != null) {
      for (Map.Entry<String, TypedData> entry : message.getDataMap().entrySet()) {
        if (entry.getValue() == null || entry.getValue().getData() == null) {
          continue;
        }

        String fieldKey = entry.getKey();
        String typeName = entry.getValue().getType().name();
        byte[] valueBytes = typedDataToBytes(entry.getValue());

        if (fieldKey.startsWith("redis.header.")) {
          String rawHeader = fieldKey.substring("redis.header.".length());
          if (!rawHeader.trim().isEmpty() && valueBytes != null) {
            headers.put(rawHeader, valueBytes);
          }
        }

        headers.put(MAPS_TYPE_HEADER_PREFIX + fieldKey, typeName.getBytes(StandardCharsets.UTF_8));
        if (valueBytes != null) {
          headers.put(MAPS_DATA_HEADER_PREFIX + fieldKey, valueBytes);
        }
      }
    }

    if (message.getContentType() != null) {
      headers.put(MAPS_CONTENT_TYPE_HEADER, message.getContentType().getBytes(StandardCharsets.UTF_8));
    }

    return headers;
  }

  /**
   * Applies CloudEvent headers when a push link is configured for wrap mode.
   *
   * @param binding push binding
   * @param message source message
   * @param headers mutable header map
   */
  private void applyCloudEventHeaders(PushBinding binding, Message message, Map<String, byte[]> headers) {
    if (!"wrap".equals(binding.cloudEventMode)) {
      return;
    }

    String eventId = getStringFromDataMap(message, "cloudevents.id", UUID.randomUUID().toString());
    String eventType = getStringFromDataMap(message, "cloudevents.type", binding.cloudEventType);
    String eventSource = getStringFromDataMap(message, "cloudevents.source", binding.cloudEventSource);
    String eventSubject = getStringFromDataMap(message, "cloudevents.subject", null);
    String eventTime = getStringFromDataMap(message, "cloudevents.time", Instant.now().toString());

    headers.put("ce_specversion", "1.0".getBytes(StandardCharsets.UTF_8));
    headers.put("ce_id", eventId.getBytes(StandardCharsets.UTF_8));
    headers.put("ce_type", eventType.getBytes(StandardCharsets.UTF_8));
    headers.put("ce_source", eventSource.getBytes(StandardCharsets.UTF_8));
    headers.put("ce_time", eventTime.getBytes(StandardCharsets.UTF_8));
    if (eventSubject != null) {
      headers.put("ce_subject", eventSubject.getBytes(StandardCharsets.UTF_8));
    }
    if (message.getContentType() != null) {
      headers.put("ce_datacontenttype", message.getContentType().getBytes(StandardCharsets.UTF_8));
    }
  }

  /**
   * Serializes a typed value to UTF-8 bytes.
   *
   * @param typedData source typed data
   * @return serialized bytes or {@code null}
   */
  private byte[] typedDataToBytes(TypedData typedData) {
    Object data = typedData.getData();
    if (data == null) {
      return null;
    }
    return data.toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * Looks up a string value from message data map with a default fallback.
   *
   * @param message source message
   * @param key lookup key
   * @param defaultValue fallback value
   * @return resolved value
   */
  private String getStringFromDataMap(Message message, String key, String defaultValue) {
    if (message == null || message.getDataMap() == null || !message.getDataMap().containsKey(key) || message.getDataMap().get(key) == null) {
      return defaultValue;
    }
    Object value = message.getDataMap().get(key).getData();
    return value == null ? defaultValue : value.toString();
  }

  /**
   * Resolves a header value from byte map as UTF-8 text.
   *
   * @param headers header map
   * @param key header key
   * @return decoded header value or {@code null}
   */
  private String headerValue(Map<String, byte[]> headers, String key) {
    if (!headers.containsKey(key) || headers.get(key) == null) {
      return null;
    }
    return new String(headers.get(key), StandardCharsets.UTF_8);
  }

  /**
   * Enriches inbound messages with pull counters and stream timing metadata.
   *
   * @param binding pull binding
   * @param inboundMessage inbound message
   */
  private void enrichInboundMetrics(PullBinding binding, Message inboundMessage) {
    if (inboundMessage == null || inboundMessage.getDataMap() == null) {
      return;
    }
    inboundMessage.getDataMap().put("redis.pull.received_count", new TypedData(binding.receivedCount.get()));
    inboundMessage.getDataMap().put("redis.pull.error_count", new TypedData(binding.errorCount.get()));
    inboundMessage.getDataMap().put("redis.pull.reconnect_count", new TypedData(binding.reconnectCount.get()));
    inboundMessage.getDataMap().put("redis.stream.pending_count", new TypedData(binding.lastPendingCount.get()));
    inboundMessage.getDataMap().put("redis.stream.ack_latency_ms", new TypedData(binding.lastAckLatencyMs.get()));
  }

  /**
   * Publishes periodic pull metrics as MAPS inbound events.
   */
  private void publishPullMetrics() {
    if (!metricsEnabled) {
      return;
    }

    for (PullBinding binding : pullBindings.values()) {
      try {
        long reconnectCount = binding.reconnectCount.get();
        long reconnectDelta = reconnectCount - binding.lastPublishedReconnectCount;
        boolean reconnectAlert = reconnectDelta >= alertReconnectDeltaThreshold;
        boolean pendingAlert = binding.lastPendingCount.get() >= alertPendingThreshold;

        Map<String, TypedData> map = new LinkedHashMap<>();
        map.put("redis.pull.remote_namespace", new TypedData(binding.remoteNamespace));
        map.put("redis.pull.local_namespace", new TypedData(binding.localNamespace));
        map.put("redis.pull.mode", new TypedData(binding.mode));
        map.put("redis.pull.received_count", new TypedData(binding.receivedCount.get()));
        map.put("redis.pull.error_count", new TypedData(binding.errorCount.get()));
        map.put("redis.pull.reconnect_count", new TypedData(reconnectCount));
        map.put("redis.pull.reconnect_delta", new TypedData(reconnectDelta));
        map.put("redis.stream.pending_count", new TypedData(binding.lastPendingCount.get()));
        map.put("redis.stream.ack_latency_ms", new TypedData(binding.lastAckLatencyMs.get()));
        map.put("redis.alert.pending", new TypedData(pendingAlert));
        map.put("redis.alert.reconnect", new TypedData(reconnectAlert));
        map.put("redis.alert.pending_threshold", new TypedData(alertPendingThreshold));
        map.put("redis.alert.reconnect_delta_threshold", new TypedData(alertReconnectDeltaThreshold));

        String payload = "{\"remote\":\"" + binding.remoteNamespace + "\",\"mode\":\"" + binding.mode + "\"}";
        Message metricsMessage = new MessageBuilder()
            .setDataMap(map)
            .setOpaqueData(payload.getBytes(StandardCharsets.UTF_8))
            .setContentType("application/json")
            .build();

        inbound(metricsNamespace + "/" + binding.remoteNamespace, metricsMessage);
        binding.lastPublishedReconnectCount = reconnectCount;
      } catch (Throwable e) {
        logger.log(RedisLogMessages.REDIS_CONSUMER_ERROR, binding.remoteNamespace, e);
      }
    }
  }

  /**
   * Resolves merged top-level and per-link attributes for the given endpoint.
   *
   * @param endpointNamespace local or remote namespace key
   * @param direction link direction
   * @param lookupByLocalNamespace {@code true} to match by local namespace
   * @return merged attribute map
   */
  private Map<String, Object> findLinkAttributes(String endpointNamespace, String direction, boolean lookupByLocalNamespace) {
    Map<String, Object> attributes = new LinkedHashMap<>();

    if (protocolConfig == null || protocolConfig.getConfig() == null) {
      return attributes;
    }

    Object linksObj = protocolConfig.getConfig().get("links");
    if (linksObj instanceof List) {
      List<?> links = (List<?>) linksObj;
      for (Object linkObj : links) {
        if (!(linkObj instanceof Map)) {
          continue;
        }
        Map<?, ?> link = (Map<?, ?>) linkObj;
        String linkDirection = valueAsString(link.get("direction"), "");
        if (!direction.equalsIgnoreCase(linkDirection)) {
          continue;
        }

        String matchField = lookupByLocalNamespace ? "local_namespace" : "remote_namespace";
        String linkNamespace = valueAsString(link.get(matchField), null);
        if (!Objects.equals(endpointNamespace, linkNamespace)) {
          continue;
        }

        for (Map.Entry<?, ?> entry : link.entrySet()) {
          attributes.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        break;
      }
    }

    Map<String, Object> topLevel = protocolConfig.getConfig();
    Optional.ofNullable(topLevel.get(REDIS_MODE)).ifPresent(v -> attributes.putIfAbsent(REDIS_MODE, v));
    Optional.ofNullable(topLevel.get(CLOUD_EVENT_MODE)).ifPresent(v -> attributes.putIfAbsent(CLOUD_EVENT_MODE, v));
    Optional.ofNullable(topLevel.get(CLOUD_EVENT_TYPE)).ifPresent(v -> attributes.putIfAbsent(CLOUD_EVENT_TYPE, v));
    Optional.ofNullable(topLevel.get(CLOUD_EVENT_SOURCE)).ifPresent(v -> attributes.putIfAbsent(CLOUD_EVENT_SOURCE, v));
    Optional.ofNullable(topLevel.get(REDIS_STREAM_GROUP)).ifPresent(v -> attributes.putIfAbsent(REDIS_STREAM_GROUP, v));
    Optional.ofNullable(topLevel.get(REDIS_STREAM_CONSUMER)).ifPresent(v -> attributes.putIfAbsent(REDIS_STREAM_CONSUMER, v));
    Optional.ofNullable(topLevel.get(REDIS_STREAM_POLL_MS)).ifPresent(v -> attributes.putIfAbsent(REDIS_STREAM_POLL_MS, v));
    Optional.ofNullable(topLevel.get(REDIS_STREAM_MIN_POLL_MS)).ifPresent(v -> attributes.putIfAbsent(REDIS_STREAM_MIN_POLL_MS, v));
    Optional.ofNullable(topLevel.get(REDIS_STREAM_BATCH_SIZE)).ifPresent(v -> attributes.putIfAbsent(REDIS_STREAM_BATCH_SIZE, v));
    Optional.ofNullable(topLevel.get(REDIS_STREAM_PENDING_SAMPLE_EVERY)).ifPresent(v -> attributes.putIfAbsent(REDIS_STREAM_PENDING_SAMPLE_EVERY, v));
    Optional.ofNullable(topLevel.get(REDIS_RECONNECT_INITIAL_MS)).ifPresent(v -> attributes.putIfAbsent(REDIS_RECONNECT_INITIAL_MS, v));
    Optional.ofNullable(topLevel.get(REDIS_RECONNECT_MAX_MS)).ifPresent(v -> attributes.putIfAbsent(REDIS_RECONNECT_MAX_MS, v));

    return attributes;
  }

  /**
   * Normalizes and validates redis link mode.
   *
   * @param modeValue config value
   * @param destination link destination
   * @return normalized mode
   */
  private String parseMode(Object modeValue, String destination) {
    String mode = valueAsString(modeValue, "pubsub").toLowerCase(Locale.ROOT);
    if (!"pubsub".equals(mode) && !"stream".equals(mode)) {
      logger.log(RedisLogMessages.REDIS_CONFIGURATION_WARNING, "Invalid redis.mode " + mode + " on " + destination + ", defaulting to pubsub");
      return "pubsub";
    }
    return mode;
  }

  /**
   * Parses a long with default fallback.
   *
   * @param value config value
   * @param defaultValue fallback value
   * @return parsed long or fallback
   */
  private long parseLong(Object value, long defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /**
   * Parses a boolean with default fallback.
   *
   * @param value config value
   * @param defaultValue fallback value
   * @return parsed boolean
   */
  private boolean parseBoolean(Object value, boolean defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    return Boolean.parseBoolean(String.valueOf(value));
  }

  /**
   * Returns current protocol config map.
   *
   * @return config map, never {@code null}
   */
  private Map<String, Object> currentConfig() {
    if (protocolConfig == null || protocolConfig.getConfig() == null) {
      return new LinkedHashMap<>();
    }
    return protocolConfig.getConfig();
  }

  /**
   * Converts nullable object to trimmed string with default fallback.
   *
   * @param value source value
   * @param defaultValue fallback
   * @return normalized string
   */
  private String valueAsString(Object value, String defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    String str = String.valueOf(value).trim();
    return str.isEmpty() ? defaultValue : str;
  }

  /**
   * Closes any closeable instance and ignores close errors.
   *
   * @param closeable target resource
   */
  private void closeQuietly(AutoCloseable closeable) {
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (Exception ignored) {
      // no-op
    }
  }

  /**
   * Outbound link binding metadata.
   */
  private static class PushBinding {
    private String localNamespace;
    private String remoteNamespace;
    private String mode;
    private String cloudEventMode;
    private String cloudEventType;
    private String cloudEventSource;
  }

  /**
   * Inbound link binding metadata and runtime state.
   */
  private static class PullBinding {
    private String localNamespace;
    private String remoteNamespace;
    private String mode;

    private String streamGroup;
    private String streamConsumer;
    private long streamPollMs;
    private long streamMinPollMs;
    private int streamBatchSize;
    private int streamPendingSampleEvery;
    private long reconnectInitialMs;
    private long reconnectMaxMs;
    private long currentReconnectMs;
    private long nextReconnectAttemptAtMs;
    private long lastPublishedReconnectCount;

    private RedisClient streamClient;
    private StatefulRedisConnection<String, byte[]> streamConnection;
    private RedisCommands<String, byte[]> streamCommands;
    private ScheduledFuture<?> streamTask;

    private final AtomicLong receivedCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong reconnectCount = new AtomicLong();
    private final AtomicLong lastPendingCount = new AtomicLong();
    private final AtomicLong lastAckLatencyMs = new AtomicLong();
  }

  /**
   * Poller that consumes Redis stream entries for a specific binding.
   */
  private class StreamPoller implements Runnable {
    private final PullBinding binding;

    /**
     * Creates a stream poller for one pull binding.
     *
     * @param binding pull binding
     */
    private StreamPoller(PullBinding binding) {
      this.binding = binding;
    }

    /**
     * Polls, dispatches and acknowledges stream messages.
     */
    @Override
    public void run() {
      try {
        tryReconnectStreamBinding(binding);
        if (binding.streamCommands == null) {
          return;
        }

        List<StreamMessage<String, byte[]>> records = binding.streamCommands.xreadgroup(
            Consumer.from(binding.streamGroup, binding.streamConsumer),
            XReadArgs.Builder.block(Duration.ofMillis(Math.max(binding.streamMinPollMs, binding.streamPollMs))).count(binding.streamBatchSize),
            XReadArgs.StreamOffset.lastConsumed(binding.remoteNamespace)
        );

        if (records == null || records.isEmpty()) {
          return;
        }

        for (StreamMessage<String, byte[]> record : records) {
          byte[] envelope = record.getBody().get("maps");
          if (envelope == null) {
            continue;
          }

          try {
            long start = System.nanoTime();
            Message inboundMessage = convertInboundEnvelope(envelope, binding.remoteNamespace, "stream");
            binding.receivedCount.incrementAndGet();
            enrichInboundMetrics(binding, inboundMessage);
            inbound(binding.localNamespace, inboundMessage);
            binding.streamCommands.xack(binding.remoteNamespace, binding.streamGroup, record.getId());
            long ackLatencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            binding.lastAckLatencyMs.set(ackLatencyMs);
          } catch (Throwable e) {
            binding.errorCount.incrementAndGet();
            logger.log(RedisLogMessages.REDIS_FAILED_TO_PROCESS_INCOMING_EVENT, binding.remoteNamespace, e);
          }
        }
        if ((binding.receivedCount.get() % binding.streamPendingSampleEvery) == 0L) {
          PendingMessages pending = binding.streamCommands.xpending(binding.remoteNamespace, binding.streamGroup);
          if (pending != null) {
            binding.lastPendingCount.set(pending.getCount());
          }
        }
      } catch (Throwable e) {
        binding.errorCount.incrementAndGet();
        markStreamDisconnected(binding);
        logger.log(RedisLogMessages.REDIS_CONSUMER_ERROR, binding.remoteNamespace, e);
      }
    }
  }

  /**
   * Immutable snapshot of pull counters for testing and diagnostics.
   */
  static final class PullStats {
    final long receivedCount;
    final long errorCount;
    final long reconnectCount;
    final long pendingCount;
    final long ackLatencyMs;

    /**
     * Creates a pull stats snapshot.
     *
     * @param receivedCount delivered inbound message count
     * @param errorCount processing or connectivity errors
     * @param reconnectCount reconnect attempts that succeeded
     * @param pendingCount current pending stream entries
     * @param ackLatencyMs latest ack latency
     */
    private PullStats(long receivedCount, long errorCount, long reconnectCount, long pendingCount, long ackLatencyMs) {
      this.receivedCount = receivedCount;
      this.errorCount = errorCount;
      this.reconnectCount = reconnectCount;
      this.pendingCount = pendingCount;
      this.ackLatencyMs = ackLatencyMs;
    }
  }

  /**
   * Outbound bridge abstraction to allow production and test transports.
   */
  interface RedisBridge extends AutoCloseable {
    /**
     * Publishes to a Redis pub/sub channel.
     *
     * @param channel channel name
     * @param envelope encoded payload
     */
    void publish(String channel, byte[] envelope);

    /**
     * Appends to a Redis stream.
     *
     * @param stream stream name
     * @param envelope encoded payload
     */
    void appendStream(String stream, byte[] envelope);

    /**
     * Closes underlying resources.
     */
    @Override
    void close();
  }

  /**
   * Lettuce-backed Redis bridge implementation.
   */
  static class LettuceRedisBridge implements RedisBridge {
    private final RedisClient redisClient;
    private final StatefulRedisConnection<String, byte[]> connection;
    private final RedisCommands<String, byte[]> commands;

    /**
     * Opens a synchronous Redis connection for outbound operations.
     *
     * @param redisUrl Redis endpoint URL
     */
    LettuceRedisBridge(String redisUrl) {
      this.redisClient = RedisClient.create(redisUrl);
      this.connection = redisClient.connect(STRING_BYTE_CODEC);
      this.commands = connection.sync();
    }

    /**
     * Publishes encoded envelopes to pub/sub.
     *
     * @param channel channel name
     * @param envelope encoded payload
     */
    @Override
    public void publish(String channel, byte[] envelope) {
      commands.publish(channel, envelope);
    }

    /**
     * Writes encoded envelopes to streams using the {@code maps} field.
     *
     * @param stream stream name
     * @param envelope encoded payload
     */
    @Override
    public void appendStream(String stream, byte[] envelope) {
      Map<String, byte[]> body = new LinkedHashMap<>();
      body.put("maps", envelope);
      commands.xadd(stream, body);
    }

    /**
     * Closes the connection and client.
     */
    @Override
    public void close() {
      connection.close();
      redisClient.shutdown();
    }
  }
}
