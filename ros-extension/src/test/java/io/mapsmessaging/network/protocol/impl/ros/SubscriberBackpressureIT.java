package io.mapsmessaging.network.protocol.impl.ros;

import id.jros2messages.Ros2MessageSerializationUtils;
import id.jrosmessages.Message;
import id.jrosmessages.std_msgs.StringMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.sensor_msgs.LaserScanMessage;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.jacoco.core.tools.ExecDumpClient;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test that verifies the subscriber backpressure fix using a real ROS2 container.
 *
 * <p>The reported bug: a 1 Hz "hello" publisher on ROS2 delivers only a single message to Maps
 * and then stops. Root cause: {@code TopicSubscriber.onSubscribe()} calls
 * {@code subscription.request(1)} once; without re-issuing demand after each delivery the DDS
 * publisher sees zero outstanding requests and stops sending.
 *
 * <p>Each test case publishes N messages at a given rate via {@code ros2 topic pub --times N
 * --rate R} inside a ROS2 container and asserts that Maps receives all N of them via MQTT — not
 * just the first. Both throughput scenarios (1 Hz, 10 Hz, 20 Hz burst) and payload sizes (small
 * string, ~1 KB string, LaserScan with 10 ranges) are covered.
 *
 * <p>The test skips gracefully when Docker or the Maps container image is unavailable, mirroring
 * the guard used in {@link MapsRos2MessageTypesIT}.
 */
class SubscriberBackpressureIT {

  private static final String DEFAULT_ROS_IMAGE_AMD64 = "osrf/ros:jazzy-desktop";
  private static final String DEFAULT_ROS_IMAGE_ARM64 = "arm64v8/ros:jazzy-perception";
  private static final String DEFAULT_RMW_IMPLEMENTATION = "rmw_fastrtps_cpp";
  private static final String DEFAULT_AUTOMATIC_DISCOVERY_RANGE = "SUBNET";
  // Static peers include "ros-bp" (ROS container alias) and "maps-bp" (Maps alias)
  private static final String DEFAULT_STATIC_PEERS = "ros-bp;maps-bp";

  // Use domain 26 to avoid collisions if MapsRos2MessageTypesIT runs concurrently (domain 25)
  private static final int ROS_DOMAIN_ID = 26;
  private static final int MQTT_PORT = 1883;
  private static final int MAPS_JACOCO_PORT = 6300;

  private static final Ros2MessageSerializationUtils SERIALIZER = new Ros2MessageSerializationUtils();
  private static final List<ThroughputCase> CASES = buildCases();

  private static GenericContainer<?> mapsContainer;
  private static GenericContainer<?> rosContainer;
  private static Network e2eNetwork;

  private static String mqttUrl;
  private static String skipReason;
  private static String resolvedMapsImage;
  private static String resolvedRosImage;
  private static String rmwImplementation;
  private static String automaticDiscoveryRange;
  private static String staticPeers;
  private static Path runArtifactPath;

  // ---------------------------------------------------------------------------
  // Test cases
  // ---------------------------------------------------------------------------

