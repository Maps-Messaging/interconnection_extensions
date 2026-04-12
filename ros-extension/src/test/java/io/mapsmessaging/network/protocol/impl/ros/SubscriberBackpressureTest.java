package io.mapsmessaging.network.protocol.impl.ros;

import id.jros2messages.Ros2MessageSerializationUtils;
import id.jrosclient.TopicSubscriber;
import id.jrosmessages.std_msgs.StringMessage;
import io.mapsmessaging.logging.LoggerFactory;
import io.mapsmessaging.network.protocol.impl.ros.messages.sensor_msgs.LaserScanMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the Flow.Subscriber backpressure fix in JRos2ClientAdapter.buildSubscriber().
 *
 * <p>Root cause: TopicSubscriber.onSubscribe() calls subscription.request(1) once (initial
 * demand = 1). TopicSubscriber.onNext() only updates a telemetry counter — it does NOT re-issue
 * demand. A real DDS publisher consumes demand on each delivery; when demand hits 0 it stops
 * sending. The fix is getSubscription().ifPresent(s -> s.request(1)) in onNext(), which re-issues
 * demand after every message and keeps the stream alive.
 *
 * <p>Tests use a synchronous TestSubscription that directly calls subscriber.onSubscribe() and
 * subscriber.onNext() on the test thread, avoiding async thread-pool scheduling. The key invariant:
 * after N messages, totalRequested must equal N + 1 (one initial + one per message).
 */
class SubscriberBackpressureTest {

  private static final RosBridgeConfig CONFIG = new RosBridgeConfig(
      RosBridgeConfig.RosVersion.ROS2, RosBridgeConfig.SchemaMode.STRICT, null, null);

  private static final RosPullBinding STRING_BINDING =
      new RosPullBinding("/maps/chatter", "/chatter", "2", "std_msgs", "String", null);

  private static final RosPullBinding SCAN_BINDING =
      new RosPullBinding("/maps/scan", "/scan", "2", "sensor_msgs", "LaserScan", null);

  private final Ros2MessageSerializationUtils serializer = new Ros2MessageSerializationUtils();

  private JRos2ClientAdapter adapter() {
    return new JRos2ClientAdapter(LoggerFactory.getLogger(SubscriberBackpressureTest.class), CONFIG);
  }

  /**
   * Minimal Flow.Subscription that counts how many times request(n) is called.
   * Each call from super.onNext() increments totalRequested by 1, giving a precise
   * measure of whether backpressure was correctly re-issued.
   */
  private static class TestSubscription implements Flow.Subscription {
    final AtomicLong totalRequested = new AtomicLong();

    @Override
    public void request(long n) {
      totalRequested.addAndGet(n);
    }

    @Override
    public void cancel() {
    }
  }

  // ---------------------------------------------------------------------------
  // Throughput: verify continuous flow at varying message counts
  // ---------------------------------------------------------------------------

  /**
   * Core regression test for the backpressure bug.
   *
   * <p>Parameterized over 10, 100, and 1 000 messages to cover low-throughput (10 Hz),
   * high-throughput (100 Hz), and burst scenarios. After N messages, totalRequested must equal
   * N + 1: one initial request from onSubscribe plus one re-request per onNext via
   * {@code getSubscription().ifPresent(s -> s.request(1))}. Without the fix, totalRequested
   * remains 1 regardless of N.
   */
  @ParameterizedTest(name = "{0} messages")
  @ValueSource(ints = {10, 100, 1000})
  void shouldRenewDemandAfterEachMessage(int count) {
    List<RosMessageEnvelope> received = new ArrayList<>();
    TopicSubscriber<StringMessage> subscriber = adapter().buildSubscriber(
        StringMessage.class, STRING_BINDING, received::add);

    TestSubscription subscription = new TestSubscription();
    subscriber.onSubscribe(subscription);

    long initialDemand = subscription.totalRequested.get();
    assertEquals(1, initialDemand,
        "TopicSubscriber must issue an initial request(1) in onSubscribe");

    for (int i = 0; i < count; i++) {
      StringMessage msg = new StringMessage();
      msg.data = "hello-" + i;
      subscriber.onNext(msg);
    }

    assertEquals(count, received.size(), "Every message must reach the listener");

    long expectedDemand = initialDemand + count; // 1 initial + 1 per message via getSubscription().request(1)
    assertEquals(expectedDemand, subscription.totalRequested.get(),
        "getSubscription().ifPresent(s -> s.request(1)) must be called after each delivery; "
            + "totalRequested=" + subscription.totalRequested.get()
            + " but expected " + expectedDemand
            + ". If totalRequested==1 the fix is missing.");
  }

  /**
   * Validates that no messages are dropped when all are delivered consecutively without pause.
   * Simulates a burst at the maximum rate a subscriber can process synchronously.
   */
  @Test
  void shouldNotDropAnyMessagesUnderBurstDelivery() {
    int count = 500;
    List<RosMessageEnvelope> received = new ArrayList<>();
    TopicSubscriber<StringMessage> subscriber = adapter().buildSubscriber(
        StringMessage.class, STRING_BINDING, received::add);

    subscriber.onSubscribe(new TestSubscription());

    for (int i = 0; i < count; i++) {
      StringMessage msg = new StringMessage();
      msg.data = "burst-" + i;
      subscriber.onNext(msg);
    }

    assertEquals(count, received.size(),
        "All " + count + " burst messages must reach the listener without drops");
  }

