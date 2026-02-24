package io.mapsmessaging.network.protocol.impl.kafka;

import io.mapsmessaging.api.MessageBuilder;
import io.mapsmessaging.api.message.Message;
import io.mapsmessaging.api.message.TypedData;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KafkaProtocolRoutingTest {

  private MockProducer<byte[], byte[]> producer;

  @BeforeEach
  void setUp() {
    producer = new MockProducer<>(true, new ByteArraySerializer(), new ByteArraySerializer());
  }

  @Test
  void outbound_HeaderKey_PartitionAndHeaders_AreApplied() {
    KafkaProtocol protocol = new KafkaProtocol("kafka://localhost:9092/", createHeaderKeyConfig());
    protocol.setProducerForTest(producer);
    protocol.registerLocalLink("/telematics/outbound");

    Map<String, TypedData> dataMap = new HashMap<>();
    dataMap.put("tenantId", new TypedData("tenant-a"));
    dataMap.put("eventType", new TypedData("speed"));

    Message message = new MessageBuilder()
        .setDataMap(dataMap)
        .setOpaqueData("payload".getBytes(StandardCharsets.UTF_8))
        .setContentType("application/json")
        .build();

    protocol.outbound("/telematics/outbound", message);

    ProducerRecord<byte[], byte[]> record = captureSingleRecord();
    assertEquals("vehicle.telemetry.events", record.topic());
    assertEquals(2, record.partition());
    assertArrayEquals("tenant-a".getBytes(StandardCharsets.UTF_8), record.key());

    assertEquals("telematics", header(record, "x-route"));
    assertEquals("speed", typedHeaderValue(record, "eventType"));
    assertEquals("STRING", typedHeaderType(record, "eventType"));
    assertEquals("application/json", header(record, "maps.contentType"));
  }

  @Test
  void outbound_FixedKey_GlobalDefaultRule_IsApplied() {
    KafkaProtocol protocol = new KafkaProtocol("kafka://localhost:9092/", createFixedKeyGlobalDefaultConfig());
    protocol.setProducerForTest(producer);
    protocol.registerLocalLink("/audit/outbound");

    Message message = new MessageBuilder()
        .setOpaqueData("audit".getBytes(StandardCharsets.UTF_8))
        .build();

    protocol.outbound("/audit/outbound", message);

    ProducerRecord<byte[], byte[]> record = captureSingleRecord();
    assertEquals("platform.audit.events", record.topic());
    assertArrayEquals("global-fixed".getBytes(StandardCharsets.UTF_8), record.key());
  }

  @Test
  void outbound_CorrelationKeyRule_IsApplied() {
    KafkaProtocol protocol = new KafkaProtocol("kafka://localhost:9092/", createCorrelationConfig());
    protocol.setProducerForTest(producer);
    protocol.registerLocalLink("/commands/outbound");

    byte[] correlation = "corr-123".getBytes(StandardCharsets.UTF_8);
    Message message = new MessageBuilder()
        .setOpaqueData("cmd".getBytes(StandardCharsets.UTF_8))
        .setCorrelationData(correlation)
        .build();

    protocol.outbound("/commands/outbound", message);

    ProducerRecord<byte[], byte[]> record = captureSingleRecord();
    assertArrayEquals(correlation, record.key());
  }

  @Test
  void outbound_MessageTimestampRule_UsesMapsTimestampDataMap() {
    KafkaProtocol protocol = new KafkaProtocol("kafka://localhost:9092/", createMessageTimestampConfig());
    protocol.setProducerForTest(producer);
    protocol.registerLocalLink("/alerts/outbound");

    long ts = 1730000000123L;
    Map<String, TypedData> dataMap = new HashMap<>();
    dataMap.put("maps.timestamp", new TypedData(ts));

    Message message = new MessageBuilder()
        .setDataMap(dataMap)
        .setOpaqueData("alert".getBytes(StandardCharsets.UTF_8))
        .build();

    protocol.outbound("/alerts/outbound", message);

    ProducerRecord<byte[], byte[]> record = captureSingleRecord();
    assertEquals(ts, record.timestamp());
  }

  @Test
  void outbound_DropsWhenSameTopicLoopDetected() {
    KafkaProtocol protocol = new KafkaProtocol("kafka://localhost:9092/", createLoopDropConfig());
    protocol.setProducerForTest(producer);
    protocol.registerLocalLink("/loop/out");

    Map<String, TypedData> dataMap = new HashMap<>();
    dataMap.put("maps.kafka.source_topic", new TypedData("stream.loop"));
    dataMap.put("maps.kafka.hops", new TypedData(1));

    Message message = new MessageBuilder()
        .setDataMap(dataMap)
        .setOpaqueData("loop".getBytes(StandardCharsets.UTF_8))
        .build();

    protocol.outbound("/loop/out", message);

    assertTrue(producer.history().isEmpty());
  }

  @Test
  void outbound_DropsWhenHopLimitExceeded() {
    KafkaProtocol protocol = new KafkaProtocol("kafka://localhost:9092/", createHopLimitConfig());
    protocol.setProducerForTest(producer);
    protocol.registerLocalLink("/hop/out");

    Map<String, TypedData> dataMap = new HashMap<>();
    dataMap.put("maps.kafka.source_topic", new TypedData("stream.source"));
    dataMap.put("maps.kafka.hops", new TypedData(2));

    Message message = new MessageBuilder()
        .setDataMap(dataMap)
        .setOpaqueData("hop".getBytes(StandardCharsets.UTF_8))
        .build();

    protocol.outbound("/hop/out", message);

    assertTrue(producer.history().isEmpty());
  }

  @Test
  void typedRoundTrip_MapsToKafkaToMaps_RetainsTypeInformation() {
    KafkaProtocol protocol = new KafkaProtocol("kafka://localhost:9092/", createRoundTripOutConfig());
    protocol.setProducerForTest(producer);
    protocol.registerLocalLink("/roundtrip/out");

    Map<String, TypedData> original = new LinkedHashMap<>();
    original.put("name", new TypedData("alpha"));
    original.put("count", new TypedData(7));
    original.put("ratio", new TypedData(2.5d));
    original.put("active", new TypedData(true));
    original.put("maps.kafka.hops", new TypedData(0));
    original.put("maps.kafka.source_topic", new TypedData("source.events"));

    Message outboundMessage = new MessageBuilder()
        .setDataMap(original)
        .setOpaqueData("typed".getBytes(StandardCharsets.UTF_8))
        .build();

    protocol.outbound("/roundtrip/out", outboundMessage);
    ProducerRecord<byte[], byte[]> produced = captureSingleRecord();

    ConsumerRecord<byte[], byte[]> inboundRecord = new ConsumerRecord<>(produced.topic(), 0, 0L, produced.key(), produced.value());
    produced.headers().forEach(h -> inboundRecord.headers().add(h.key(), h.value()));

    Message roundTrip = protocol.convertInboundRecordForTest(inboundRecord);

    assertEquals(original.get("name").getType(), roundTrip.getDataMap().get("name").getType());
    assertEquals("alpha", roundTrip.getDataMap().get("name").getData());
    assertEquals(original.get("count").getType(), roundTrip.getDataMap().get("count").getType());
    assertEquals(7, roundTrip.getDataMap().get("count").getData());
    assertEquals(original.get("ratio").getType(), roundTrip.getDataMap().get("ratio").getType());
    assertEquals(2.5d, (Double) roundTrip.getDataMap().get("ratio").getData(), 0.0001d);
    assertEquals(original.get("active").getType(), roundTrip.getDataMap().get("active").getType());
    assertEquals(true, roundTrip.getDataMap().get("active").getData());
  }

  @Test
  void typedRoundTrip_KafkaToMapsToKafka_RetainsTypeInformation() {
    KafkaProtocol protocol = new KafkaProtocol("kafka://localhost:9092/", createPipelineConfig());
    protocol.setProducerForTest(producer);
    protocol.registerLocalLink("/pipeline/out");

    ConsumerRecord<byte[], byte[]> inboundRecord = new ConsumerRecord<>("pipeline.in", 1, 42L, null, "payload".getBytes(StandardCharsets.UTF_8));
    inboundRecord.headers().add("maps.type.sensorId", "STRING".getBytes(StandardCharsets.UTF_8));
    inboundRecord.headers().add("maps.data.sensorId", "s-1".getBytes(StandardCharsets.UTF_8));
    inboundRecord.headers().add("maps.type.speed", "INT".getBytes(StandardCharsets.UTF_8));
    inboundRecord.headers().add("maps.data.speed", "88".getBytes(StandardCharsets.UTF_8));
    inboundRecord.headers().add("maps.type.valid", "BOOLEAN".getBytes(StandardCharsets.UTF_8));
    inboundRecord.headers().add("maps.data.valid", "true".getBytes(StandardCharsets.UTF_8));

    Message mapsMessage = protocol.convertInboundRecordForTest(inboundRecord);
    assertEquals("s-1", mapsMessage.getDataMap().get("sensorId").getData());
    assertEquals(88, mapsMessage.getDataMap().get("speed").getData());
    assertEquals(true, mapsMessage.getDataMap().get("valid").getData());

    protocol.outbound("/pipeline/out", mapsMessage);
    ProducerRecord<byte[], byte[]> produced = captureSingleRecord();

    assertEquals("STRING", typedHeaderType(produced, "sensorId"));
    assertEquals("s-1", typedHeaderValue(produced, "sensorId"));
    assertEquals("INT", typedHeaderType(produced, "speed"));
    assertEquals("88", typedHeaderValue(produced, "speed"));
    assertEquals("BOOLEAN", typedHeaderType(produced, "valid"));
    assertEquals("true", typedHeaderValue(produced, "valid"));
  }

  @Test
  void cloudEventRoundTrip_KafkaToCloudEventToKafka_RetainsPayloadHeadersAndTypes() {
    KafkaProtocol protocol = new KafkaProtocol("kafka://localhost:9092/", createCloudEventPipelineConfig());
    protocol.setProducerForTest(producer);
    protocol.registerLocalLink("/ce/wrap");
    protocol.registerLocalLink("/ce/final");

    ConsumerRecord<byte[], byte[]> inboundRecord = new ConsumerRecord<>("raw.in", 1, 42L, null, "payload-kafka".getBytes(StandardCharsets.UTF_8));
    inboundRecord.headers().add("x-trace-id", "trace-1".getBytes(StandardCharsets.UTF_8));
    inboundRecord.headers().add("maps.type.speed", "INT".getBytes(StandardCharsets.UTF_8));
    inboundRecord.headers().add("maps.data.speed", "88".getBytes(StandardCharsets.UTF_8));
    inboundRecord.headers().add("maps.type.valid", "BOOLEAN".getBytes(StandardCharsets.UTF_8));
    inboundRecord.headers().add("maps.data.valid", "true".getBytes(StandardCharsets.UTF_8));

    Message mapsMessage = protocol.convertInboundRecordForTest(inboundRecord);
    protocol.outbound("/ce/wrap", mapsMessage);
    ProducerRecord<byte[], byte[]> wrapped = producer.history().get(0);

    assertEquals("1.0", header(wrapped, "ce_specversion"));
    assertEquals("io.test.kafka", header(wrapped, "ce_type"));
    assertEquals("/tests/kafka", header(wrapped, "ce_source"));

    ConsumerRecord<byte[], byte[]> wrappedInbound = new ConsumerRecord<>(wrapped.topic(), 0, 0L, wrapped.key(), wrapped.value());
    wrapped.headers().forEach(h -> wrappedInbound.headers().add(h.key(), h.value()));
    Message afterCloudEvent = protocol.convertInboundRecordForTest(wrappedInbound);

    protocol.outbound("/ce/final", afterCloudEvent);
    ProducerRecord<byte[], byte[]> finalRecord = producer.history().get(1);

    assertArrayEquals("payload-kafka".getBytes(StandardCharsets.UTF_8), finalRecord.value());
    assertEquals("trace-1", header(finalRecord, "x-trace-id"));
    assertEquals("88", typedHeaderValue(finalRecord, "speed"));
    assertEquals("INT", typedHeaderType(finalRecord, "speed"));
    assertEquals("true", typedHeaderValue(finalRecord, "valid"));
    assertEquals("BOOLEAN", typedHeaderType(finalRecord, "valid"));
  }

  @Test
  void cloudEventRoundTrip_MapsToCloudEventToKafka_RetainsPayloadHeadersAndTypes() {
    KafkaProtocol protocol = new KafkaProtocol("kafka://localhost:9092/", createCloudEventPipelineConfig());
    protocol.setProducerForTest(producer);
    protocol.registerLocalLink("/ce/wrap");
    protocol.registerLocalLink("/ce/final");

    Map<String, TypedData> map = new LinkedHashMap<>();
    map.put("sensorId", new TypedData("s-1"));
    map.put("count", new TypedData(17));
    map.put("active", new TypedData(true));
    map.put("kafka.header.x-customer", new TypedData("customer-1"));

    Message mapsOutbound = new MessageBuilder()
        .setDataMap(map)
        .setOpaqueData("payload-maps".getBytes(StandardCharsets.UTF_8))
        .setContentType("application/json")
        .build();

    protocol.outbound("/ce/wrap", mapsOutbound);
    ProducerRecord<byte[], byte[]> wrapped = producer.history().get(0);

    assertEquals("1.0", header(wrapped, "ce_specversion"));
    assertEquals("application/json", header(wrapped, "ce_datacontenttype"));

    ConsumerRecord<byte[], byte[]> wrappedInbound = new ConsumerRecord<>(wrapped.topic(), 0, 0L, wrapped.key(), wrapped.value());
    wrapped.headers().forEach(h -> wrappedInbound.headers().add(h.key(), h.value()));
    Message afterCloudEvent = protocol.convertInboundRecordForTest(wrappedInbound);

    protocol.outbound("/ce/final", afterCloudEvent);
    ProducerRecord<byte[], byte[]> finalRecord = producer.history().get(1);

    assertArrayEquals("payload-maps".getBytes(StandardCharsets.UTF_8), finalRecord.value());
    assertEquals("customer-1", header(finalRecord, "x-customer"));
    assertEquals("s-1", typedHeaderValue(finalRecord, "sensorId"));
    assertEquals("STRING", typedHeaderType(finalRecord, "sensorId"));
    assertEquals("17", typedHeaderValue(finalRecord, "count"));
    assertEquals("INT", typedHeaderType(finalRecord, "count"));
    assertEquals("true", typedHeaderValue(finalRecord, "active"));
    assertEquals("BOOLEAN", typedHeaderType(finalRecord, "active"));
  }

  private ProducerRecord<byte[], byte[]> captureSingleRecord() {
    assertEquals(1, producer.history().size());
    return producer.history().get(0);
  }

  private String header(ProducerRecord<byte[], byte[]> record, String key) {
    if (record.headers().lastHeader(key) == null) {
      return null;
    }
    return new String(record.headers().lastHeader(key).value(), StandardCharsets.UTF_8);
  }

  private String typedHeaderType(ProducerRecord<byte[], byte[]> record, String field) {
    return header(record, "maps.type." + field);
  }

  private String typedHeaderValue(ProducerRecord<byte[], byte[]> record, String field) {
    return header(record, "maps.data." + field);
  }

  private Map<String, Object> createHeaderKeyConfig() {
    Map<String, Object> config = new HashMap<>();

    Map<String, Object> link = new HashMap<>();
    link.put("direction", "push");
    link.put("local_namespace", "/telematics/outbound");
    link.put("remote_namespace", "vehicle.telemetry.events");
    link.put("routing.key_source", "header");
    link.put("routing.key_header", "tenantId");
    link.put("routing.partition", 2);

    Map<String, Object> headers = new HashMap<>();
    headers.put("x-route", "telematics");
    link.put("routing.headers", headers);

    config.put("links", List.of(link));
    return config;
  }

  private Map<String, Object> createFixedKeyGlobalDefaultConfig() {
    Map<String, Object> config = new HashMap<>();
    config.put("routing.key_source", "fixed");
    config.put("routing.key_value", "global-fixed");

    Map<String, Object> link = new HashMap<>();
    link.put("direction", "push");
    link.put("local_namespace", "/audit/outbound");
    link.put("remote_namespace", "platform.audit.events");

    config.put("links", List.of(link));
    return config;
  }

  private Map<String, Object> createCorrelationConfig() {
    Map<String, Object> config = new HashMap<>();

    Map<String, Object> link = new HashMap<>();
    link.put("direction", "push");
    link.put("local_namespace", "/commands/outbound");
    link.put("remote_namespace", "vehicle.commands.dispatch");
    link.put("routing.key_source", "correlation");

    config.put("links", List.of(link));
    return config;
  }

  private Map<String, Object> createMessageTimestampConfig() {
    Map<String, Object> config = new HashMap<>();

    Map<String, Object> link = new HashMap<>();
    link.put("direction", "push");
    link.put("local_namespace", "/alerts/outbound");
    link.put("remote_namespace", "vehicle.alerts");
    link.put("routing.timestamp_source", "message");

    config.put("links", List.of(link));
    return config;
  }

  private Map<String, Object> createLoopDropConfig() {
    Map<String, Object> config = new HashMap<>();
    config.put("loopGuard.enabled", true);
    config.put("loopGuard.maxHops", 8);

    Map<String, Object> link = new HashMap<>();
    link.put("direction", "push");
    link.put("local_namespace", "/loop/out");
    link.put("remote_namespace", "stream.loop");
    link.put("loop.allow_same_topic", false);

    config.put("links", List.of(link));
    return config;
  }

  private Map<String, Object> createHopLimitConfig() {
    Map<String, Object> config = new HashMap<>();
    config.put("loopGuard.enabled", true);
    config.put("loopGuard.maxHops", 2);

    Map<String, Object> link = new HashMap<>();
    link.put("direction", "push");
    link.put("local_namespace", "/hop/out");
    link.put("remote_namespace", "stream.derived");

    config.put("links", List.of(link));
    return config;
  }

  private Map<String, Object> createRoundTripOutConfig() {
    Map<String, Object> config = new HashMap<>();

    Map<String, Object> link = new HashMap<>();
    link.put("direction", "push");
    link.put("local_namespace", "/roundtrip/out");
    link.put("remote_namespace", "roundtrip.events");

    config.put("links", List.of(link));
    return config;
  }

  private Map<String, Object> createPipelineConfig() {
    Map<String, Object> config = new HashMap<>();

    Map<String, Object> link = new HashMap<>();
    link.put("direction", "push");
    link.put("local_namespace", "/pipeline/out");
    link.put("remote_namespace", "pipeline.out");

    config.put("links", List.of(link));
    return config;
  }

  private Map<String, Object> createCloudEventPipelineConfig() {
    Map<String, Object> config = new HashMap<>();

    Map<String, Object> wrapLink = new HashMap<>();
    wrapLink.put("direction", "push");
    wrapLink.put("local_namespace", "/ce/wrap");
    wrapLink.put("remote_namespace", "events.cloudevents");
    wrapLink.put("cloud_event.mode", "wrap");
    wrapLink.put("cloud_event.type", "io.test.kafka");
    wrapLink.put("cloud_event.source", "/tests/kafka");

    Map<String, Object> finalLink = new HashMap<>();
    finalLink.put("direction", "push");
    finalLink.put("local_namespace", "/ce/final");
    finalLink.put("remote_namespace", "events.final");

    config.put("links", List.of(wrapLink, finalLink));
    return config;
  }
}
