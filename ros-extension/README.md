# ROS Extension

MapsMessaging extension module for bridging MAPS topics with ROS2 topics using `jros2client`.

## Goals

- Support ROS2 topic bridge use cases for Nav2-oriented integrations.
- Preserve ROS message context for seamless MAPS -> protocol -> MAPS -> ROS roundtrips.
- Emit a stable schema convention so translators can map ROS payloads across protocols.

Current scope is intentionally limited to ROS2 topic transport, including TF and selected Nav2
monitoring messages. Service calls and action command execution are out of scope for this module.
That boundary is deliberate: ROS/Nav2 remains responsible for autonomy, while MAPS consumes,
routes, and exposes state.

## Architecture

```text
Robot / Drone / Submersible
  -> ROS2 / Nav2 / perception nodes
  -> ROS2 MAPS protocol adapter
  -> MAPS internal message model
  -> MQTT / AMQP / other MAPS protocols
```

The adapter is not a pairwise ROS-to-MQTT or ROS-to-AMQP converter. It maps ROS2 payloads into the
common MAPS message model, then MAPS routing and target protocol adapters handle delivery.
Format-level transformations such as JSON projection should be implemented through the MAPS
transformation layer, not hidden inside this protocol adapter.

## Schema Convention

Each inbound ROS message is emitted with:

- `maps.schema.kind = ros`
- `maps.schema.id = ros://<version>/<package>/<type>`
- `ros.version`, `ros.package`, `ros.type`, `ros.topic`
- `ros.qos`, `ros.context`

Payload bytes are kept as ROS wire payload (`contentType = application/x-ros-binary`) to avoid context loss.

This means MQTT/AMQP clients that consume raw ROS bridge topics receive ROS2 CDR bytes plus schema
metadata, not JSON. Likewise, publishing into ROS from MQTT/AMQP requires a ROS2 CDR payload unless a
separate MAPS transformation is configured.

## Configuration

See `src/main/resources/NetworkConnectionManager-example.yaml`.

Important fields:

- `rosVersion`: `2` (default and only supported value)
- `ros_domain_id`: optional ROS2 DDS domain override
- `network_interface`: optional network interface name for DDS traffic (for example `eth0`)
- `schema_mode`: `strict` (default) or `passthrough`
- Per-link: `ros_topic`, `ros_version`, `ros_package`, `ros_type`, `ros_qos`

In strict mode, both `ros_package` and `ros_type` are required on push and pull links.

### Per-link QoS (`ros_qos`)

The optional `ros_qos` field sets the DDS QoS profile for a link. Supported values:

| Value | Reliability | Durability | Typical use |
|---|---|---|---|
| *(omitted)* or `default` | RELIABLE | VOLATILE | Commands, services |
| `sensor_data` | BEST_EFFORT | VOLATILE | `/scan`, `/odom`, `/tf` |
| `reliable` | RELIABLE | VOLATILE | `/cmd_vel` (explicit) |
| `transient_local` | RELIABLE | TRANSIENT_LOCAL | `/tf_static` |

Matching the QoS profile to the publisher is important for subscription success. ROS2
sensor topics such as `/scan` and `/odom` use `BEST_EFFORT`; connecting with `RELIABLE`
will receive no messages.

Action feedback links use the action base topic in configuration. For example, configure
`ros_topic: "/navigate_to_pose"` with `ros_package: "nav2_msgs/action"` and
`ros_type: "NavigateToPose_FeedbackMessage"`. The jROS action metadata maps that base topic to the
ROS2 feedback topic (`/navigate_to_pose/_action/feedback`).

## Runtime Notes

ROS2 transport is implemented using `jros2client`.

To enable live ROS2 networking in your deployment, include `jros2client` and its transitive runtime
dependencies on the plugin classpath.

For remote ROS2 hosts over VPN, configure DDS discovery explicitly when needed:
- Set `ros_domain_id` to match the robot ROS2 domain.
- Set `network_interface` if auto-selection picks the wrong NIC.

Current Nav2 topic support with `jros2client` in this module includes:
- `sensor_msgs/LaserScan`
- `std_msgs/Bool` (for example `/collision_ahead`)
- `std_msgs/Float32` (for example `/nearest_obstacle_m`)
- `nav_msgs/Odometry`
- `geometry_msgs/Twist`
- `tf2_msgs/TFMessage` (`/tf` and `/tf_static`)
- `nav2_msgs/Costmap` (for example `/local_costmap/costmap`)
- `nav2_msgs/action/NavigateToPose_FeedbackMessage` (action feedback monitoring topic)
- `action_msgs/GoalStatusArray` (topic-style status array payloads)

The bridge remains navigation-agnostic: it maps configured topics and selected monitoring streams
without implementing planners, costmap logic, or autonomy behavior.

## Scope Against The Nav2 Validation Plan

Covered by this module:
- `/scan` as `sensor_msgs/LaserScan`
- `/collision_ahead` as `std_msgs/Bool`
- `/nearest_obstacle_m` as `std_msgs/Float32`
- `/cmd_vel` as `geometry_msgs/Twist`
- `/odom` as `nav_msgs/Odometry`
- `/tf` and `/tf_static` as `tf2_msgs/TFMessage`
- `/local_costmap/costmap` as `nav2_msgs/Costmap`
- `/navigate_to_pose` feedback monitoring as `nav2_msgs/action/NavigateToPose_FeedbackMessage`
- `/navigate_to_pose/_action/status` payload transport as `action_msgs/GoalStatusArray`

Explicitly not covered:
- RealSense D435i setup, depth stream launch, or `depthimage_to_laserscan` launch. Those are ROS-side
  deployment prerequisites that produce topics such as `/scan`.