  private static List<ThroughputCase> buildCases() {
    return List.of(

        // The exact bug report scenario: 1 Hz "hello" string, 10 messages.
        // Before the fix only the first message arrived; with the fix all 10 must arrive.
        new ThroughputCase(
            "baseline-1hz",
            "/ros2_bp_baseline", "/maps/ros/bp/baseline",
            "std_msgs", "String", StringMessage.class,
            "{data: 'hello'}", 10, 1,
            msg -> assertEquals("hello", ((StringMessage) msg).data,
                "Payload must survive the ROS2 → Maps bridge")),

        // Higher-frequency stream (10 Hz, 20 messages = 2 seconds of publishing).
        new ThroughputCase(
            "stream-10hz",
            "/ros2_bp_10hz", "/maps/ros/bp/10hz",
            "std_msgs", "String", StringMessage.class,
            "{data: 'hello'}", 20, 10,
            msg -> assertEquals("hello", ((StringMessage) msg).data)),

        // Burst scenario (20 Hz, 50 messages = 2.5 seconds).
        // Validates that backpressure re-request keeps up with a rapid publisher.
        new ThroughputCase(
            "burst-20hz",
            "/ros2_bp_burst", "/maps/ros/bp/burst",
            "std_msgs", "String", StringMessage.class,
            "{data: 'hello'}", 50, 20,
            msg -> assertEquals("hello", ((StringMessage) msg).data)),

        // Small payload: 5-character string at 5 Hz.
        new ThroughputCase(
            "small-payload",
            "/ros2_bp_small", "/maps/ros/bp/small",
            "std_msgs", "String", StringMessage.class,
            "{data: 'hello'}", 10, 5,
            msg -> assertEquals("hello", ((StringMessage) msg).data,
                "Small 5-char payload must be preserved")),

        // Medium payload: ~1 KB string at 5 Hz.
        // Verifies the serialiser handles non-trivial payload sizes without truncation.
        new ThroughputCase(
            "medium-payload",
            "/ros2_bp_medium", "/maps/ros/bp/medium",
            "std_msgs", "String", StringMessage.class,
            "{data: '" + "x".repeat(1024) + "'}", 10, 5,
            msg -> assertEquals(1024, ((StringMessage) msg).data.length(),
                "~1 KB payload must not be truncated across the bridge")),

        // Large structured payload: LaserScan with 10 float ranges at 2 Hz.
        // Tests that binary arrays survive serialisation under repeated delivery.
        new ThroughputCase(
            "laserscan-10ranges",
            "/ros2_bp_scan", "/maps/ros/bp/scan",
            "sensor_msgs", "LaserScan", LaserScanMessage.class,
            "{header: {frame_id: 'laser_frame'}, "
                + "angle_min: -3.14159, angle_max: 3.14159, angle_increment: 0.69813, "
                + "range_min: 0.1, range_max: 30.0, "
                + "ranges: [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0], "
                + "intensities: [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0]}",
            10, 2,
            msg -> {
              LaserScanMessage scan = (LaserScanMessage) msg;
              assertEquals(10, scan.ranges.length,
                  "All 10 float ranges must survive the bridge");
              assertEquals(5.0f, scan.ranges[4], 0.01f, "Range value at index 4 must be preserved");
            })
    );
  }

  // ---------------------------------------------------------------------------
  // Container lifecycle
  // ---------------------------------------------------------------------------

