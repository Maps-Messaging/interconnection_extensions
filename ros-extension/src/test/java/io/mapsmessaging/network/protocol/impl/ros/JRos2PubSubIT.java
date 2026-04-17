package io.mapsmessaging.network.protocol.impl.ros;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Baseline ROS2 container interoperability test aligned to ROS Jazzy docs:
 * "Run 2 nodes in single or separate docker containers".
 *
 * It starts:
 * - talker container:   ros2 run demo_nodes_cpp talker
 * - listener container: ros2 run demo_nodes_cpp listener
 *
 * and asserts listener output contains "I heard".
 */
class JRos2PubSubIT {

  // osrf ROS images used in docs are x86_64; arm64 uses a ROS Foundation multi-arch
  // image family to avoid qemu-emulated amd64 runtime on Apple Silicon.
  private static final String DEFAULT_ROS_IMAGE_AMD64 = "osrf/ros:jazzy-desktop";
  private static final String DEFAULT_ROS_IMAGE_ARM64 = "arm64v8/ros:jazzy-perception";
  private static final String DEFAULT_RMW_IMPLEMENTATION = "rmw_fastrtps_cpp";
  private static final String DEFAULT_AUTOMATIC_DISCOVERY_RANGE = "SUBNET";
  private static final String DEFAULT_STATIC_PEERS = "ros2-talker;ros2-listener";
  private static final String DEMO_PACKAGE = "demo_nodes_cpp";
  private static final Duration CONTAINER_STARTUP_TIMEOUT = Duration.ofSeconds(90);
  private static final int ROS_DOMAIN_ID = 25;
  private static final int DEFAULT_MIN_HEARD_MESSAGES = 10;
  private static final int LISTENER_OUTPUT_TIMEOUT_SECONDS = 120;

  private static String skipReason;
  private static String rosImage;
  private static String rmwImplementation;
  private static String automaticDiscoveryRange;
  private static String staticPeers;
  private static int minimumHeardMessages;

  @BeforeAll
  static void beforeAll() {
    skipReason = checkDockerAvailable();
    rosImage = resolveRosImage();
    rmwImplementation = System.getProperty(
        "maps.e2e.rmw.implementation",
        System.getenv().getOrDefault("MAPS_E2E_RMW_IMPLEMENTATION", DEFAULT_RMW_IMPLEMENTATION));
    automaticDiscoveryRange = System.getProperty(
        "maps.e2e.ros.automatic.discovery.range",
        System.getenv().getOrDefault(
            "MAPS_E2E_ROS_AUTOMATIC_DISCOVERY_RANGE",
            DEFAULT_AUTOMATIC_DISCOVERY_RANGE));
    staticPeers = System.getProperty(
        "maps.e2e.ros.static.peers",
        System.getenv().getOrDefault("MAPS_E2E_ROS_STATIC_PEERS", DEFAULT_STATIC_PEERS));
    minimumHeardMessages = parseIntConfig(
        "maps.e2e.ros.min.heard.messages",
        System.getenv("MAPS_E2E_ROS_MIN_HEARD_MESSAGES"),
        DEFAULT_MIN_HEARD_MESSAGES);
  }

