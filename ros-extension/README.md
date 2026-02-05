# ROS Extension

MapsMessaging extension module for bridging MAPS topics with ROS topics using the jrosclient ecosystem.

## Goals

- Support ROS 1 and ROS 2 deployments through one extension contract.
- Preserve ROS message context for seamless MAPS -> protocol -> MAPS -> ROS roundtrips.
- Emit a stable schema convention so translators can map ROS payloads across protocols.

## Schema Convention

Each inbound ROS message is emitted with:

- `maps.schema.kind = ros`
- `maps.schema.id = ros://<version>/<package>/<type>`
- `ros.version`, `ros.package`, `ros.type`, `ros.topic`
- `ros.md5` (ROS1 when available), `ros.qos` (ROS2 when available), `ros.context`

Payload bytes are kept as ROS wire payload (`contentType = application/x-ros-binary`) to avoid context loss.

## Configuration

See `src/main/resources/NetworkConnectionManager-example.yaml`.

Important fields:

- `rosVersion`: `1`, `2`, or `auto`
- `schema_mode`: `strict` (default) or `passthrough`
- Per-link: `ros_topic`, `ros_version`, `ros_package`, `ros_type`

In strict mode, `ros_type` is required on push and pull links.

## Runtime Notes

This module uses a reflection-based adapter boundary to stay binary-compatible across jrosclient versions.

To enable live ROS networking in your deployment, include jrosclient runtime jars on the plugin classpath.