  @BeforeAll
  static void beforeAll() throws Exception {
    skipReason = evaluatePrerequisites();
    if (skipReason != null) {
      return;
    }

    Path extensionJarPath = findExtensionJar();
    if (extensionJarPath == null) {
      skipReason = "Extension jar not found in target/; run mvn package or mvn verify first";
      return;
    }

    List<Path> runtimeLibs = listRuntimeLibs(Path.of("target", "e2e-libs"));
    if (runtimeLibs.isEmpty()) {
      skipReason = "Runtime libs not found in target/e2e-libs; run via failsafe (mvn verify)";
      return;
    }

    String runId = Instant.now().toString().replace(":", "-");
    runArtifactPath = Path.of("target", "test-artifacts", "subscriber-backpressure-it", runId);
    Files.createDirectories(runArtifactPath);

    Path bridgeConfigPath = runArtifactPath.resolve("NetworkConnectionManager.yaml");
    Files.writeString(bridgeConfigPath, buildBridgeConfig());

    Path mapsJacocoAgentPath = findJacocoAgentJar();

    e2eNetwork = Network.builder()
        .driver("bridge")
        .createNetworkCmdModifier(cmd -> cmd.withAttachable(true))
        .build();

    mapsContainer = new GenericContainer<>(DockerImageName.parse(resolvedMapsImage))
        .withNetwork(e2eNetwork)
        .withNetworkAliases("maps-bp")
        .withExposedPorts(MQTT_PORT, MAPS_JACOCO_PORT)
        .withCopyFileToContainer(
            MountableFile.forHostPath(bridgeConfigPath),
            "/opt/maps/conf/NetworkConnectionManager.yaml")
        .withEnv("MAPS_DATA", "/opt/maps_data")
        .withStartupTimeout(Duration.ofSeconds(90));

    mapsContainer.withCopyFileToContainer(
        MountableFile.forHostPath(extensionJarPath),
        "/opt/maps/lib/" + extensionJarPath.getFileName());
    for (Path runtimeLib : runtimeLibs) {
      mapsContainer.withCopyFileToContainer(
          MountableFile.forHostPath(runtimeLib),
          "/opt/maps/lib/" + runtimeLib.getFileName());
    }
    if (mapsJacocoAgentPath != null) {
      mapsContainer
          .withCopyFileToContainer(
              MountableFile.forHostPath(mapsJacocoAgentPath),
              "/opt/maps/lib/jacocoagent.jar")
          .withEnv("JAVA_TOOL_OPTIONS",
              "-javaagent:/opt/maps/lib/jacocoagent.jar=output=tcpserver,address=*,port="
                  + MAPS_JACOCO_PORT
                  + ",dumponexit=false,includes=io.mapsmessaging.network.protocol.impl.ros.*");
    }

    mapsContainer.start();
    mqttUrl = "tcp://" + mapsContainer.getHost() + ":" + mapsContainer.getMappedPort(MQTT_PORT);
    waitForMqttReady(Duration.ofSeconds(30), mqttUrl);

    rosContainer = new GenericContainer<>(DockerImageName.parse(resolvedRosImage))
        .withNetwork(e2eNetwork)
        .withNetworkAliases("ros-bp")
        .withEnv("ROS_DOMAIN_ID", Integer.toString(ROS_DOMAIN_ID))
        .withEnv("RMW_IMPLEMENTATION", rmwImplementation)
        .withEnv("ROS_AUTOMATIC_DISCOVERY_RANGE", automaticDiscoveryRange)
        .withEnv("ROS_STATIC_PEERS", staticPeers)
        .withEnv("FASTDDS_BUILTIN_TRANSPORTS", "UDPv4")
        .withStartupTimeout(Duration.ofSeconds(90))
        .withCommand("bash", "-lc", "source /opt/ros/jazzy/setup.bash && sleep infinity");

    rosContainer.start();
    ensureRosInterfacesAvailable();

    Files.writeString(runArtifactPath.resolve("runtime.txt"),
        "maps.image=" + resolvedMapsImage + System.lineSeparator()
            + "ros.image=" + resolvedRosImage + System.lineSeparator()
            + "rmw.implementation=" + rmwImplementation + System.lineSeparator()
            + "ros.domain.id=" + ROS_DOMAIN_ID + System.lineSeparator());
  }