  /**
   * Verifies message order is preserved under sequential delivery.
   * The subscriber must not reorder or skip messages.
   */
  @Test
  void messagesShouldArriveInOrderWithoutGaps() {
    int count = 100;
    List<String> payloads = new ArrayList<>();
    TopicSubscriber<StringMessage> subscriber = adapter().buildSubscriber(
        StringMessage.class, STRING_BINDING, envelope -> {
          StringMessage decoded = serializer.read(envelope.payload(), StringMessage.class);
          payloads.add(decoded.data);
        });

    subscriber.onSubscribe(new TestSubscription());

    for (int i = 0; i < count; i++) {
      StringMessage msg = new StringMessage();
      msg.data = "seq-" + i;
      subscriber.onNext(msg);
    }

    assertEquals(count, payloads.size(), "No messages may be dropped");
    for (int i = 0; i < count; i++) {
      assertEquals("seq-" + i, payloads.get(i),
          "Message at index " + i + " arrived out of order or is missing");
    }
  }

  // ---------------------------------------------------------------------------
  // Payload size: verify serialisation round-trip at the subscriber boundary
  // ---------------------------------------------------------------------------

  /**
   * Small payload: a short StringMessage ("hello") — baseline for the 1 Hz "hello" scenario
   * described in the bug report. Verifies the fix works for the exact reported case.
   */
  @Test
  void smallPayloadShouldRoundTripThroughSubscriber() {
    String expected = "hello";
    List<RosMessageEnvelope> received = new ArrayList<>();
    TopicSubscriber<StringMessage> subscriber = adapter().buildSubscriber(
        StringMessage.class, STRING_BINDING, received::add);

    subscriber.onSubscribe(new TestSubscription());

    StringMessage msg = new StringMessage();
    msg.data = expected;
    subscriber.onNext(msg);

    assertEquals(1, received.size());
    StringMessage decoded = serializer.read(received.get(0).payload(), StringMessage.class);
    assertEquals(expected, decoded.data, "Small payload must survive the subscriber round-trip");
  }

  /**
   * Medium payload: a ~1 KB StringMessage — typical for JSON-encoded sensor metadata or log
   * lines. Verifies serialisation handles non-trivial string sizes without truncation.
   * Delivered 10 times to exercise the re-request loop at medium throughput.
   */
  @Test
  void mediumPayloadShouldRoundTripThroughSubscriber() {
    String expected = "x".repeat(1024);
    List<RosMessageEnvelope> received = new ArrayList<>();
    TopicSubscriber<StringMessage> subscriber = adapter().buildSubscriber(
        StringMessage.class, STRING_BINDING, received::add);

    subscriber.onSubscribe(new TestSubscription());

    for (int i = 0; i < 10; i++) {
      StringMessage msg = new StringMessage();
      msg.data = expected;
      subscriber.onNext(msg);
    }

    assertEquals(10, received.size());
    for (RosMessageEnvelope envelope : received) {
      StringMessage decoded = serializer.read(envelope.payload(), StringMessage.class);
      assertEquals(1024, decoded.data.length(), "Medium payload must not be truncated");
    }
  }

  /**
   * Large payload: a LaserScanMessage with 1 080 float ranges (full 360° scan at 3 readings per
   * degree — a realistic industrial LiDAR profile). Verifies that large binary arrays are
   * preserved across the subscriber serialisation boundary under repeated delivery.
   * Delivered 10 times to exercise the re-request loop with large structured messages.
   */
  @Test
  void largePayloadShouldRoundTripThroughSubscriber() {
    int rangeCount = 1080;
    float[] ranges = new float[rangeCount];
    for (int i = 0; i < rangeCount; i++) {
      ranges[i] = 0.1f + i * 0.01f;
    }

    List<RosMessageEnvelope> received = new ArrayList<>();
    TopicSubscriber<LaserScanMessage> subscriber = adapter().buildSubscriber(
        LaserScanMessage.class, SCAN_BINDING, received::add);

    subscriber.onSubscribe(new TestSubscription());

    for (int i = 0; i < 10; i++) {
      LaserScanMessage scan = new LaserScanMessage()
          .withRanges(ranges)
          .withIntensities(new float[rangeCount]);
      scan.angle_min = -3.14159f;
      scan.angle_max = 3.14159f;
      scan.range_min = 0.1f;
      scan.range_max = 30.0f;
      subscriber.onNext(scan);
    }

    assertEquals(10, received.size(), "All large-payload scans must be delivered");
    for (RosMessageEnvelope envelope : received) {
      LaserScanMessage decoded = serializer.read(envelope.payload(), LaserScanMessage.class);
      assertEquals(rangeCount, decoded.ranges.length,
          "All " + rangeCount + " float ranges must survive the subscriber round-trip");
      assertEquals(ranges[0], decoded.ranges[0], 1e-4f, "First range value must be preserved");
      assertEquals(ranges[rangeCount - 1], decoded.ranges[rangeCount - 1], 1e-4f,
          "Last range value must be preserved");
    }
  }
}
