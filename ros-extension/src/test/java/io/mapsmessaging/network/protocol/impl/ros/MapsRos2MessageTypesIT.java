package io.mapsmessaging.network.protocol.impl.ros;

import id.jros2messages.Ros2MessageSerializationUtils;
import id.jros2messages.geometry_msgs.PoseStampedMessage;
import id.jros2messages.geometry_msgs.TransformStampedMessage;
import id.jros2messages.std_msgs.HeaderMessage;
import id.jros2messages.unique_identifier_msgs.UUIDMessage;
import id.jrosmessages.Message;
import id.jrosmessages.geometry_msgs.PointMessage;
import id.jrosmessages.geometry_msgs.PoseMessage;
import id.jrosmessages.geometry_msgs.PoseWithCovarianceMessage;
import id.jrosmessages.geometry_msgs.QuaternionMessage;
import id.jrosmessages.geometry_msgs.TransformMessage;
import id.jrosmessages.geometry_msgs.TwistMessage;
import id.jrosmessages.geometry_msgs.TwistWithCovarianceMessage;
import id.jrosmessages.geometry_msgs.Vector3Message;
import id.jrosmessages.primitives.Duration;
import id.jrosmessages.std_msgs.StringMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.action_msgs.GoalStatusArrayMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.action_msgs.GoalStatusMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.nav2_msgs.CostmapMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.nav_msgs.OdometryMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.nav2_msgs.action.NavigateToPose_FeedbackMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.sensor_msgs.LaserScanMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.std_msgs.BoolMessage;
import io.mapsmessaging.network.protocol.impl.ros.messages.std_msgs.Float32Message;
import io.mapsmessaging.network.protocol.impl.ros.messages.tf2_msgs.TFMessage;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapsRos2MessageTypesIT {

  private static final String DEFAULT_ROS_IMAGE_AMD64 = "osrf/ros:jazzy-desktop";
  private static final String DEFAULT_ROS_IMAGE_ARM64 = "arm64v8/ros:jazzy-perception";
  private static final String DEFAULT_RMW_IMPLEMENTATION = "rmw_fastrtps_cpp";
  private static final String DEFAULT_AUTOMATIC_DISCOVERY_RANGE = "SUBNET";
  private static final String DEFAULT_STATIC_PEERS = "ros-listener;maps";

  private static final int ROS_DOMAIN_ID = 25;
  private static final int MQTT_PORT = 1883;
  private static final int MAPS_JACOCO_PORT = 6300;
  private static final int ROS_ECHO_TIMEOUT_SECONDS = 60;

  private static final Ros2MessageSerializationUtils SERIALIZER = new Ros2MessageSerializationUtils();
  private static final List<MessageCase> MESSAGE_CASES = buildMessageCases();

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
  private static Path bridgeConfigPath;
  private static Path extensionJarPath;
  private static Path mapsJacocoAgentPath;

  @BeforeAll
  static void beforeAll() throws Exception {
    skipReason = evaluatePrerequisites();
    if (skipReason != null) {
      return;
    }

    extensionJarPath = findExtensionJar();
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
    runArtifactPath = Path.of("target", "test-artifacts", "maps-ros2-message-types", runId);
    Files.createDirectories(runArtifactPath);
    bridgeConfigPath = runArtifactPath.resolve("NetworkConnectionManager.yaml");
    Files.writeString(bridgeConfigPath, buildBridgeConfig(MESSAGE_CASES));
    mapsJacocoAgentPath = findJacocoAgentJar();

    e2eNetwork = Network.builder()
        .driver("bridge")
        .createNetworkCmdModifier(cmd -> cmd.withAttachable(true))
        .build();

    mapsContainer = new GenericContainer<>(DockerImageName.parse(resolvedMapsImage))
        .withNetwork(e2eNetwork)
        .withNetworkAliases("maps")
        .withExposedPorts(MQTT_PORT, MAPS_JACOCO_PORT)
        .withCopyFileToContainer(
            MountableFile.forHostPath(bridgeConfigPath),
            "/opt/maps/conf/NetworkConnectionManager.yaml")
        .withEnv("MAPS_DATA", "/opt/maps_data")
        .withStartupTimeout(java.time.Duration.ofSeconds(90));

    mapsContainer.withCopyFileToContainer(MountableFile.forHostPath(extensionJarPath),
        "/opt/maps/lib/" + extensionJarPath.getFileName());
    for (Path runtimeLib : runtimeLibs) {
      mapsContainer.withCopyFileToContainer(MountableFile.forHostPath(runtimeLib),
          "/opt/maps/lib/" + runtimeLib.getFileName());
    }
    if (mapsJacocoAgentPath != null) {
      mapsContainer
          .withCopyFileToContainer(MountableFile.forHostPath(mapsJacocoAgentPath), "/opt/maps/lib/jacocoagent.jar")
          .withEnv("JAVA_TOOL_OPTIONS",
              "-javaagent:/opt/maps/lib/jacocoagent.jar=output=tcpserver,address=*,port="
                  + MAPS_JACOCO_PORT
                  + ",dumponexit=false,includes=io.mapsmessaging.network.protocol.impl.ros.*");
    }

    mapsContainer.start();
    mqttUrl = "tcp://" + mapsContainer.getHost() + ":" + mapsContainer.getMappedPort(MQTT_PORT);
    waitForMqttReady(java.time.Duration.ofSeconds(30), mqttUrl);

    rosContainer = new GenericContainer<>(DockerImageName.parse(resolvedRosImage))
        .withNetwork(e2eNetwork)
        .withNetworkAliases("ros", "ros-listener")
        .withEnv("ROS_DOMAIN_ID", Integer.toString(ROS_DOMAIN_ID))
        .withEnv("RMW_IMPLEMENTATION", rmwImplementation)
        .withEnv("ROS_AUTOMATIC_DISCOVERY_RANGE", automaticDiscoveryRange)
        .withEnv("ROS_STATIC_PEERS", staticPeers)
        .withEnv("FASTDDS_BUILTIN_TRANSPORTS", "UDPv4")
        .withStartupTimeout(java.time.Duration.ofSeconds(90))
        .withCommand("bash", "-lc", "source /opt/ros/jazzy/setup.bash && sleep infinity");

    rosContainer.start();
    ensureRosInterfacesAvailable();
    writeRunDiagnostics();
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

  @ParameterizedTest(name = "ROS2 -> MAPS {0}")
  @MethodSource("messageCases")
  @Timeout(value = 180, unit = TimeUnit.SECONDS)
  void shouldBridgeRosToMapsForRegisteredMessageType(MessageCase messageCase) throws Exception {
    Assumptions.assumeTrue(skipReason == null, skipReason);
    Path artifactPath = caseArtifactPath(messageCase.id(), "ros-to-maps");
    Files.createDirectories(artifactPath);

    MqttClient subscriber = null;
    CountDownLatch received = new CountDownLatch(1);
    AtomicReference<byte[]> payloadRef = new AtomicReference<>();

    try {
      String rosCliTopic = messageCase.rosCliTopic();
      waitForRosTopicType(rosCliTopic, messageCase.rosCliType(), java.time.Duration.ofSeconds(60));
      waitForRosSubscriptionCount(rosCliTopic, 1, java.time.Duration.ofSeconds(90));

      subscriber = new MqttClient(mqttUrl, "maps-e2e-ros2maps-" + messageCase.id() + "-" + UUID.randomUUID());
      subscriber.setCallback(new MqttCallback() {
        @Override
        public void connectionLost(Throwable cause) {
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
          payloadRef.set(message.getPayload());
          received.countDown();
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
        }
      });
      subscriber.connect(mqttOptions());
      subscriber.subscribe(messageCase.mapsTopic());

      Container.ExecResult publish = rosContainer.execInContainer(
          "bash", "-lc",
          "source /opt/ros/jazzy/setup.bash && timeout 60s ros2 topic pub --once "
              + rosCliTopic
              + " "
              + messageCase.rosCliType()
              + " "
              + shellQuote(messageCase.rosCliYaml()));

      Files.writeString(artifactPath.resolve("ros_publish_output.log"), publish.getStdout() + "\n" + publish.getStderr());
      assertEquals(0, publish.getExitCode(),
          "Expected ROS publish to complete successfully for " + messageCase.id()
              + ", stdout=" + publish.getStdout()
              + " stderr=" + publish.getStderr());

      assertTrue(received.await(45, TimeUnit.SECONDS),
          "Timed out waiting for bridged MAPS message on " + messageCase.mapsTopic());
      assertNotNull(payloadRef.get(), "Expected bridged payload for " + messageCase.id());

      Message inbound = SERIALIZER.read(payloadRef.get(), messageCase.messageClass());
      messageCase.mapsPayloadAssertions().accept(inbound);

      Files.write(artifactPath.resolve("ros_to_maps_mqtt_payload.bin"), payloadRef.get());
      Files.writeString(artifactPath.resolve("flow_summary.txt"),
          "direction=ros2-to-maps" + System.lineSeparator()
              + "case=" + messageCase.id() + System.lineSeparator()
              + "ros.topic=" + messageCase.rosTopic() + System.lineSeparator()
              + "ros.cli.topic=" + rosCliTopic + System.lineSeparator()
              + "maps.topic=" + messageCase.mapsTopic() + System.lineSeparator()
              + "ros.type=" + messageCase.rosCliType() + System.lineSeparator());
      writeRosTopicInfo(artifactPath.resolve("ros_topic_info_pull.txt"), rosCliTopic);
    } catch (Throwable t) {
      captureFailureArtifacts(artifactPath, t, messageCase);
      throw t;
    } finally {
      if (subscriber != null) {
        safeDisconnect(subscriber);
      }
    }
  }

  @ParameterizedTest(name = "MAPS -> ROS2 {0}")
  @MethodSource("messageCases")
  @Timeout(value = 180, unit = TimeUnit.SECONDS)
  void shouldBridgeMapsToRosForRegisteredMessageType(MessageCase messageCase) throws Exception {
    Assumptions.assumeTrue(skipReason == null, skipReason);
    Path artifactPath = caseArtifactPath(messageCase.id(), "maps-to-ros");
    Files.createDirectories(artifactPath);

    MqttClient publisher = null;
    String outboundRosTopic = messageCase.outboundRosTopic();
    String outboundRosCliTopic = messageCase.outboundRosCliTopic();
    String outboundMapsTopic = messageCase.outboundMapsTopic();
    byte[] payload = SERIALIZER.write(messageCase.mapsToRosMessage());

    try {
      waitForRosTopicType(outboundRosCliTopic, messageCase.rosCliType(), java.time.Duration.ofSeconds(60));
      waitForRosPublisherCount(outboundRosCliTopic, 1, java.time.Duration.ofSeconds(90));

      CompletableFuture<Container.ExecResult> echoFuture = CompletableFuture.supplyAsync(() -> {
        try {
          return rosContainer.execInContainer(
              "bash", "-lc",
              buildEchoCommand(outboundRosCliTopic, messageCase.rosCliType()));
        } catch (Exception e) {
          throw new CompletionException(e);
        }
      });

      publisher = new MqttClient(mqttUrl, "maps-e2e-maps2ros-" + messageCase.id() + "-" + UUID.randomUUID());
      publisher.connect(mqttOptions());

      for (int attempts = 0; attempts < 30 && !echoFuture.isDone(); attempts++) {
        publisher.publish(outboundMapsTopic, payload, 0, false);
        Thread.sleep(500L);
      }

      Container.ExecResult echoed = echoFuture.get(120, TimeUnit.SECONDS);
      Files.writeString(artifactPath.resolve("maps_to_ros_echo_output.log"), echoed.getStdout() + "\n" + echoed.getStderr());

      assertEquals(0, echoed.getExitCode(),
          "Expected ROS echo to complete successfully for " + messageCase.id()
              + ", stdout=" + echoed.getStdout()
              + " stderr=" + echoed.getStderr());
      for (String expected : messageCase.rosEchoAssertions()) {
        assertTrue(echoed.getStdout().contains(expected),
            "Expected ROS echo for " + messageCase.id()
                + " to contain '" + expected + "'. stdout=" + echoed.getStdout());
      }

      Files.writeString(artifactPath.resolve("flow_summary.txt"),
          "direction=maps-to-ros2" + System.lineSeparator()
              + "case=" + messageCase.id() + System.lineSeparator()
              + "maps.topic=" + outboundMapsTopic + System.lineSeparator()
              + "ros.topic=" + outboundRosTopic + System.lineSeparator()
              + "ros.cli.topic=" + outboundRosCliTopic + System.lineSeparator()
              + "ros.type=" + messageCase.rosCliType() + System.lineSeparator());
      writeRosTopicInfo(artifactPath.resolve("ros_topic_info_push.txt"), outboundRosCliTopic);
    } catch (Throwable t) {
      captureFailureArtifacts(artifactPath, t, messageCase);
      throw t;
    } finally {
      if (publisher != null) {
        safeDisconnect(publisher);
      }
    }
  }

  static Stream<MessageCase> messageCases() {
    return MESSAGE_CASES.stream();
  }

  private static List<MessageCase> buildMessageCases() {
    return List.of(
        stringCase(),
        boolCase(),
        float32Case(),
        twistCase(),
        laserScanCase(),
        odometryCase(),
        tfCase(),
        costmapCase(),
        navigateToPoseFeedbackCase(),
        goalStatusArrayCase());
  }

  private static MessageCase stringCase() {
    StringMessage message = new StringMessage();
    message.data = "maps-string-sentinel";
    return new MessageCase(
        "std-string",
        "std_msgs",
        "String",
        StringMessage.class,
        "/maps_ros2_it_string",
        "/maps/ros/it/string",
        message,
        "{data: 'maps-string-sentinel'}",
        inbound -> assertEquals("maps-string-sentinel", ((StringMessage) inbound).data),
        List.of("data: maps-string-sentinel"),
        List.of("std_msgs/msg/String"));
  }

  private static MessageCase boolCase() {
    BoolMessage message = new BoolMessage();
    message.data = true;
    return new MessageCase(
        "std-bool-collision-ahead",
        "std_msgs",
        "Bool",
        BoolMessage.class,
        "/maps_ros2_it_collision_ahead",
        "/maps/ros/it/collision_ahead",
        message,
        "{data: true}",
        inbound -> assertTrue(((BoolMessage) inbound).data),
        List.of("data: true"),
        List.of("std_msgs/msg/Bool"));
  }

  private static MessageCase float32Case() {
    Float32Message message = new Float32Message();
    message.data = 0.85f;
    return new MessageCase(
        "std-float32-nearest-obstacle",
        "std_msgs",
        "Float32",
        Float32Message.class,
        "/maps_ros2_it_nearest_obstacle_m",
        "/maps/ros/it/nearest_obstacle_m",
        message,
        "{data: 0.85}",
        inbound -> assertEquals(0.85f, ((Float32Message) inbound).data, 0.0001f),
        List.of("data: 0.850"),
        List.of("std_msgs/msg/Float32"));
  }

  private static MessageCase twistCase() {
    TwistMessage message = new TwistMessage();
    message.linear = new Vector3Message(1.25, 0.0, 0.0);
    message.angular = new Vector3Message(0.0, 0.0, 0.75);
    return new MessageCase(
        "geometry-twist",
        "geometry_msgs",
        "Twist",
        TwistMessage.class,
        "/maps_ros2_it_cmd_vel",
        "/maps/ros/it/cmd_vel",
        message,
        "{linear: {x: 1.25, y: 0.0, z: 0.0}, angular: {x: 0.0, y: 0.0, z: 0.75}}",
        inbound -> {
          TwistMessage twist = (TwistMessage) inbound;
          assertEquals(1.25, twist.linear.x, 0.0001);
          assertEquals(0.75, twist.angular.z, 0.0001);
        },
        List.of("x: 1.25", "z: 0.75"),
        List.of("geometry_msgs/msg/Twist"));
  }

  private static MessageCase laserScanCase() {
    LaserScanMessage message = new LaserScanMessage();
    message.header.frame_id = "laser_frame";
    message.angle_min = -0.174533f;
    message.angle_max = 0.174533f;
    message.angle_increment = 0.174533f;
    message.range_min = 0.45f;
    message.range_max = 10.0f;
    message.ranges = new float[]{1.0f, 2.5f, 3.0f};
    message.intensities = new float[]{0.1f, 0.2f, 0.3f};
    return new MessageCase(
        "sensor-laserscan",
        "sensor_msgs",
        "LaserScan",
        LaserScanMessage.class,
        "/maps_ros2_it_scan",
        "/maps/ros/it/scan",
        message,
        "{header: {frame_id: 'laser_frame'}, angle_min: -0.174533, angle_max: 0.174533, "
            + "angle_increment: 0.174533, time_increment: 0.0, scan_time: 0.0, range_min: 0.45, "
            + "range_max: 10.0, ranges: [1.0, 2.5, 3.0], intensities: [0.1, 0.2, 0.3]}",
        inbound -> {
          LaserScanMessage scan = (LaserScanMessage) inbound;
          assertEquals(-0.174533f, scan.angle_min, 0.0001f);
          assertEquals(3, scan.ranges.length);
          assertEquals(2.5f, scan.ranges[1], 0.0001f);
        },
        List.of("angle_min: -0.174532", "ranges:"),
        List.of("sensor_msgs/msg/LaserScan"));
  }

  private static MessageCase odometryCase() {
    OdometryMessage message = new OdometryMessage();
    message.header.frame_id = "odom";
    message.child_frame_id = "base_link";
    message.pose = poseWithCovariance(4.5, 1.5);
    message.twist = twistWithCovariance(0.42, 0.24);
    return new MessageCase(
        "nav-odometry",
        "nav_msgs",
        "Odometry",
        OdometryMessage.class,
        "/maps_ros2_it_odom",
        "/maps/ros/it/odom",
        message,
        "{header: {frame_id: 'odom'}, child_frame_id: 'base_link', "
            + "pose: {pose: {position: {x: 4.5, y: 1.5, z: 0.0}, orientation: {x: 0.0, y: 0.0, z: 0.0, w: 1.0}}, "
            + "covariance: [0.11, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]}, "
            + "twist: {twist: {linear: {x: 0.42, y: 0.0, z: 0.0}, angular: {x: 0.0, y: 0.0, z: 0.24}}, "
            + "covariance: [0.22, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]}}",
        inbound -> {
          OdometryMessage odometry = (OdometryMessage) inbound;
          assertEquals("base_link", odometry.child_frame_id);
          assertEquals(4.5, odometry.pose.pose.position.x, 0.0001);
          assertEquals(0.42, odometry.twist.twist.linear.x, 0.0001);
        },
        List.of("child_frame_id: base_link", "x: 4.5", "x: 0.42"),
        List.of("nav_msgs/msg/Odometry"));
  }

  private static MessageCase tfCase() {
    TFMessage message = new TFMessage();
    TransformStampedMessage transform = new TransformStampedMessage();
    transform.header = new HeaderMessage().withFrameId("map");
    StringMessage childFrame = new StringMessage();
    childFrame.data = "base_link";
    transform.child_frame_id = childFrame;
    transform.transform = new TransformMessage()
        .withTranslation(new Vector3Message(1.0, 2.0, 3.0))
        .withRotation(new QuaternionMessage(0.0, 0.0, 0.0, 1.0));
    message.transforms = new TransformStampedMessage[]{transform};
    return new MessageCase(
        "tf-message",
        "tf2_msgs",
        "TFMessage",
        TFMessage.class,
        "/maps_ros2_it_tf",
        "/maps/ros/it/tf",
        message,
        "{transforms: [{header: {frame_id: 'map'}, child_frame_id: 'base_link', "
            + "transform: {translation: {x: 1.0, y: 2.0, z: 3.0}, rotation: {x: 0.0, y: 0.0, z: 0.0, w: 1.0}}}]}",
        inbound -> {
          TFMessage tf = (TFMessage) inbound;
          assertEquals(1, tf.transforms.length);
          assertEquals("base_link", tf.transforms[0].child_frame_id.data);
          assertEquals(1.0, tf.transforms[0].transform.translation.x, 0.0001);
        },
        List.of("child_frame_id: base_link", "x: 1.0"),
        List.of("tf2_msgs/msg/TFMessage"));
  }

  private static MessageCase costmapCase() {
    CostmapMessage message = new CostmapMessage();
    message.header.frame_id = "map";
    message.metadata.layer = "obstacles";
    message.metadata.resolution = 0.05f;
    message.metadata.size_x = 2;
    message.metadata.size_y = 2;
    message.data = new byte[]{1, 2, 3, 4};
    return new MessageCase(
        "nav2-costmap",
        "nav2_msgs",
        "Costmap",
        CostmapMessage.class,
        "/maps_ros2_it_local_costmap",
        "/maps/ros/it/local_costmap",
        message,
        "{header: {frame_id: 'map'}, metadata: {layer: 'obstacles', resolution: 0.05, size_x: 2, size_y: 2, "
            + "origin: {position: {x: 0.0, y: 0.0, z: 0.0}, orientation: {x: 0.0, y: 0.0, z: 0.0, w: 1.0}}}, "
            + "data: [1, 2, 3, 4]}",
        inbound -> {
          CostmapMessage costmap = (CostmapMessage) inbound;
          assertEquals("obstacles", costmap.metadata.layer);
          assertEquals(2, costmap.metadata.size_x);
          assertEquals(4, costmap.data.length);
        },
        List.of("layer: obstacles", "size_x: 2", "data:"),
        List.of("nav2_msgs/msg/Costmap"));
  }

  private static MessageCase navigateToPoseFeedbackCase() {
    NavigateToPose_FeedbackMessage message = new NavigateToPose_FeedbackMessage();
    message.goal_id = new UUIDMessage(UUID.fromString("00000000-0000-0000-0000-000000000123"));
    message.feedback.current_pose = poseStamped("map", 7.0, 8.0);
    message.feedback.navigation_time = new Duration(10, 0);
    message.feedback.estimated_time_remaining = new Duration(3, 0);
    message.feedback.number_of_recoveries = 2;
    message.feedback.distance_remaining = 12.5f;
    return new MessageCase(
        "nav2-navigate-feedback",
        "nav2_msgs/action",
        "NavigateToPose_FeedbackMessage",
        NavigateToPose_FeedbackMessage.class,
        "/maps_ros2_it_navigate_feedback",
        "/maps/ros/it/navigate_feedback",
        message,
        "{goal_id: {uuid: [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 35]}, "
            + "feedback: {current_pose: {header: {frame_id: 'map'}, pose: {position: {x: 7.0, y: 8.0, z: 0.0}, orientation: {x: 0.0, y: 0.0, z: 0.0, w: 1.0}}}, "
            + "navigation_time: {sec: 10, nanosec: 0}, estimated_time_remaining: {sec: 3, nanosec: 0}, "
            + "number_of_recoveries: 2, distance_remaining: 12.5}}",
        inbound -> {
          NavigateToPose_FeedbackMessage feedback = (NavigateToPose_FeedbackMessage) inbound;
          assertEquals(12.5f, feedback.feedback.distance_remaining, 0.0001f);
          assertEquals(2, feedback.feedback.number_of_recoveries);
        },
        List.of("distance_remaining: 12.5", "number_of_recoveries: 2"),
        List.of("nav2_msgs/action/NavigateToPose"));
  }

  private static MessageCase goalStatusArrayCase() {
    GoalStatusArrayMessage message = new GoalStatusArrayMessage();
    message.status_list = new GoalStatusMessage[0];
    return new MessageCase(
        "action-goal-status-array",
        "action_msgs",
        "GoalStatusArray",
        GoalStatusArrayMessage.class,
        "/maps_ros2_it_goal_status",
        "/maps/ros/it/goal_status",
        message,
        "{status_list: []}",
        inbound -> {
          GoalStatusArrayMessage statusArray = (GoalStatusArrayMessage) inbound;
          assertEquals(0, statusArray.status_list.length);
        },
        List.of("status_list: []"),
        List.of("action_msgs/msg/GoalStatusArray"));
  }

  private static PoseWithCovarianceMessage poseWithCovariance(double x, double y) {
    PoseWithCovarianceMessage pose = new PoseWithCovarianceMessage();
    pose.pose = new PoseMessage()
        .withPosition(new PointMessage(x, y, 0.0))
        .withQuaternion(new QuaternionMessage(0.0, 0.0, 0.0, 1.0));
    pose.covariance = new double[36];
    pose.covariance[0] = 0.11;
    return pose;
  }

  private static TwistWithCovarianceMessage twistWithCovariance(double linearX, double angularZ) {
    TwistWithCovarianceMessage twist = new TwistWithCovarianceMessage();
    twist.twist = new TwistMessage()
        .withLinear(new Vector3Message(linearX, 0.0, 0.0))
        .withAngular(new Vector3Message(0.0, 0.0, angularZ));
    twist.covariance = new double[36];
    twist.covariance[0] = 0.22;
    return twist;
  }

  private static PoseStampedMessage poseStamped(String frameId, double x, double y) {
    PoseStampedMessage pose = new PoseStampedMessage();
    pose.header = new HeaderMessage().withFrameId(frameId);
    pose.pose = new PoseMessage()
        .withPosition(new PointMessage(x, y, 0.0))
        .withQuaternion(new QuaternionMessage(0.0, 0.0, 0.0, 1.0));
    return pose;
  }

  private static String buildBridgeConfig(List<MessageCase> cases) {
    StringBuilder builder = new StringBuilder();
    builder.append("---\n")
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
        .append("    - name: ros_bridge_e2e\n")
        .append("      url: \"ros://localhost\"\n")
        .append("      remote:\n")
        .append("        username: anonymous\n")
        .append("        password: \"\"\n")
        .append("        sessionId: maps-ros2-message-types-e2e\n")
        .append("      protocol: ros\n")
        .append("      plugin: true\n")
        .append("      links:\n");
    for (MessageCase messageCase : cases) {
      appendEndpointLink(builder, "pull", messageCase.mapsTopic(), messageCase.rosTopic());
      appendEndpointLink(builder, "push", messageCase.outboundMapsTopic(), messageCase.outboundRosTopic());
    }
    builder.append("      config:\n")
        .append("        rosVersion: 2\n")
        .append("        schema_mode: strict\n")
        .append("        ros_domain_id: ").append(ROS_DOMAIN_ID).append("\n")
        .append("        network_interface: eth0\n")
        .append("        links:\n");
    for (MessageCase messageCase : cases) {
      appendConfigLink(builder, "pull", messageCase.mapsTopic(), messageCase.rosTopic(), messageCase);
      appendConfigLink(builder, "push", messageCase.outboundMapsTopic(), messageCase.outboundRosTopic(), messageCase);
    }
    return builder.toString();
  }

  private static void appendEndpointLink(StringBuilder builder, String direction, String mapsTopic, String rosTopic) {
    builder.append("        - direction: ").append(direction).append("\n")
        .append("          local_namespace: \"").append(mapsTopic).append("\"\n")
        .append("          remote_namespace: \"").append(rosTopic).append("\"\n")
        .append("          include_schema: true\n");
  }

  private static void appendConfigLink(
      StringBuilder builder,
      String direction,
      String mapsTopic,
      String rosTopic,
      MessageCase messageCase) {
    builder.append("          - direction: ").append(direction).append("\n")
        .append("            local_namespace: \"").append(mapsTopic).append("\"\n")
        .append("            remote_namespace: \"").append(rosTopic).append("\"\n")
        .append("            include_schema: true\n")
        .append("            ros_topic: \"").append(rosTopic).append("\"\n")
        .append("            ros_version: \"2\"\n")
        .append("            ros_package: \"").append(messageCase.rosPackage()).append("\"\n")
        .append("            ros_type: \"").append(messageCase.rosType()).append("\"\n");
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
            "MAPS_E2E_ROS_AUTOMATIC_DISCOVERY_RANGE",
            DEFAULT_AUTOMATIC_DISCOVERY_RANGE));
    staticPeers = System.getProperty(
        "maps.e2e.ros.static.peers",
        System.getenv().getOrDefault("MAPS_E2E_ROS_STATIC_PEERS", DEFAULT_STATIC_PEERS));

    return null;
  }

  private static void ensureRosInterfacesAvailable() throws Exception {
    String checks = MESSAGE_CASES.stream()
        .flatMap(messageCase -> messageCase.requiredRosInterfaces().stream())
        .distinct()
        .map(type -> "ros2 interface show " + type + " >/dev/null 2>&1")
        .reduce((left, right) -> left + " && " + right)
        .orElse("true");
    String installCommand = "source /opt/ros/jazzy/setup.bash && "
        + "if ! (" + checks + "); then "
        + "apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y "
        + "ros-jazzy-demo-nodes-cpp "
        + "ros-jazzy-common-interfaces "
        + "ros-jazzy-tf2-msgs "
        + "ros-jazzy-nav2-msgs; "
        + "fi && source /opt/ros/jazzy/setup.bash && " + checks;
    Container.ExecResult result = rosContainer.execInContainer("bash", "-lc", installCommand);
    Files.writeString(runArtifactPath.resolve("ros_interface_setup.log"), result.getStdout() + "\n" + result.getStderr());
    assertEquals(0, result.getExitCode(),
        "ROS interface setup failed. stdout=" + result.getStdout() + " stderr=" + result.getStderr());
  }

  private static String rosCliType(String rosPackage, String rosType) {
    if (rosPackage.contains("/action")) {
      return rosPackage + "/" + rosType;
    }
    return rosPackage + "/msg/" + rosType;
  }

  private static void captureFailureArtifacts(Path artifactPath, Throwable throwable, MessageCase messageCase) {
    try {
      Files.createDirectories(artifactPath);
      Files.writeString(artifactPath.resolve("failure.txt"), throwable.toString());
      Files.writeString(artifactPath.resolve("maps_container.log"), mapsContainer == null ? "" : mapsContainer.getLogs());
      Files.writeString(artifactPath.resolve("ros_container.log"), rosContainer == null ? "" : rosContainer.getLogs());
      writeRosTopicInfo(artifactPath.resolve("ros_topic_info_pull.txt"), messageCase.rosTopic());
      writeRosTopicInfo(artifactPath.resolve("ros_topic_info_push.txt"), messageCase.outboundRosTopic());
      writeRosTopicInfo(artifactPath.resolve("ros_topic_info_pull_cli.txt"), messageCase.rosCliTopic());
      writeRosTopicInfo(artifactPath.resolve("ros_topic_info_push_cli.txt"), messageCase.outboundRosCliTopic());
      if (rosContainer != null) {
        Files.writeString(artifactPath.resolve("ros_topic_list.txt"),
            rosContainer.execInContainer(
                    "bash", "-lc",
                    "source /opt/ros/jazzy/setup.bash && ros2 topic list -t || true")
                .getStdout());
      }
    } catch (Exception ignored) {
    }
  }

  private static void writeRunDiagnostics() throws Exception {
    Files.writeString(runArtifactPath.resolve("runtime.txt"),
        "maps.image=" + resolvedMapsImage + System.lineSeparator()
            + "ros.image=" + resolvedRosImage + System.lineSeparator()
            + "rmw.implementation=" + rmwImplementation + System.lineSeparator()
            + "automatic.discovery.range=" + automaticDiscoveryRange + System.lineSeparator()
            + "static.peers=" + staticPeers + System.lineSeparator()
            + "bridge.config=" + absolutePath(bridgeConfigPath) + System.lineSeparator()
            + "extension.jar=" + absolutePath(extensionJarPath) + System.lineSeparator()
            + "maps.jacoco.agent=" + absolutePath(mapsJacocoAgentPath) + System.lineSeparator());
    if (mapsContainer != null) {
      Files.writeString(runArtifactPath.resolve("mounted_NetworkConnectionManager.yaml"),
          mapsContainer.execInContainer(
                  "sh", "-lc",
                  "cat /opt/maps/conf/NetworkConnectionManager.yaml 2>&1 || true")
              .getStdout());
      Files.writeString(runArtifactPath.resolve("maps_lib_ros_extension.txt"),
          mapsContainer.execInContainer(
                  "sh", "-lc",
                  "ls -l /opt/maps/lib/*ros-extension* 2>&1 || true")
              .getStdout());
      Files.writeString(runArtifactPath.resolve("maps_lib_jacoco_agent.txt"),
          mapsContainer.execInContainer(
                  "sh", "-lc",
                  "ls -l /opt/maps/lib/jacocoagent.jar 2>&1 || true")
              .getStdout());
    }
  }

  private static Path caseArtifactPath(String caseId, String direction) {
    return runArtifactPath.resolve(caseId).resolve(direction);
  }

  private static void writeRosTopicInfo(Path path, String topic) throws Exception {
    if (rosContainer == null) {
      return;
    }
    Files.writeString(path,
        rosContainer.execInContainer(
                "bash", "-lc",
                "source /opt/ros/jazzy/setup.bash && ros2 topic info " + topic + " || true")
            .getStdout());
  }

  private static String buildEchoCommand(String topic, String expectedType) {
    return "source /opt/ros/jazzy/setup.bash && "
        + "for i in $(seq 1 " + ROS_ECHO_TIMEOUT_SECONDS + "); do "
        + "if ros2 topic info " + topic + " 2>/dev/null | grep -q \"Type: " + expectedType + "\"; then break; fi; "
        + "sleep 1; "
        + "done; "
        + "timeout " + ROS_ECHO_TIMEOUT_SECONDS + "s ros2 topic echo --once " + topic;
  }

  private static void waitForRosTopicType(String topic, String expectedType, java.time.Duration timeout) throws Exception {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      Container.ExecResult result = rosContainer.execInContainer(
          "bash", "-lc",
          "source /opt/ros/jazzy/setup.bash && ros2 topic info " + topic + " 2>/dev/null || true");
      if (result.getStdout().contains("Type: " + expectedType)) {
        return;
      }
      Thread.sleep(500L);
    }
    throw new IOException("Timed out waiting for ROS topic type " + expectedType + " on " + topic);
  }

  private static void waitForRosPublisherCount(String topic, int minimumCount, java.time.Duration timeout) throws Exception {
    waitForRosCount(topic, "Publisher count:", minimumCount, timeout);
  }

  private static void waitForRosSubscriptionCount(String topic, int minimumCount, java.time.Duration timeout) throws Exception {
    waitForRosCount(topic, "Subscription count:", minimumCount, timeout);
  }

  private static void waitForRosCount(String topic, String prefix, int minimumCount, java.time.Duration timeout) throws Exception {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      Container.ExecResult result = rosContainer.execInContainer(
          "bash", "-lc",
          "source /opt/ros/jazzy/setup.bash && ros2 topic info " + topic + " 2>/dev/null || true");
      int count = parseTopicCount(result.getStdout(), prefix);
      if (count >= minimumCount) {
        return;
      }
      Thread.sleep(500L);
    }
    throw new IOException("Timed out waiting for " + prefix + " >= " + minimumCount + " on topic " + topic);
  }

  private static int parseTopicCount(String output, String prefix) {
    for (String line : output.split("\\R")) {
      String trimmed = line.trim();
      if (trimmed.startsWith(prefix)) {
        String value = trimmed.substring(prefix.length()).trim();
        try {
          return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
          return 0;
        }
      }
    }
    return 0;
  }

  private static void waitForMqttReady(java.time.Duration timeout, String mqttUrl) throws Exception {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      MqttClient client = null;
      try {
        client = new MqttClient(mqttUrl, "maps-e2e-probe-" + UUID.randomUUID());
        client.connect(mqttOptions());
        client.disconnect();
        client.close();
        return;
      } catch (MqttException ignored) {
        if (client != null) {
          safeDisconnect(client);
        }
        Thread.sleep(500L);
      }
    }
    throw new IOException("MAPS MQTT broker did not become ready at " + mqttUrl);
  }

  private static void safeDisconnect(MqttClient client) {
    try {
      if (client.isConnected()) {
        client.disconnect();
      }
    } catch (Exception ignored) {
    }
    try {
      client.close();
    } catch (Exception ignored) {
    }
  }

  private static MqttConnectOptions mqttOptions() {
    MqttConnectOptions options = new MqttConnectOptions();
    options.setAutomaticReconnect(false);
    options.setCleanSession(true);
    options.setConnectionTimeout(5);
    return options;
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
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

  private static String resolveMapsImageName() {
    String explicitImage = System.getProperty("maps.e2e.maps.image", System.getenv("MAPS_E2E_MAPS_IMAGE"));
    if (explicitImage != null && !explicitImage.isBlank()) {
      return explicitImage.trim();
    }

    String mapsVersion = System.getProperty("maps.e2e.maps.version", System.getenv("MAPS_E2E_MAPS_VERSION"));
    if (mapsVersion == null || mapsVersion.isBlank()) {
      throw new IllegalStateException(
          "MAPS E2E image version is not configured. Set maps.e2e.maps.version, MAPS_E2E_MAPS_VERSION, "
              + "or maps.e2e.maps.image/MAPS_E2E_MAPS_IMAGE. Maven runs should inherit maps.core.version.");
    }

    String normalizedVersion = mapsVersion.toLowerCase(Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
    return (arch.contains("aarch64") || arch.contains("arm64"))
        ? "mapsmessaging/server_daemon_arm_" + normalizedVersion
        : "mapsmessaging/server_daemon_" + normalizedVersion;
  }

  private static String absolutePath(Path path) {
    return path == null ? "<unset>" : path.toAbsolutePath().normalize().toString();
  }

  private static void dumpMapsContainerCoverage() {
    if (mapsContainer == null || !mapsContainer.isRunning() || mapsJacocoAgentPath == null) {
      return;
    }
    try {
      ExecDumpClient client = new ExecDumpClient();
      client.setDump(true);
      client.setReset(false);
      client.setRetryCount(10);
      client.setRetryDelay(500L);
      ExecFileLoader loader = client.dump(mapsContainer.getHost(), mapsContainer.getMappedPort(MAPS_JACOCO_PORT));
      Path output = Path.of("target", "jacoco-maps.exec");
      Files.createDirectories(output.getParent());
      loader.save(output.toFile(), false);
      if (runArtifactPath != null) {
        Files.copy(output, runArtifactPath.resolve("jacoco-maps.exec"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to dump JaCoCo coverage from MAPS container", e);
    }
  }

  private static Path findExtensionJar() throws IOException {
    Path target = Path.of("target");
    if (!Files.isDirectory(target)) {
      return null;
    }
    try (var stream = Files.list(target)) {
      return stream
          .filter(path -> path.getFileName().toString().startsWith("ros-extension-"))
          .filter(path -> path.getFileName().toString().endsWith(".jar"))
          .filter(path -> !path.getFileName().toString().contains("-sources"))
          .filter(path -> !path.getFileName().toString().contains("-javadoc"))
          .sorted(Comparator.comparing(Path::toString))
          .findFirst()
          .orElse(null);
    }
  }

  private static List<Path> listRuntimeLibs(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    List<Path> libs = new ArrayList<>();
    try (var stream = Files.list(directory)) {
      stream
          .filter(path -> path.getFileName().toString().endsWith(".jar"))
          .sorted(Comparator.comparing(Path::toString))
          .forEach(libs::add);
    }
    return libs;
  }

  private static Path findJacocoAgentJar() throws IOException {
    String explicitAgent = System.getProperty("maps.e2e.jacoco.agent", System.getenv("MAPS_E2E_JACOCO_AGENT"));
    if (explicitAgent != null && !explicitAgent.isBlank()) {
      Path path = Path.of(explicitAgent.trim());
      return Files.exists(path) ? path : null;
    }

    Path agentDirectory = Path.of(System.getProperty("user.home"), ".m2", "repository",
        "org", "jacoco", "org.jacoco.agent");
    if (!Files.isDirectory(agentDirectory)) {
      return null;
    }
    try (var versions = Files.list(agentDirectory)) {
      return versions
          .filter(Files::isDirectory)
          .flatMap(version -> {
            try {
              return Files.list(version)
                  .filter(path -> path.getFileName().toString().endsWith("-runtime.jar"));
            } catch (IOException e) {
              return Stream.<Path>empty();
            }
          })
          .sorted(Comparator.comparing(Path::toString).reversed())
          .findFirst()
          .orElse(null);
    }
  }

  private record MessageCase(
      String id,
      String rosPackage,
      String rosType,
      Class<? extends Message> messageClass,
      String rosTopic,
      String mapsTopic,
      Message mapsToRosMessage,
      String rosCliYaml,
      Consumer<Message> mapsPayloadAssertions,
      List<String> rosEchoAssertions,
      List<String> requiredRosInterfaces) {

    String outboundRosTopic() {
      return rosTopic + "_outbound";
    }

    String outboundMapsTopic() {
      return mapsTopic + "_outbound";
    }

    String rosCliTopic() {
      return rosCliTopicFor(rosTopic);
    }

    String outboundRosCliTopic() {
      return rosCliTopicFor(outboundRosTopic());
    }

    private String rosCliTopicFor(String topic) {
      if ("nav2_msgs/action".equals(rosPackage) && "NavigateToPose_FeedbackMessage".equals(rosType)) {
        return topic + "/_action/feedback";
      }
      return topic;
    }

    String rosCliType() {
      return MapsRos2MessageTypesIT.rosCliType(rosPackage, rosType);
    }

    @Override
    public String toString() {
      return id + " (" + rosCliType() + ")";
    }
  }
}