  @AfterAll
  static void afterAll() {
    dumpMapsContainerCoverage();
    if (rosContainer != null) {
      rosContainer.stop();
    }
    if (mapsContainer != null) {
      mapsContainer.stop();
    }
    if (e2eNetwork != null) {
      e2eNetwork.close();
    }
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  static Stream<ThroughputCase> cases() {
    return CASES.stream();
  }

  /**
   * Publishes {@code tc.messageCount()} messages at {@code tc.rateHz()} Hz via the ROS container
   * and asserts that Maps receives all of them over MQTT.
   *
   * <p>Key assertions:
   * <ul>
   *   <li>All N messages arrive (not just the first — the bug symptom).
   *   <li>The last received payload round-trips correctly through the bridge.
   * </ul>
   */
  @ParameterizedTest(name = "backpressure ROS2→MAPS {0}")
  @MethodSource("cases")
  @Timeout(value = 180, unit = TimeUnit.SECONDS)
  void shouldReceiveAllMessagesFromRos(ThroughputCase tc) throws Exception {
    Assumptions.assumeTrue(skipReason == null, skipReason);

    Path artifactPath = runArtifactPath.resolve(tc.id());
    Files.createDirectories(artifactPath);

    List<byte[]> received = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch latch = new CountDownLatch(tc.messageCount());

    MqttClient subscriber = null;
    try {
      subscriber = new MqttClient(
          mqttUrl, "bp-it-" + tc.id() + "-" + UUID.randomUUID());
      subscriber.setCallback(new MqttCallback() {
        @Override public void connectionLost(Throwable cause) {}
        @Override public void messageArrived(String topic, MqttMessage message) {
          received.add(message.getPayload());
          latch.countDown();
        }
        @Override public void deliveryComplete(IMqttDeliveryToken token) {}
      });
      subscriber.connect(mqttOptions());
      subscriber.subscribe(tc.mapsTopic());

      // Wait for Maps to register a subscription on the ROS topic before publishing.
      waitForRosSubscriptionCount(tc.rosTopic(), 1, Duration.ofSeconds(90));

      // Publish N messages at R Hz via the ROS container.
      // ros2 topic pub --times N --rate R blocks for approximately N/R seconds.
      String pubCmd = "source /opt/ros/jazzy/setup.bash && ros2 topic pub"
          + " --times " + tc.messageCount()
          + " --rate " + tc.rateHz()
          + " " + tc.rosTopic()
          + " " + tc.rosCliType()
          + " " + shellQuote(tc.rosCliYaml());

      Container.ExecResult pub = rosContainer.execInContainer("bash", "-lc", pubCmd);
      Files.writeString(artifactPath.resolve("ros_pub.log"),
          pub.getStdout() + "\n" + pub.getStderr());
      assertEquals(0, pub.getExitCode(),
          "ros2 topic pub failed for " + tc.id()
              + ". stderr=" + pub.getStderr());

      // Allow a short grace period for in-flight messages to arrive after the publisher exits.
      long gracePeriodSeconds = Math.max(5, tc.messageCount() / tc.rateHz());
      boolean allArrived = latch.await(gracePeriodSeconds + 15, TimeUnit.SECONDS);

      Files.writeString(artifactPath.resolve("summary.txt"),
          "id=" + tc.id() + System.lineSeparator()
              + "expected=" + tc.messageCount() + System.lineSeparator()
              + "received=" + received.size() + System.lineSeparator()
              + "rate_hz=" + tc.rateHz() + System.lineSeparator()
              + "ros.topic=" + tc.rosTopic() + System.lineSeparator()
              + "maps.topic=" + tc.mapsTopic() + System.lineSeparator());

      assertTrue(allArrived,
          "Only " + received.size() + "/" + tc.messageCount()
              + " messages arrived for case '" + tc.id() + "'. "
              + "If count==1 the backpressure fix (getSubscription().request(1)) is not working. "
              + "Maps logs: " + lastLines(mapsContainer.getLogs(), 30));

      assertEquals(tc.messageCount(), received.size(),
          "No messages may be dropped for case '" + tc.id() + "'");

      // Verify payload content of the last received message.
      byte[] lastPayload = received.get(received.size() - 1);
      Message decoded = SERIALIZER.read(lastPayload, tc.messageClass());
      tc.payloadAssertion().accept(decoded);

    } catch (Throwable t) {
      try {
        Files.writeString(artifactPath.resolve("failure.txt"), t.toString());
        Files.writeString(artifactPath.resolve("maps.log"), mapsContainer == null ? "" : mapsContainer.getLogs());
        Files.writeString(artifactPath.resolve("ros.log"), rosContainer == null ? "" : rosContainer.getLogs());
        writeRosTopicInfo(artifactPath.resolve("ros_topic_info.txt"), tc.rosTopic());
      } catch (Exception ignored) {}
      throw t;
    } finally {
      safeDisconnect(subscriber);
    }
  }

  // ---------------------------------------------------------------------------
  // Bridge configuration
  // ---------------------------------------------------------------------------

  private static String buildBridgeConfig() {
    StringBuilder sb = new StringBuilder();
    sb.append("---\n")
        .append("NetworkConnectionManager:\n")
        .append("  global:\n")
        .append("    selector_pool_size: 2\n")
        .append("    receiveBufferSize: 128000\n")
        .append("    sendBufferSize: 128000\n")
        .append("    timeout: 60000\n")
        .append("    readDelayOnFragmentation: 100\n")
        .append("    enableReadDelayOnFragmentation: true\n")
        .append("    serverReadBufferSize: 10240\n")
        .append("    serverWriteBufferSize: 10240\n")
        .append("    selectorThreadCount: 1\n")
        .append("  data:\n")
        .append("    - name: ros_bp_e2e\n")
        .append("      url: \"ros://localhost\"\n")
        .append("      remote:\n")
        .append("        username: anonymous\n")
        .append("        password: \"\"\n")
        .append("        sessionId: maps-ros2-backpressure-it\n")
        .append("      protocol: ros\n")
        .append("      plugin: true\n")
        .append("      links:\n");
    for (ThroughputCase tc : CASES) {
      sb.append("        - direction: pull\n")
          .append("          local_namespace: \"").append(tc.mapsTopic()).append("\"\n")
          .append("          remote_namespace: \"").append(tc.rosTopic()).append("\"\n")
          .append("          include_schema: true\n")
          .append("          linkProperties:\n")
          .append("            ros_topic: \"").append(tc.rosTopic()).append("\"\n")
          .append("            ros_version: \"2\"\n")
          .append("            ros_package: \"").append(tc.rosPackage()).append("\"\n")
          .append("            ros_type: \"").append(tc.rosType()).append("\"\n");
    }
    sb.append("      config:\n")
        .append("        rosVersion: 2\n")
        .append("        schema_mode: strict\n")
        .append("        ros_domain_id: ").append(ROS_DOMAIN_ID).append("\n")
        .append("        network_interface: eth0\n")
        .append("        links:\n");
    for (ThroughputCase tc : CASES) {
      sb.append("          - direction: pull\n")
          .append("            local_namespace: \"").append(tc.mapsTopic()).append("\"\n")
          .append("            remote_namespace: \"").append(tc.rosTopic()).append("\"\n")
          .append("            include_schema: true\n")
          .append("            ros_topic: \"").append(tc.rosTopic()).append("\"\n")
          .append("            ros_version: \"2\"\n")
          .append("            ros_package: \"").append(tc.rosPackage()).append("\"\n")
          .append("            ros_type: \"").append(tc.rosType()).append("\"\n");
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------------------
  // Container / ROS helpers (mirrors MapsRos2MessageTypesIT private statics)
  // ---------------------------------------------------------------------------

  private static String evaluatePrerequisites() {
    try {
      resolvedMapsImage = resolveMapsImageName();
      resolvedRosImage = resolveRosImage();
    } catch (IllegalStateException e) {
      return e.getMessage();
    }

    try {
      configureTestcontainersSocket();
      DockerClientFactory.instance().client().pingCmd().exec();
    } catch (Throwable t) {
      return "Testcontainers Docker environment unavailable: " + t.getMessage();
    }

    rmwImplementation = System.getProperty(
        "maps.e2e.rmw.implementation",
        System.getenv().getOrDefault("MAPS_E2E_RMW_IMPLEMENTATION", DEFAULT_RMW_IMPLEMENTATION));
    automaticDiscoveryRange = System.getProperty(
        "maps.e2e.ros.automatic.discovery.range",
        System.getenv().getOrDefault(
            "MAPS_E2E_ROS_AUTOMATIC_DISCOVERY_RANGE", DEFAULT_AUTOMATIC_DISCOVERY_RANGE));
    staticPeers = System.getProperty(
        "maps.e2e.ros.static.peers",
        System.getenv().getOrDefault("MAPS_E2E_ROS_STATIC_PEERS", DEFAULT_STATIC_PEERS));

    return null;
  }

  private static void ensureRosInterfacesAvailable() throws Exception {
    String installCmd = "source /opt/ros/jazzy/setup.bash && "
        + "if ! ros2 interface show std_msgs/msg/String >/dev/null 2>&1 "
        + "|| ! ros2 interface show sensor_msgs/msg/LaserScan >/dev/null 2>&1; then "
        + "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y "
        + "ros-jazzy-common-interfaces; "
        + "fi";
    Container.ExecResult result = rosContainer.execInContainer("bash", "-lc", installCmd);
    if (runArtifactPath != null) {
      Files.writeString(runArtifactPath.resolve("ros_interface_setup.log"),
          result.getStdout() + "\n" + result.getStderr());
    }
    assertEquals(0, result.getExitCode(),
        "ROS interface setup failed. stderr=" + result.getStderr());
  }

  private static void waitForRosSubscriptionCount(
      String topic, int minimum, Duration timeout) throws Exception {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      Container.ExecResult result = rosContainer.execInContainer(
          "bash", "-lc",
          "source /opt/ros/jazzy/setup.bash && ros2 topic info " + topic + " 2>/dev/null || true");
      if (parseCount(result.getStdout(), "Subscription count:") >= minimum) {
        return;
      }
      Thread.sleep(500L);
    }
    throw new IOException(
        "Timed out waiting for Subscription count >= " + minimum + " on " + topic);
  }

  private static int parseCount(String output, String prefix) {
    for (String line : output.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.startsWith(prefix)) {
        try {
          return Integer.parseInt(trimmed.substring(prefix.length()).trim());
        } catch (NumberFormatException ignored) {
        }
      }
    }
    return 0;
  }

  private static void waitForMqttReady(Duration timeout, String url) throws Exception {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      MqttClient client = null;
      try {
        client = new MqttClient(url, "bp-it-probe-" + UUID.randomUUID());
        client.connect(mqttOptions());
        client.disconnect();
        client.close();
        return;
      } catch (MqttException ignored) {
        safeDisconnect(client);
        Thread.sleep(500L);
      }
    }
    throw new IOException("MAPS MQTT did not become ready at " + url);
  }

