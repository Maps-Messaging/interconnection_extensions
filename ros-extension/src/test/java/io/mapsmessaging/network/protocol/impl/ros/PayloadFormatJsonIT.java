package io.mapsmessaging.network.protocol.impl.ros;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test verifying that {@code payload_format: json} in the bridge config causes the
 * ROS2 → Maps bridge to forward message payloads as UTF-8 JSON rather than CDR binary.
 *
 * <p>The test starts a Maps container configured with {@code payload_format: json} and a ROS2
 * container, publishes a {@code std_msgs/String} message from the ROS side, and asserts that the
 * payload received over MQTT is valid JSON containing the expected {@code data} field.
 */
class PayloadFormatJsonIT {

  private static final String DEFAULT_ROS_IMAGE_AMD64 = "osrf/ros:jazzy-desktop";
  private static final String DEFAULT_ROS_IMAGE_ARM64 = "arm64v8/ros:jazzy-perception";
  private static final String DEFAULT_RMW_IMPLEMENTATION = "rmw_fastrtps_cpp";
  private static final String DEFAULT_AUTOMATIC_DISCOVERY_RANGE = "SUBNET";
  private static final String DEFAULT_STATIC_PEERS = "ros-json;maps-json";

  // Domain 27 avoids collisions with SubscriberBackpressureIT (26) and MapsRos2MessageTypesIT (25)
  private static final int ROS_DOMAIN_ID = 27;
  private static final int MQTT_PORT = 1883;
  private static final int MAPS_JACOCO_PORT = 6300;

  private static final String ROS_TOPIC = "/ros2_json_fmt_string";
  private static final String MAPS_TOPIC = "/maps/ros/json_fmt/string";
  private static final String EXPECTED_DATA = "hello-json";

  private static final Gson GSON = new Gson();

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