- Collision detection algorithms. The bridge transports `/collision_ahead` and
  `/nearest_obstacle_m` once a ROS node publishes them.
- Nav2 planning, recovery behavior, costmap parsing, or velocity generation.
- Nav2 action goal/result/cancel orchestration and ROS2 services.
- JSON transformation for MQTT/AMQP consumers. Use MAPS transformations for that layer.

## Message Type Ownership

Message types registered in this extension come from two sources:

**jrosmessages / jros2messages library** (upstream; no local copy needed):

| ROS type | Java class |
|---|---|
| `std_msgs/String` | `id.jrosmessages.std_msgs.StringMessage` |
| `geometry_msgs/Twist` | `id.jrosmessages.geometry_msgs.TwistMessage` |

**Extension-owned** (hand-written POJOs in the `messages` sub-package; not present in the
upstream libraries at versions 12.0 / 10.0):

| ROS type | Java class |
|---|---|
| `std_msgs/Bool` | `messages/std_msgs/BoolMessage` |
| `std_msgs/Float32` | `messages/std_msgs/Float32Message` |
| `sensor_msgs/LaserScan` | `messages/sensor_msgs/LaserScanMessage` |
| `nav_msgs/Odometry` | `messages/nav_msgs/OdometryMessage` |
| `tf2_msgs/TFMessage` | `messages/tf2_msgs/TFMessage` |
| `nav2_msgs/Costmap` | `messages/nav2_msgs/CostmapMessage` |
| `nav2_msgs/action/NavigateToPose_FeedbackMessage` | `messages/nav2_msgs/action/NavigateToPose_FeedbackMessage` |
| `action_msgs/GoalStatusArray` | `messages/action_msgs/GoalStatusArrayMessage` |

When upgrading `jrosmessages` or `jros2messages`, check whether any extension-owned types
have been added to the upstream library and remove the local copy if present. The
`Ros2MessageTypeRegistry` Javadoc lists the same information in-code.

## Integration Testing

`MapsRos2MessageTypesIT` validates registered message types end-to-end as black-box containers:
- Starts MAPS server container image and injects this module jar plus runtime ROS dependencies.
- Starts a ROS2 Jazzy container on a shared Docker network.
- Generates the MAPS ROS bridge configuration dynamically for each registered message type.
- Asserts both `ROS -> MAPS` and `MAPS -> ROS` for each registered type.
- Captures separate MAPS-container JaCoCo coverage under `target/site/jacoco-maps`.

`JRos2PubSubIT` remains as the ROS2-only Docker networking smoke test.

The MAPS/ROS E2E suite validates both directions for every registered type:
- `std_msgs/String`
- `std_msgs/Bool`
- `std_msgs/Float32`
- `geometry_msgs/Twist`
- `sensor_msgs/LaserScan`
- `nav_msgs/Odometry`
- `tf2_msgs/TFMessage`
- `nav2_msgs/Costmap`
- `nav2_msgs/action/NavigateToPose_FeedbackMessage`
- `action_msgs/GoalStatusArray`

Prerequisites:
- Docker daemon running.
- Network access to pull Docker images when not already present locally.
- A reachable local Docker socket (auto-detected for common Desktop/Engine setups).

Default image resolution:
- MAPS image is derived from `${maps.core.version}` and local architecture:
  - ARM64: `mapsmessaging/server_daemon_arm_<maps.core.version-lowercase>`
  - x86_64: `mapsmessaging/server_daemon_<maps.core.version-lowercase>`
- ROS image default:
  - x86_64: `osrf/ros:jazzy-desktop`
  - ARM64: `arm64v8/ros:jazzy-perception`

Testcontainers will pull missing MAPS/ROS images automatically on container start.
The test auto-configures Testcontainers Docker host for common macOS/Windows/Linux setups
(Rancher Desktop, Docker Desktop, Linux Docker Engine) without shelling out to the `docker` CLI.

Run E2E:
`mvn -pl ros-extension -Dtest=MapsRos2MessageTypesIT test -DfailIfNoTests=false`

Generate coverage reports:
- Unit/JUnit JVM: `target/site/jacoco`
- MAPS container JVM: `target/site/jacoco-maps`
- Merged: `target/site/jacoco-merged`

Optional image overrides:
- `MAPS_E2E_MAPS_IMAGE` or `-Dmaps.e2e.maps.image=...`
- `MAPS_E2E_ROS_IMAGE` or `-Dmaps.e2e.ros.image=...`
- `MAPS_E2E_MAPS_VERSION` or `-Dmaps.e2e.maps.version=...`

Optional Docker/Testcontainers overrides (normally not required):
- `DOCKER_HOST`
- `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE`

## Remote ROS2 Validation

For a live ROS2/Nav2 server, validate the ROS side before enabling the MAPS bridge:

```bash
ros2 topic list
ros2 topic info /scan
ros2 topic info /odom
ros2 topic info /cmd_vel
ros2 topic info /tf
ros2 topic info /local_costmap/costmap
ros2 topic info /navigate_to_pose/_action/feedback
```

The bridge configuration should then map only the topics you want MAPS to observe or publish. For the
RealSense/depthimage validation path, ROS is responsible for launching the camera and
`depthimage_to_laserscan`; this extension starts at the resulting ROS topics, especially `/scan`.

If the ROS2 host is reachable over VPN, match the robot's `ROS_DOMAIN_ID` and configure
`network_interface` if DDS selects the wrong local interface. DDS discovery across VPNs is
environment-sensitive, so first prove ROS2 CLI visibility from the MAPS host before debugging the
bridge.
