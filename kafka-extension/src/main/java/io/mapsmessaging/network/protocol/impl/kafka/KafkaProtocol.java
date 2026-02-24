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

package io.mapsmessaging.network.protocol.impl.kafka;

import io.mapsmessaging.api.MessageBuilder;
import io.mapsmessaging.api.message.Message;
import io.mapsmessaging.api.message.TypedData;
import io.mapsmessaging.dto.rest.config.protocol.impl.ExtensionConfigDTO;
import io.mapsmessaging.logging.Logger;
import io.mapsmessaging.logging.LoggerFactory;
import io.mapsmessaging.network.EndPointURL;
import io.mapsmessaging.network.io.EndPoint;
import io.mapsmessaging.network.protocol.impl.extension.Extension;
import io.mapsmessaging.utilities.threads.SimpleTaskScheduler;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class KafkaProtocol extends Extension {

  private static final String ROUTING_KEY_SOURCE = "routing.key_source";
  private static final String ROUTING_KEY_HEADER = "routing.key_header";
  private static final String ROUTING_KEY_VALUE = "routing.key_value";
  private static final String ROUTING_PARTITION = "routing.partition";
  private static final String ROUTING_HEADERS = "routing.headers";
  private static final String ROUTING_TIMESTAMP_SOURCE = "routing.timestamp_source";

  private static final String LOOP_GUARD_ENABLED = "loopGuard.enabled";
  private static final String LOOP_GUARD_MAX_HOPS = "loopGuard.maxHops";
  private static final String LOOP_ALLOW_SAME_TOPIC = "loop.allow_same_topic";
  private static final String CLOUD_EVENT_MODE = "cloud_event.mode";
  private static final String CLOUD_EVENT_TYPE = "cloud_event.type";
  private static final String CLOUD_EVENT_SOURCE = "cloud_event.source";

  private static final String MAPS_DATA_HEADER_PREFIX = "maps.data.";
  private static final String MAPS_TYPE_HEADER_PREFIX = "maps.type.";
  private static final String MAPS_CONTENT_TYPE_HEADER = "maps.contentType";

  private static final String MAPS_SOURCE_TOPIC_KEY = "maps.kafka.source_topic";
  private static final String MAPS_HOPS_KEY = "maps.kafka.hops";

  private final Logger logger;
  private final EndPointURL url;
  private final ExtensionConfigDTO protocolConfig;

  private Producer<byte[], byte[]> producer;
  private final Map<String, PushBinding> pushBindings;
  private final Map<String, ConsumerBinding> consumerBindings;
  private final Map<String, ScheduledFuture<?>> consumerTasks;

  private long pollTimeoutMillis;
  private long pollIntervalMillis;
  private boolean loopGuardEnabled;
  private int loopGuardMaxHops;

  public KafkaProtocol(@NonNull @NotNull EndPoint endPoint, ExtensionConfigDTO protocolConfigDTO) {
    this.url = new EndPointURL(endPoint.getConfig().getUrl());
    this.protocolConfig = protocolConfigDTO;
    this.logger = LoggerFactory.getLogger(KafkaProtocol.class);
    this.pushBindings = new ConcurrentHashMap<>();
    this.consumerBindings = new ConcurrentHashMap<>();
    this.consumerTasks = new ConcurrentHashMap<>();
    parseGlobalConfig(currentConfig());
  }

  KafkaProtocol(String urlString, Map<String, Object> configMap) {
    this.url = new EndPointURL(urlString);
    this.protocolConfig = new ExtensionConfigDTO() {
      @Override
      public Map<String, Object> getConfig() {
        return configMap;
      }
    };
    this.logger = LoggerFactory.getLogger(KafkaProtocol.class);
    this.pushBindings = new ConcurrentHashMap<>();
    this.consumerBindings = new ConcurrentHashMap<>();
    this.consumerTasks = new ConcurrentHashMap<>();
    parseGlobalConfig(currentConfig());
  }

  void setProducerForTest(Producer<byte[], byte[]> testProducer) {
    this.producer = testProducer;
  }

  Message convertInboundRecordForTest(ConsumerRecord<byte[], byte[]> record) {
    return convertInboundMessage(record);
  }

  @Override
  public void initialise() {
    logger.log(KafkaLogMessages.INITIALISE_KAFKA_ENDPOINT, url.toString());
    Map<String, Object> config = currentConfig();
    parseGlobalConfig(config);

    Properties producerProps = buildProducerProperties(config);
    producer = new KafkaProducer<>(producerProps);
    logger.log(KafkaLogMessages.KAFKA_ENDPOINT_INITIALIZED, bootstrapServers(config));
  }

  @Override
  public void close() throws IOException {
    for (Map.Entry<String, ScheduledFuture<?>> entry : consumerTasks.entrySet()) {
      ScheduledFuture<?> task = entry.getValue();
      if (task != null) {
        task.cancel(true);
      }
    }
    consumerTasks.clear();

    for (Map.Entry<String, ConsumerBinding> entry : consumerBindings.entrySet()) {
      ConsumerBinding binding = entry.getValue();
      if (binding != null && binding.consumer != null) {
        try {
          binding.consumer.wakeup();
          binding.consumer.close();
        } catch (Throwable e) {
          logger.log(KafkaLogMessages.KAFKA_ENDPOINT_CLOSE_ERROR, e);
        }
      }
    }
    consumerBindings.clear();

    if (producer != null) {
      try {
        producer.flush();
        producer.close();
      } catch (Throwable e) {
        logger.log(KafkaLogMessages.KAFKA_ENDPOINT_CLOSE_ERROR, e);
      }
      producer = null;
    }

    logger.log(KafkaLogMessages.KAFKA_ENDPOINT_CLOSED);
    super.close();
  }

  @Override
  public @NonNull String getName() {
    return "KafkaProtocol";
  }

  @Override
  public String getVersion() {
    return "1.0";
  }

  @Override
  public boolean supportsRemoteFiltering() {
    return false;
  }

  @Override
  public void registerLocalLink(@NonNull @NotNull String destination) {
    Map<String, Object> attrs = findLinkAttributes(destination, "push", true);
    String topic = valueAsString(attrs.getOrDefault("remote_namespace", destination), destination);

    PushBinding binding = new PushBinding();
    binding.localNamespace = destination;
    binding.remoteTopic = topic;
    binding.keySource = valueAsString(attrs.getOrDefault(ROUTING_KEY_SOURCE, "none"), "none").toLowerCase(Locale.ROOT);
    binding.keyHeader = valueAsString(attrs.get(ROUTING_KEY_HEADER), null);
    binding.keyValue = valueAsString(attrs.get(ROUTING_KEY_VALUE), null);
    binding.partition = parseIntNullable(attrs.get(ROUTING_PARTITION));
    binding.timestampSource = valueAsString(attrs.getOrDefault(ROUTING_TIMESTAMP_SOURCE, "none"), "none").toLowerCase(Locale.ROOT);
    binding.staticHeaders = parseStringMap(attrs.get(ROUTING_HEADERS));
    binding.allowSameTopic = parseBoolean(attrs.getOrDefault(LOOP_ALLOW_SAME_TOPIC, false), false);
    binding.cloudEventMode = valueAsString(attrs.getOrDefault(CLOUD_EVENT_MODE, "none"), "none").toLowerCase(Locale.ROOT);
    binding.cloudEventType = valueAsString(attrs.getOrDefault(CLOUD_EVENT_TYPE, "io.mapsmessaging.event"), "io.mapsmessaging.event");
    binding.cloudEventSource = valueAsString(attrs.getOrDefault(CLOUD_EVENT_SOURCE, "/" + destination), "/" + destination);

    pushBindings.put(destination, binding);
    logger.log(KafkaLogMessages.KAFKA_SUBSCRIBE_LOCAL_SUCCESS, destination, topic);
  }

  @Override
  public void registerRemoteLink(@NotNull @NotNull String destination, @Nullable String filter) {
    Map<String, Object> attrs = findLinkAttributes(destination, "pull", false);
    String topic = valueAsString(attrs.getOrDefault("remote_namespace", destination), destination);
    String localNamespace = valueAsString(attrs.getOrDefault("local_namespace", destination), destination);

    Properties consumerProps = buildConsumerProperties(currentConfig(), attrs);
    KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(consumerProps);
    consumer.subscribe(List.of(topic));

    ConsumerBinding binding = new ConsumerBinding();
    binding.topic = topic;
    binding.localNamespace = localNamespace;
    binding.consumer = consumer;

    ConsumerBinding previous = consumerBindings.put(topic, binding);
    if (previous != null && previous.consumer != null) {
      try {
        previous.consumer.wakeup();
        previous.consumer.close();
      } catch (Throwable ignored) {
        // no-op
      }
    }

    ScheduledFuture<?> task = SimpleTaskScheduler.getInstance().scheduleAtFixedRate(
        new ConsumerPoller(binding),
        0,
        pollIntervalMillis,
        TimeUnit.MILLISECONDS);
    ScheduledFuture<?> oldTask = consumerTasks.put(topic, task);
    if (oldTask != null) {
      oldTask.cancel(true);
    }

    logger.log(KafkaLogMessages.KAFKA_SUBSCRIBE_REMOTE_SUCCESS, topic, localNamespace);
  }

  @Override
  public void outbound(@NonNull @NotNull String destinationName, @NonNull @NotNull Message message) {
    PushBinding binding = pushBindings.get(destinationName);
    if (binding == null) {
      return;
    }

    if (producer == null) {
      logger.log(KafkaLogMessages.KAFKA_FAILED_TO_SEND_MESSAGE, binding.remoteTopic, new IllegalStateException("Kafka producer not initialized"));
      return;
    }

    if (shouldDropForLoop(binding, message)) {
      return;
    }

    byte[] key = buildRecordKey(binding, message);
    List<Header> headers = buildHeaders(binding, message);
    applyCloudEventHeaders(binding, message, headers);
    Long timestamp = resolveTimestamp(binding, message);

    ProducerRecord<byte[], byte[]> record;
    if (binding.partition != null) {
      record = new ProducerRecord<>(binding.remoteTopic, binding.partition, timestamp, key, message.getOpaqueData(), headers);
    } else {
      record = new ProducerRecord<>(binding.remoteTopic, null, timestamp, key, message.getOpaqueData(), headers);
    }

    producer.send(record, new SendCallback(binding.remoteTopic));
    logger.log(KafkaLogMessages.KAFKA_SEND_MESSAGE, binding.remoteTopic);
  }

  private boolean shouldDropForLoop(PushBinding binding, Message message) {
    if (!loopGuardEnabled) {
      return false;
    }

    int hops = getIntFromDataMap(message, MAPS_HOPS_KEY, 0);
    if (hops >= loopGuardMaxHops) {
      logger.log(KafkaLogMessages.KAFKA_LOOP_HOP_LIMIT_DROPPED, binding.remoteTopic, hops, loopGuardMaxHops);
      return true;
    }

    String sourceTopic = getStringFromDataMap(message, MAPS_SOURCE_TOPIC_KEY, null);
    if (!binding.allowSameTopic && sourceTopic != null && sourceTopic.equals(binding.remoteTopic)) {
      logger.log(KafkaLogMessages.KAFKA_LOOP_SAME_TOPIC_DROPPED, binding.remoteTopic, sourceTopic);
      return true;
    }

    return false;
  }

  private byte[] buildRecordKey(PushBinding binding, Message message) {
    if (binding.keySource == null) {
      return null;
    }

    switch (binding.keySource) {
      case "fixed":
        logger.log(KafkaLogMessages.KAFKA_ROUTING_RULE_APPLIED, ROUTING_KEY_SOURCE, binding.localNamespace);
        return binding.keyValue == null ? null : binding.keyValue.getBytes(StandardCharsets.UTF_8);
      case "header":
        if (binding.keyHeader != null && message.getDataMap() != null && message.getDataMap().containsKey(binding.keyHeader)) {
          logger.log(KafkaLogMessages.KAFKA_ROUTING_RULE_APPLIED, ROUTING_KEY_HEADER, binding.localNamespace);
          Object value = message.getDataMap().get(binding.keyHeader).getData();
          return value == null ? null : value.toString().getBytes(StandardCharsets.UTF_8);
        }
        return null;
      case "correlation":
        logger.log(KafkaLogMessages.KAFKA_ROUTING_RULE_APPLIED, "correlation", binding.localNamespace);
        return message.getCorrelationData();
      case "local_namespace":
        logger.log(KafkaLogMessages.KAFKA_ROUTING_RULE_APPLIED, "local_namespace", binding.localNamespace);
        return binding.localNamespace.getBytes(StandardCharsets.UTF_8);
      case "none":
      default:
        return null;
    }
  }

  private List<Header> buildHeaders(PushBinding binding, Message message) {
    List<Header> headers = new ArrayList<>();
    for (Map.Entry<String, String> entry : binding.staticHeaders.entrySet()) {
      headers.add(new RecordHeader(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8)));
    }

    if (message.getDataMap() != null) {
      for (Map.Entry<String, TypedData> entry : message.getDataMap().entrySet()) {
        if (entry.getValue() == null || entry.getValue().getData() == null) {
          continue;
        }

        String fieldKey = entry.getKey();
        String typeName = entry.getValue().getType().name();
        byte[] valueBytes = typedDataToBytes(entry.getValue());

        if (fieldKey.startsWith("kafka.header.")) {
          String rawHeader = fieldKey.substring("kafka.header.".length());
          if (!rawHeader.trim().isEmpty() && valueBytes != null) {
            headers.add(new RecordHeader(rawHeader, valueBytes));
          }
        }

        headers.add(new RecordHeader(MAPS_TYPE_HEADER_PREFIX + fieldKey, typeName.getBytes(StandardCharsets.UTF_8)));
        if (valueBytes != null) {
          headers.add(new RecordHeader(MAPS_DATA_HEADER_PREFIX + fieldKey, valueBytes));
        }
      }
    }

    if (message.getContentType() != null) {
      headers.add(new RecordHeader(MAPS_CONTENT_TYPE_HEADER, message.getContentType().getBytes(StandardCharsets.UTF_8)));
    }

    return headers;
  }

  private void applyCloudEventHeaders(PushBinding binding, Message message, List<Header> headers) {
    if (!"wrap".equals(binding.cloudEventMode)) {
      return;
    }

    String eventId = getStringFromDataMap(message, "cloudevents.id", UUID.randomUUID().toString());
    String eventType = getStringFromDataMap(message, "cloudevents.type", binding.cloudEventType);
    String eventSource = getStringFromDataMap(message, "cloudevents.source", binding.cloudEventSource);
    String eventSubject = getStringFromDataMap(message, "cloudevents.subject", null);
    String eventTime = getStringFromDataMap(message, "cloudevents.time", Instant.now().toString());

    addOrReplaceHeader(headers, "ce_specversion", "1.0");
    addOrReplaceHeader(headers, "ce_id", eventId);
    addOrReplaceHeader(headers, "ce_type", eventType);
    addOrReplaceHeader(headers, "ce_source", eventSource);
    addOrReplaceHeader(headers, "ce_time", eventTime);
    if (eventSubject != null) {
      addOrReplaceHeader(headers, "ce_subject", eventSubject);
    }
    if (message.getContentType() != null) {
      addOrReplaceHeader(headers, "ce_datacontenttype", message.getContentType());
    }
  }

  private void addOrReplaceHeader(List<Header> headers, String key, String value) {
    for (int i = headers.size() - 1; i >= 0; i--) {
      if (headers.get(i).key().equals(key)) {
        headers.remove(i);
      }
    }
    headers.add(new RecordHeader(key, value.getBytes(StandardCharsets.UTF_8)));
  }

  private byte[] typedDataToBytes(TypedData typedData) {
    Object data = typedData.getData();
    if (data == null) {
      return null;
    }

    switch (typedData.getType()) {
      case STRING:
      case INT:
      case LONG:
      case FLOAT:
      case DOUBLE:
      case BOOLEAN:
      case SHORT:
      case BYTE:
      case CHAR:
        return data.toString().getBytes(StandardCharsets.UTF_8);
      default:
        return data.toString().getBytes(StandardCharsets.UTF_8);
    }
  }

  private Long resolveTimestamp(PushBinding binding, Message message) {
    if ("message".equals(binding.timestampSource) && message.getDataMap() != null && message.getDataMap().containsKey("maps.timestamp")) {
      Object data = message.getDataMap().get("maps.timestamp").getData();
      if (data != null) {
        try {
          logger.log(KafkaLogMessages.KAFKA_ROUTING_RULE_APPLIED, ROUTING_TIMESTAMP_SOURCE, binding.localNamespace);
          return Long.parseLong(data.toString());
        } catch (NumberFormatException ignored) {
          return null;
        }
      }
    }
    if ("now".equals(binding.timestampSource)) {
      logger.log(KafkaLogMessages.KAFKA_ROUTING_RULE_APPLIED, ROUTING_TIMESTAMP_SOURCE, binding.localNamespace);
      return System.currentTimeMillis();
    }
    return null;
  }

  private Message convertInboundMessage(ConsumerRecord<byte[], byte[]> record) {
    MessageBuilder builder = new MessageBuilder().setOpaqueData(record.value() == null ? new byte[0] : record.value());

    Map<String, TypedData> dataMap = new LinkedHashMap<>();
    applyTypedHeaders(dataMap, record);

    dataMap.put("kafka.topic", new TypedData(record.topic()));
    dataMap.put("kafka.partition", new TypedData(record.partition()));
    dataMap.put("kafka.offset", new TypedData(record.offset()));
    if (record.key() != null) {
      dataMap.put("kafka.key", new TypedData(new String(record.key(), StandardCharsets.UTF_8)));
    }
    if (record.timestamp() > 0) {
      dataMap.put("kafka.timestamp", new TypedData(record.timestamp()));
    }

    if (!dataMap.containsKey(MAPS_SOURCE_TOPIC_KEY)) {
      dataMap.put(MAPS_SOURCE_TOPIC_KEY, new TypedData(record.topic()));
    }
    int hops = getIntFromDataMap(dataMap, MAPS_HOPS_KEY, 0) + 1;
    dataMap.put(MAPS_HOPS_KEY, new TypedData(hops));

    String contentType = contentTypeFromHeaders(record);
    if (contentType != null) {
      builder.setContentType(contentType);
    }

    builder.setDataMap(dataMap);
    return builder.build();
  }

  private void applyTypedHeaders(Map<String, TypedData> dataMap, ConsumerRecord<byte[], byte[]> record) {
    Map<String, String> typeMap = new LinkedHashMap<>();
    Map<String, String> valueMap = new LinkedHashMap<>();

    for (Header header : record.headers()) {
      if (header.value() == null) {
        continue;
      }
      String headerKey = header.key();
      String headerValue = new String(header.value(), StandardCharsets.UTF_8);

      if (headerKey.startsWith(MAPS_TYPE_HEADER_PREFIX)) {
        typeMap.put(headerKey.substring(MAPS_TYPE_HEADER_PREFIX.length()), headerValue);
      } else if (headerKey.startsWith(MAPS_DATA_HEADER_PREFIX)) {
        valueMap.put(headerKey.substring(MAPS_DATA_HEADER_PREFIX.length()), headerValue);
      } else {
        dataMap.put("kafka.header." + headerKey, new TypedData(headerValue));
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

  private String contentTypeFromHeaders(ConsumerRecord<byte[], byte[]> record) {
    Header contentType = record.headers().lastHeader(MAPS_CONTENT_TYPE_HEADER);
    if (contentType == null || contentType.value() == null) {
      return null;
    }
    return new String(contentType.value(), StandardCharsets.UTF_8);
  }

  private int getIntFromDataMap(Map<String, TypedData> dataMap, String key, int defaultValue) {
    if (dataMap == null || !dataMap.containsKey(key) || dataMap.get(key) == null || dataMap.get(key).getData() == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(dataMap.get(key).getData().toString());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private int getIntFromDataMap(Message message, String key, int defaultValue) {
    if (message == null || message.getDataMap() == null) {
      return defaultValue;
    }
    return getIntFromDataMap(message.getDataMap(), key, defaultValue);
  }

  private String getStringFromDataMap(Message message, String key, String defaultValue) {
    if (message == null || message.getDataMap() == null || !message.getDataMap().containsKey(key) || message.getDataMap().get(key) == null) {
      return defaultValue;
    }
    Object value = message.getDataMap().get(key).getData();
    return value == null ? defaultValue : value.toString();
  }

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
    Optional.ofNullable(topLevel.get(ROUTING_KEY_SOURCE)).ifPresent(v -> attributes.putIfAbsent(ROUTING_KEY_SOURCE, v));
    Optional.ofNullable(topLevel.get(ROUTING_KEY_HEADER)).ifPresent(v -> attributes.putIfAbsent(ROUTING_KEY_HEADER, v));
    Optional.ofNullable(topLevel.get(ROUTING_KEY_VALUE)).ifPresent(v -> attributes.putIfAbsent(ROUTING_KEY_VALUE, v));
    Optional.ofNullable(topLevel.get(ROUTING_PARTITION)).ifPresent(v -> attributes.putIfAbsent(ROUTING_PARTITION, v));
    Optional.ofNullable(topLevel.get(ROUTING_HEADERS)).ifPresent(v -> attributes.putIfAbsent(ROUTING_HEADERS, v));
    Optional.ofNullable(topLevel.get(ROUTING_TIMESTAMP_SOURCE)).ifPresent(v -> attributes.putIfAbsent(ROUTING_TIMESTAMP_SOURCE, v));
    Optional.ofNullable(topLevel.get(LOOP_ALLOW_SAME_TOPIC)).ifPresent(v -> attributes.putIfAbsent(LOOP_ALLOW_SAME_TOPIC, v));
    Optional.ofNullable(topLevel.get(CLOUD_EVENT_MODE)).ifPresent(v -> attributes.putIfAbsent(CLOUD_EVENT_MODE, v));
    Optional.ofNullable(topLevel.get(CLOUD_EVENT_TYPE)).ifPresent(v -> attributes.putIfAbsent(CLOUD_EVENT_TYPE, v));
    Optional.ofNullable(topLevel.get(CLOUD_EVENT_SOURCE)).ifPresent(v -> attributes.putIfAbsent(CLOUD_EVENT_SOURCE, v));

    return attributes;
  }

  private Properties buildProducerProperties(Map<String, Object> config) {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(config));
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
    props.put(ProducerConfig.CLIENT_ID_CONFIG, valueAsString(config.get("clientId"), "maps-kafka-extension-producer"));
    props.put(ProducerConfig.ACKS_CONFIG, valueAsString(config.get("acks"), "all"));
    props.put(ProducerConfig.RETRIES_CONFIG, parseInt(config.get("retries"), 3));
    props.put(ProducerConfig.LINGER_MS_CONFIG, parseInt(config.get("lingerMs"), 5));
    props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, parseBoolean(config.get("enableIdempotence"), true));
    applySecurityProperties(props, config);
    return props;
  }

  private Properties buildConsumerProperties(Map<String, Object> config, Map<String, Object> linkAttrs) {
    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(config));
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

    String groupId = valueAsString(linkAttrs.get("group_id"), valueAsString(config.get("groupId"), "maps-kafka-extension"));
    String offsetReset = valueAsString(linkAttrs.get("offset_reset"), valueAsString(config.get("offsetReset"), "earliest"));
    String clientId = valueAsString(linkAttrs.get("consumer_client_id"), valueAsString(config.get("consumerClientId"), "maps-kafka-extension-consumer"));

    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset);
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, parseBoolean(config.get("enableAutoCommit"), false));
    props.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId + "-" + valueAsString(linkAttrs.get("remote_namespace"), "topic"));
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, parseInt(linkAttrs.get("max_poll_records"), parseInt(config.get("maxPollRecords"), 200)));

    applySecurityProperties(props, config);
    return props;
  }

  private void applySecurityProperties(Properties props, Map<String, Object> config) {
    setIfPresent(props, "security.protocol", config.get("securityProtocol"));
    setIfPresent(props, "sasl.mechanism", config.get("saslMechanism"));
    setIfPresent(props, "sasl.jaas.config", config.get("saslJaasConfig"));
    setIfPresent(props, "ssl.truststore.location", config.get("sslTruststoreLocation"));
    setIfPresent(props, "ssl.truststore.password", config.get("sslTruststorePassword"));
    setIfPresent(props, "ssl.keystore.location", config.get("sslKeystoreLocation"));
    setIfPresent(props, "ssl.keystore.password", config.get("sslKeystorePassword"));
    setIfPresent(props, "ssl.key.password", config.get("sslKeyPassword"));
  }

  private void setIfPresent(Properties props, String key, Object value) {
    if (value != null && !value.toString().trim().isEmpty()) {
      props.put(key, value.toString());
    }
  }

  private String bootstrapServers(Map<String, Object> config) {
    String explicit = valueAsString(config.get("bootstrapServers"), null);
    if (explicit != null && !explicit.trim().isEmpty()) {
      return explicit;
    }

    String host = valueAsString(url.getHost(), "localhost");
    int port = url.getPort() > 0 ? url.getPort() : 9092;
    return host + ":" + port;
  }

  private Map<String, String> parseStringMap(Object object) {
    Map<String, String> result = new LinkedHashMap<>();
    if (!(object instanceof Map)) {
      return result;
    }
    Map<?, ?> map = (Map<?, ?>) object;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        continue;
      }
      result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
    }
    return result;
  }

  private int parseInt(Object object, int defaultValue) {
    try {
      return object == null ? defaultValue : Integer.parseInt(object.toString());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private Integer parseIntNullable(Object object) {
    if (object == null || object.toString().trim().isEmpty()) {
      return null;
    }
    try {
      return Integer.parseInt(object.toString());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private long parseLong(Object object, long defaultValue) {
    try {
      return object == null ? defaultValue : Long.parseLong(object.toString());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  private boolean parseBoolean(Object object, boolean defaultValue) {
    return object == null ? defaultValue : Boolean.parseBoolean(object.toString());
  }

  private String valueAsString(Object object, String defaultValue) {
    if (object == null) {
      return defaultValue;
    }
    String value = object.toString();
    return value.trim().isEmpty() ? defaultValue : value;
  }

  private Map<String, Object> currentConfig() {
    if (protocolConfig == null || protocolConfig.getConfig() == null) {
      return new HashMap<>();
    }
    return protocolConfig.getConfig();
  }

  private void parseGlobalConfig(Map<String, Object> config) {
    pollTimeoutMillis = parseLong(config.get("pollTimeoutMs"), 250L);
    pollIntervalMillis = parseLong(config.get("pollIntervalMs"), 200L);
    if (pollTimeoutMillis <= 0 || pollIntervalMillis <= 0) {
      logger.log(KafkaLogMessages.KAFKA_CONFIGURATION_WARNING, "pollTimeoutMs/pollIntervalMs must be > 0, falling back to defaults");
      pollTimeoutMillis = 250L;
      pollIntervalMillis = 200L;
    }

    loopGuardEnabled = parseBoolean(config.get(LOOP_GUARD_ENABLED), true);
    loopGuardMaxHops = parseInt(config.get(LOOP_GUARD_MAX_HOPS), 8);
    if (loopGuardMaxHops < 1) {
      logger.log(KafkaLogMessages.KAFKA_CONFIGURATION_WARNING, "loopGuard.maxHops must be >= 1, falling back to 8");
      loopGuardMaxHops = 8;
    }
  }

  private class SendCallback implements Callback {

    private final String topic;

    private SendCallback(String topic) {
      this.topic = topic;
    }

    @Override
    public void onCompletion(RecordMetadata metadata, Exception exception) {
      if (exception != null) {
        logger.log(KafkaLogMessages.KAFKA_FAILED_TO_SEND_MESSAGE, topic, exception);
      }
    }
  }

  private class ConsumerPoller implements Runnable {

    private final ConsumerBinding binding;

    private ConsumerPoller(ConsumerBinding binding) {
      this.binding = binding;
    }

    @Override
    public void run() {
      try {
        ConsumerRecords<byte[], byte[]> records = binding.consumer.poll(Duration.ofMillis(pollTimeoutMillis));
        for (ConsumerRecord<byte[], byte[]> record : records) {
          Message message = convertInboundMessage(record);
          inbound(binding.localNamespace, message);
        }

        if (!records.isEmpty()) {
          binding.consumer.commitSync();
        }
      } catch (org.apache.kafka.common.errors.WakeupException ignored) {
        // expected during close / reconfiguration
      } catch (Throwable e) {
        logger.log(KafkaLogMessages.KAFKA_CONSUMER_ERROR, binding.topic, e);
      }
    }
  }

  private static class PushBinding {
    private String localNamespace;
    private String remoteTopic;
    private String keySource;
    private String keyHeader;
    private String keyValue;
    private Integer partition;
    private String timestampSource;
    private boolean allowSameTopic;
    private String cloudEventMode;
    private String cloudEventType;
    private String cloudEventSource;
    private Map<String, String> staticHeaders = new LinkedHashMap<>();
  }

  private static class ConsumerBinding {
    private String topic;
    private String localNamespace;
    private KafkaConsumer<byte[], byte[]> consumer;
  }
}