  @Test
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void shouldRunTalkerAndListenerInSeparateContainers() throws Exception {
    Assumptions.assumeTrue(skipReason == null, skipReason);

    Network network = Network.builder()
        .driver("bridge")
        .createNetworkCmdModifier(cmd -> cmd.withAttachable(true))
        .build();
    GenericContainer<?> talker = null;
    GenericContainer<?> listener = null;
    Path artifactDir = null;
    try {
      talker = new GenericContainer<>(DockerImageName.parse(rosImage))
          .withNetwork(network)
          .withNetworkAliases("ros2-talker")
          .withEnv("ROS_DOMAIN_ID", Integer.toString(ROS_DOMAIN_ID))
          .withEnv("RMW_IMPLEMENTATION", rmwImplementation)
          .withEnv("ROS_AUTOMATIC_DISCOVERY_RANGE", automaticDiscoveryRange)
          .withEnv("ROS_STATIC_PEERS", staticPeers)
          // FastDDS shared-memory transport is unreliable across Docker containers.
          // Force UDP so cross-container pub/sub behaves consistently on Docker Desktop.
          .withEnv("FASTDDS_BUILTIN_TRANSPORTS", "UDPv4")
          .withCommand("bash", "-lc", buildNodeCommand("talker"))
          .withStartupTimeout(CONTAINER_STARTUP_TIMEOUT);
      talker.start();

      listener = new GenericContainer<>(DockerImageName.parse(rosImage))
          .withNetwork(network)
          .withNetworkAliases("ros2-listener")
          .withEnv("ROS_DOMAIN_ID", Integer.toString(ROS_DOMAIN_ID))
          .withEnv("RMW_IMPLEMENTATION", rmwImplementation)
          .withEnv("ROS_AUTOMATIC_DISCOVERY_RANGE", automaticDiscoveryRange)
          .withEnv("ROS_STATIC_PEERS", staticPeers)
          .withEnv("FASTDDS_BUILTIN_TRANSPORTS", "UDPv4")
          .withCommand("bash", "-lc", buildNodeCommand("listener"))
          .withStartupTimeout(CONTAINER_STARTUP_TIMEOUT);
      listener.start();

      String talkerRuntime = inspectRosRuntime(talker);
      String listenerRuntime = inspectRosRuntime(listener);
      String talkerNetwork = containerNetworkDetails(talker);
      String listenerNetwork = containerNetworkDetails(listener);
      boolean talkerRmwOk = talkerRuntime.contains("RMW_IMPLEMENTATION=" + rmwImplementation);
      boolean listenerRmwOk = listenerRuntime.contains("RMW_IMPLEMENTATION=" + rmwImplementation);

      int heardCount = waitForListenerOutput(listener, "I heard", LISTENER_OUTPUT_TIMEOUT_SECONDS);
      boolean heard = heardCount >= minimumHeardMessages;
      String talkerLogs = talker.getLogs();
      String listenerLogs = listener.getLogs();
      String talkerGraph = inspectRosGraph(talker);
      String listenerGraph = inspectRosGraph(listener);

      artifactDir = writeArtifacts(
          talkerRuntime,
          listenerRuntime,
          talkerNetwork,
          listenerNetwork,
          talkerGraph,
          listenerGraph,
          talkerLogs,
          listenerLogs,
          heardCount,
          talkerRmwOk,
          listenerRmwOk);

      assertTrue(talkerRmwOk,
          "Talker did not pick requested RMW implementation. runtime="
              + talkerRuntime
              + " artifacts="
              + artifactDir);
      assertTrue(listenerRmwOk,
          "Listener did not pick requested RMW implementation. runtime="
              + listenerRuntime
              + " artifacts="
              + artifactDir);
      assertTrue(heard,
          "Listener did not receive at least " + minimumHeardMessages
              + " messages within timeout. image="
              + rosImage
              + " rosDomainId="
              + ROS_DOMAIN_ID
              + " rmwImplementation="
              + rmwImplementation
              + " automaticDiscoveryRange="
              + automaticDiscoveryRange
              + " staticPeers="
              + staticPeers
              + " talkerContainer="
              + talkerNetwork
              + " listenerContainer="
              + listenerNetwork
              + " talkerGraph="
              + talkerGraph
              + " listenerGraph="
              + listenerGraph
              + " talkerRuntime="
              + talkerRuntime
              + " listenerRuntime="
              + listenerRuntime
              + " talkerLogs="
              + talkerLogs
              + " listenerLogs="
              + listenerLogs
              + " artifacts="
              + artifactDir);
    } finally {
      if (artifactDir == null) {
        try {
          String talkerRuntime = talker == null ? "<not-started>" : inspectRosRuntime(talker);
          String listenerRuntime = listener == null ? "<not-started>" : inspectRosRuntime(listener);
          String talkerNetwork = talker == null ? "<not-started>" : containerNetworkDetails(talker);
          String listenerNetwork = listener == null ? "<not-started>" : containerNetworkDetails(listener);
          String talkerGraph = talker == null ? "<not-started>" : inspectRosGraph(talker);
          String listenerGraph = listener == null ? "<not-started>" : inspectRosGraph(listener);
          String talkerLogs = talker == null ? "<not-started>" : talker.getLogs();
          String listenerLogs = listener == null ? "<not-started>" : listener.getLogs();
          artifactDir = writeArtifacts(
              talkerRuntime,
              listenerRuntime,
              talkerNetwork,
              listenerNetwork,
              talkerGraph,
              listenerGraph,
              talkerLogs,
              listenerLogs,
              0,
              false,
              false);
        } catch (Exception ignored) {
        }
      }
      if (listener != null) {
        listener.stop();
      }
      if (talker != null) {
        talker.stop();
      }
      network.close();
    }
  }