  private static void writeRosTopicInfo(Path path, String topic) {
    if (rosContainer == null) return;
    try {
      Files.writeString(path,
          rosContainer.execInContainer(
                  "bash", "-lc",
                  "source /opt/ros/jazzy/setup.bash && ros2 topic info " + topic + " || true")
              .getStdout());
    } catch (Exception ignored) {}
  }

  private static void safeDisconnect(MqttClient client) {
    if (client == null) return;
    try {
      if (client.isConnected()) client.disconnect();
    } catch (Exception ignored) {}
    try {
      client.close();
    } catch (Exception ignored) {}
  }

  private static MqttConnectOptions mqttOptions() {
    MqttConnectOptions opts = new MqttConnectOptions();
    opts.setAutomaticReconnect(false);
    opts.setCleanSession(true);
    opts.setConnectionTimeout(5);
    return opts;
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }

  private static String lastLines(String text, int n) {
    if (text == null || text.isEmpty()) return "<empty>";
    String[] lines = text.split("\\R");
    int start = Math.max(0, lines.length - n);
    StringBuilder sb = new StringBuilder();
    for (int i = start; i < lines.length; i++) {
      sb.append(lines[i]).append('\n');
    }
    return sb.toString();
  }

  private static String resolveRosImage() {
    String explicit = System.getProperty("maps.e2e.ros.image", System.getenv("MAPS_E2E_ROS_IMAGE"));
    if (explicit != null && !explicit.isBlank()) return explicit.trim();
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    return (arch.contains("aarch64") || arch.contains("arm64"))
        ? DEFAULT_ROS_IMAGE_ARM64 : DEFAULT_ROS_IMAGE_AMD64;
  }