  @BeforeAll
  static void beforeAll() throws Exception {
    skipReason = evaluatePrerequisites();
    if (skipReason != null) {
      return;
    }

    Path extensionJarPath = findExtensionJar();
    if (extensionJarPath == null) {
      skipReason = "Extension jar not found in target/; run mvn package first";
      return;
    }

    List<Path> runtimeLibs = listRuntimeLibs(Path.of("target", "e2e-libs"));
    if (runtimeLibs.isEmpty()) {
      skipReason = "Runtime libs not found in target/e2e-libs; run via failsafe (mvn verify)";
      return;
    }

    String runId = Instant.now().toString().replace(":", "-");
    runArtifactPath = Path.of("target", "test-artifacts", "payload-format-json-it", runId);
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
        .withNetworkAliases("maps-json")
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
        .withNetworkAliases("ros-json")
        .withEnv("ROS_DOMAIN_ID", Integer.toString(ROS_DOMAIN_ID))
        .withEnv("RMW_IMPLEMENTATION", rmwImplementation)
        .withEnv("ROS_AUTOMATIC_DISCOVERY_RANGE", automaticDiscoveryRange)
        .withEnv("ROS_STATIC_PEERS", staticPeers)
        .withEnv("FASTDDS_BUILTIN_TRANSPORTS", "UDPv4")
        .withStartupTimeout(Duration.ofSeconds(90))
        .withCommand("bash", "-lc", "source /opt/ros/jazzy/setup.bash && sleep infinity");

    rosContainer.start();
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

  @Test
  @Timeout(value = 180, unit = TimeUnit.SECONDS)
  void shouldReceiveJsonPayloadFromRos() throws Exception {
    Assumptions.assumeTrue(skipReason == null, skipReason);

    Path artifactPath = runArtifactPath.resolve("json-string");
    Files.createDirectories(artifactPath);

    List<byte[]> received = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(1);

    MqttClient subscriber = null;
    try {
      subscriber = new MqttClient(mqttUrl, "json-fmt-it-" + UUID.randomUUID());
      subscriber.setCallback(new MqttCallback() {
        @Override public void connectionLost(Throwable cause) {}
        @Override public void messageArrived(String topic, MqttMessage message) {
          received.add(message.getPayload());
          latch.countDown();
        }
        @Override public void deliveryComplete(IMqttDeliveryToken token) {}
      });
      subscriber.connect(mqttOptions());
      subscriber.subscribe(MAPS_TOPIC);

      waitForRosSubscriptionCount(ROS_TOPIC, 1, Duration.ofSeconds(90));

      String pubCmd = "source /opt/ros/jazzy/setup.bash && ros2 topic pub"
          + " --times 1"
          + " --rate 1"
          + " " + ROS_TOPIC
          + " std_msgs/msg/String"
          + " '{data: \"" + EXPECTED_DATA + "\"}'";

      Container.ExecResult pub = rosContainer.execInContainer("bash", "-lc", pubCmd);
      Files.writeString(artifactPath.resolve("ros_pub.log"),
          pub.getStdout() + "\n" + pub.getStderr());
      assertEquals(0, pub.getExitCode(),
          "ros2 topic pub failed. stderr=" + pub.getStderr());

      boolean arrived = latch.await(20, TimeUnit.SECONDS);

      Files.writeString(artifactPath.resolve("summary.txt"),
          "expected=1\nreceived=" + received.size() + "\n");

      assertTrue(arrived,
          "No message arrived within timeout. Maps logs: "
              + lastLines(mapsContainer.getLogs(), 30));

      // Verify the payload is valid JSON — not CDR binary
      byte[] payload = received.get(0);
      String json = new String(payload, StandardCharsets.UTF_8);
      Files.writeString(artifactPath.resolve("payload.json"), json);

      JsonObject obj;
      try {
        obj = GSON.fromJson(json, JsonObject.class);
      } catch (JsonSyntaxException e) {
        throw new AssertionError(
            "Payload is not valid JSON — payload_format: json was not applied. "
                + "Raw payload (hex): " + toHex(payload), e);
      }
      assertNotNull(obj, "Parsed JSON object must not be null");
      assertTrue(obj.has("data"),
          "JSON payload must contain 'data' field. Got: " + json);
      assertEquals(EXPECTED_DATA, obj.get("data").getAsString(),
          "JSON 'data' field must match published value");

    } catch (Throwable t) {
      try {
        Files.writeString(artifactPath.resolve("failure.txt"), t.toString());
        Files.writeString(artifactPath.resolve("maps.log"),
            mapsContainer == null ? "" : mapsContainer.getLogs());
        Files.writeString(artifactPath.resolve("ros.log"),
            rosContainer == null ? "" : rosContainer.getLogs());
      } catch (Exception ignored) {}
      throw t;
    } finally {
      safeDisconnect(subscriber);
    }
  }

  private static String buildBridgeConfig() {
    return "---\n"
        + "NetworkConnectionManager:\n"
        + "  global:\n"
        + "    selector_pool_size: 2\n"
        + "    receiveBufferSize: 128000\n"
        + "    sendBufferSize: 128000\n"
        + "    timeout: 60000\n"
        + "    readDelayOnFragmentation: 100\n"
        + "    enableReadDelayOnFragmentation: true\n"
        + "    serverReadBufferSize: 10240\n"
        + "    serverWriteBufferSize: 10240\n"
        + "    selectorThreadCount: 1\n"
        + "  data:\n"
        + "    - name: ros_json_fmt_e2e\n"
        + "      url: \"ros://localhost\"\n"
        + "      remote:\n"
        + "        username: anonymous\n"
        + "        password: \"\"\n"
        + "        sessionId: maps-ros2-json-fmt-it\n"
        + "      protocol: ros\n"
        + "      plugin: true\n"
        + "      links:\n"
        + "        - direction: pull\n"
        + "          local_namespace: \"" + MAPS_TOPIC + "\"\n"
        + "          remote_namespace: \"" + ROS_TOPIC + "\"\n"
        + "          include_schema: true\n"
        + "          linkProperties:\n"
        + "            ros_topic: \"" + ROS_TOPIC + "\"\n"
        + "            ros_version: \"2\"\n"
        + "            ros_package: \"std_msgs\"\n"
        + "            ros_type: \"String\"\n"
        + "      config:\n"
        + "        rosVersion: 2\n"
        + "        schema_mode: strict\n"
        + "        payload_format: json\n"
        + "        ros_domain_id: " + ROS_DOMAIN_ID + "\n"
        + "        network_interface: eth0\n"
        + "        links:\n"
          + "          - direction: pull\n"
          + "            local_namespace: \"" + MAPS_TOPIC + "\"\n"
          + "            remote_namespace: \"" + ROS_TOPIC + "\"\n"
          + "            include_schema: true\n"
        + "            ros_topic: \"" + ROS_TOPIC + "\"\n"
        + "            ros_version: \"2\"\n"
        + "            ros_package: \"std_msgs\"\n"
        + "            ros_type: \"String\"\n";
  }

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
        } catch (NumberFormatException ignored) {}
      }
    }
    return 0;
  }

  private static void waitForMqttReady(Duration timeout, String url) throws Exception {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      MqttClient client = null;
      try {
        client = new MqttClient(url, "json-it-probe-" + UUID.randomUUID());
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

  private static void safeDisconnect(MqttClient client) {
    if (client == null) return;
    try { if (client.isConnected()) client.disconnect(); } catch (Exception ignored) {}
    try { client.close(); } catch (Exception ignored) {}
  }

  private static MqttConnectOptions mqttOptions() {
    MqttConnectOptions opts = new MqttConnectOptions();
    opts.setAutomaticReconnect(false);
    opts.setCleanSession(true);
    opts.setConnectionTimeout(5);
    return opts;
  }

  private static String toHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder(bytes.length * 3);
    for (byte b : bytes) {
      sb.append(String.format("%02x ", b));
    }
    return sb.toString().trim();
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
      Path output = Path.of("target", "jacoco-maps-json-fmt.exec");
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
    } catch (Exception ignored) {}
  }
}