  private static String checkDockerAvailable() {
    try {
      configureTestcontainersSocket();
      DockerClientFactory.instance().client().pingCmd().exec();
      return null;
    } catch (Throwable t) {
      return "Docker unavailable: " + t.getMessage()
          + " ; set -DTESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=unix:///var/run/docker.sock (or your socket path)";
    }
  }

  private static void configureTestcontainersSocket() {
    if (System.getProperty("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE") != null
        || System.getenv("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE") != null
        || System.getProperty("DOCKER_HOST") != null
        || System.getenv("DOCKER_HOST") != null) {
      return;
    }

    String home = System.getProperty("user.home", "");
    String[] candidateSockets = {
        "/var/run/docker.sock",
        home + "/.rd/docker.sock",
        home + "/.docker/run/docker.sock"
    };

    for (String candidate : candidateSockets) {
      if (!candidate.isBlank() && Files.exists(Path.of(candidate))) {
        System.setProperty("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "unix:///" + candidate);
        return;
      }
    }
  }

  private static int waitForListenerOutput(
      GenericContainer<?> listener,
      String expectedMarker,
      int timeoutSeconds) throws InterruptedException {
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
    while (System.currentTimeMillis() < deadline) {
      String logs = listener.getLogs();
      int count = countMarker(logs, expectedMarker);
      if (count >= minimumHeardMessages) {
        return count;
      }
      Thread.sleep(1000L);
    }
    return countMarker(listener.getLogs(), expectedMarker);
  }

  private static int countMarker(String logs, String marker) {
    if (logs == null || logs.isEmpty()) {
      return 0;
    }
    int count = 0;
    int index = 0;
    while (true) {
      int found = logs.indexOf(marker, index);
      if (found < 0) {
        return count;
      }
      count++;
      index = found + marker.length();
    }
  }

  private static String inspectRosRuntime(GenericContainer<?> container) {
    try {
      Container.ExecResult result = container.execInContainer(
          "bash",
          "-lc",
          "echo RMW_IMPLEMENTATION=${RMW_IMPLEMENTATION:-<unset>}; "
              + "echo ROS_DOMAIN_ID=${ROS_DOMAIN_ID:-<unset>}; "
              + "echo ROS_AUTOMATIC_DISCOVERY_RANGE=${ROS_AUTOMATIC_DISCOVERY_RANGE:-<unset>}; "
              + "echo ROS_STATIC_PEERS=${ROS_STATIC_PEERS:-<unset>}; "
              + "echo FASTDDS_BUILTIN_TRANSPORTS=${FASTDDS_BUILTIN_TRANSPORTS:-<unset>}; "
              + "ip -4 -o addr show eth0 | awk '{print \"ETH0_IP=\" $4}'");
      return result.getStdout().replace('\n', ';').trim();
    } catch (Exception e) {
      return "runtime-inspection-failed:" + e.getMessage();
    }
  }

  private static String inspectRosGraph(GenericContainer<?> container) {
    try {
      Container.ExecResult result = container.execInContainer(
          "bash",
          "-lc",
          "source /opt/ros/jazzy/setup.bash; "
              + "echo '--- node list ---'; timeout 10s ros2 node list || true; "
              + "echo '--- topic list -t ---'; timeout 10s ros2 topic list -t || true; "
              + "echo '--- topic info /chatter -v ---'; timeout 10s ros2 topic info /chatter -v || true");
      return result.getStdout().replace('\n', ';').trim();
    } catch (Exception e) {
      return "graph-inspection-failed:" + e.getMessage();
    }
  }

  private static String containerNetworkDetails(GenericContainer<?> container) {
    try {
      Map<String, com.github.dockerjava.api.model.ContainerNetwork> networks =
          container.getContainerInfo().getNetworkSettings().getNetworks();
      StringBuilder sb = new StringBuilder();
      for (Map.Entry<String, com.github.dockerjava.api.model.ContainerNetwork> entry : networks.entrySet()) {
        if (sb.length() > 0) {
          sb.append('|');
        }
        sb.append(entry.getKey())
            .append(':')
            .append(entry.getValue().getIpAddress());
      }
      return sb.toString();
    } catch (Exception e) {
      return "network-inspection-failed:" + e.getMessage();
    }
  }

  private static Path writeArtifacts(
      String talkerRuntime,
      String listenerRuntime,
      String talkerNetwork,
      String listenerNetwork,
      String talkerGraph,
      String listenerGraph,
      String talkerLogs,
      String listenerLogs,
      int heardCount,
      boolean talkerRmwOk,
      boolean listenerRmwOk) {
    try {
      String runId = Instant.now().toString().replace(":", "-");
      Path dir = Path.of("target", "test-artifacts", "jros2pubsub", runId);
      Files.createDirectories(dir);

      String summary = ""
          + "image=" + rosImage + "\n"
          + "ros_domain_id=" + ROS_DOMAIN_ID + "\n"
          + "rmw_implementation=" + rmwImplementation + "\n"
          + "ros_automatic_discovery_range=" + automaticDiscoveryRange + "\n"
          + "ros_static_peers=" + staticPeers + "\n"
          + "talker_rmw_ok=" + talkerRmwOk + "\n"
          + "listener_rmw_ok=" + listenerRmwOk + "\n"
          + "listener_heard_count=" + heardCount + "\n"
          + "listener_heard_threshold=" + minimumHeardMessages + "\n"
          + "talker_network=" + talkerNetwork + "\n"
          + "listener_network=" + listenerNetwork + "\n"
          + "talker_runtime=" + talkerRuntime + "\n"
          + "listener_runtime=" + listenerRuntime + "\n";

      Files.writeString(dir.resolve("summary.txt"), summary, StandardCharsets.UTF_8);
      Files.writeString(dir.resolve("talker.log"), talkerLogs, StandardCharsets.UTF_8);
      Files.writeString(dir.resolve("listener.log"), listenerLogs, StandardCharsets.UTF_8);
      Files.writeString(dir.resolve("talker-graph.txt"), talkerGraph, StandardCharsets.UTF_8);
      Files.writeString(dir.resolve("listener-graph.txt"), listenerGraph, StandardCharsets.UTF_8);
      return dir;
    } catch (Exception e) {
      return Path.of("<artifact-write-failed:" + e.getMessage() + ">");
    }
  }

  private static String buildNodeCommand(String nodeName) {
    return "source /opt/ros/jazzy/setup.bash; "
        + "if ! ros2 pkg executables " + DEMO_PACKAGE + " >/dev/null 2>&1; then "
        + "apt-get update && apt-get install -y ros-jazzy-demo-nodes-cpp; "
        + "fi; "
        + "stdbuf -oL -eL ros2 run " + DEMO_PACKAGE + " " + nodeName;
  }

  private static String resolveRosImage() {
    String explicitImage = System.getProperty("maps.e2e.ros.image", System.getenv("MAPS_E2E_ROS_IMAGE"));
    if (explicitImage != null && !explicitImage.isBlank()) {
      return explicitImage.trim();
    }

    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    if (arch.contains("aarch64") || arch.contains("arm64")) {
      return DEFAULT_ROS_IMAGE_ARM64;
    }
    return DEFAULT_ROS_IMAGE_AMD64;
  }

  private static int parseIntConfig(String propertyName, String envValue, int defaultValue) {
    String raw = System.getProperty(propertyName, envValue);
    if (raw == null || raw.isBlank()) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(raw.trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }
}