  private static String resolveMapsImageName() {
    String explicit = System.getProperty("maps.e2e.maps.image", System.getenv("MAPS_E2E_MAPS_IMAGE"));
    if (explicit != null && !explicit.isBlank()) return explicit.trim();

    String version = System.getProperty("maps.e2e.maps.version", System.getenv("MAPS_E2E_MAPS_VERSION"));
    if (version == null || version.isBlank()) {
      throw new IllegalStateException(
          "MAPS E2E image version not configured. Set maps.e2e.maps.version, MAPS_E2E_MAPS_VERSION, "
              + "or maps.e2e.maps.image/MAPS_E2E_MAPS_IMAGE.");
    }
    String normalized = version.toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    return (arch.contains("aarch64") || arch.contains("arm64"))
        ? "mapsmessaging/server_daemon_arm_" + normalized
        : "mapsmessaging/server_daemon_" + normalized;
  }

  private static void configureTestcontainersSocket() {
    if (System.getProperty("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE") != null
        || System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE") != null
        || System.getProperty("DOCKER_HOST") != null
        || System.getenv("DOCKER_HOST") != null) {
      return;
    }
    String home = System.getProperty("user.home", "");
    for (String candidate : new String[]{
        "/var/run/docker.sock",
        home + "/.rd/docker.sock",
        home + "/.docker/run/docker.sock"}) {
      if (!candidate.isBlank() && Files.exists(Path.of(candidate))) {
        System.setProperty("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "unix:///" + candidate);
        return;
      }
    }
  }

  private static Path findExtensionJar() throws IOException {
    Path target = Path.of("target");
    if (!Files.isDirectory(target)) return null;
    try (var stream = Files.list(target)) {
      return stream
          .filter(p -> p.getFileName().toString().startsWith("ros-extension-"))
          .filter(p -> p.getFileName().toString().endsWith(".jar"))
          .filter(p -> !p.getFileName().toString().contains("-sources"))
          .filter(p -> !p.getFileName().toString().contains("-javadoc"))
          .sorted(Comparator.comparing(Path::toString))
          .findFirst()
          .orElse(null);
    }
  }

  private static List<Path> listRuntimeLibs(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) return List.of();
    List<Path> libs = new ArrayList<>();
    try (var stream = Files.list(directory)) {
      stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
          .sorted(Comparator.comparing(Path::toString))
          .forEach(libs::add);
    }
    return libs;
  }

  private static Path findJacocoAgentJar() throws IOException {
    String explicit = System.getProperty("maps.e2e.jacoco.agent", System.getenv("MAPS_E2E_JACOCO_AGENT"));
    if (explicit != null && !explicit.isBlank()) {
      Path p = Path.of(explicit.trim());
      return Files.exists(p) ? p : null;
    }
    Path agentDir = Path.of(System.getProperty("user.home"), ".m2", "repository",
        "org", "jacoco", "org.jacoco.agent");
    if (!Files.isDirectory(agentDir)) return null;
    try (var versions = Files.list(agentDir)) {
      return versions.filter(Files::isDirectory)
          .flatMap(version -> {
            try {
              return Files.list(version)
                  .filter(p -> p.getFileName().toString().endsWith("-runtime.jar"));
            } catch (IOException e) {
              return Stream.<Path>empty();
            }
          })
          .sorted(Comparator.comparing(Path::toString).reversed())
          .findFirst()
          .orElse(null);
    }
  }

  private static void dumpMapsContainerCoverage() {
    if (mapsContainer == null || !mapsContainer.isRunning()) return;
    try {
      Path output = Path.of("target", "jacoco-maps.exec");
      Files.createDirectories(output.getParent());
      ExecDumpClient client = new ExecDumpClient();
      client.setDump(true);
      client.setReset(false);
      client.setRetryCount(10);
      client.setRetryDelay(500L);
      ExecFileLoader loader = client.dump(
          mapsContainer.getHost(), mapsContainer.getMappedPort(MAPS_JACOCO_PORT));
      loader.save(output.toFile(), false);
      if (runArtifactPath != null) {
        Files.copy(output, runArtifactPath.resolve("jacoco-maps.exec"),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (Exception ignored) {
      // Coverage dump is best-effort; don't fail the suite for it
    }
  }

  // ---------------------------------------------------------------------------
  // ThroughputCase record
  // ---------------------------------------------------------------------------

  /**
   * Describes a single backpressure/throughput scenario: what to publish, how fast, and how to
   * validate the payload after it has crossed the ROS2 → Maps bridge.
   */
  record ThroughputCase(
      String id,
      String rosTopic,
      String mapsTopic,
      String rosPackage,
      String rosType,
      Class<? extends Message> messageClass,
      String rosCliYaml,
      int messageCount,
      int rateHz,
      Consumer<Message> payloadAssertion) {

    /** CLI type string for {@code ros2 topic pub}, e.g. {@code std_msgs/msg/String}. */
    String rosCliType() {
      if (rosPackage.contains("/action")) {
        return rosPackage + "/" + rosType;
      }
      return rosPackage + "/msg/" + rosType;
    }

    @Override
    public String toString() {
      return id + " (" + messageCount + "×" + rosType + "@" + rateHz + "Hz)";
    }
  }
}
