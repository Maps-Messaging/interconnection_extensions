package io.mapsmessaging.network.protocol.impl.ros.messages.sensor_msgs;

import id.jros2messages.std_msgs.HeaderMessage;
import id.jrosmessages.Message;
import id.jrosmessages.MessageMetadata;

@MessageMetadata(
    name = "sensor_msgs/LaserScan",
    fields = {
        "header",
        "angle_min",
        "angle_max",
        "angle_increment",
        "time_increment",
        "scan_time",
        "range_min",
        "range_max",
        "ranges",
        "intensities"
    }
)
public class LaserScanMessage implements Message {
  public HeaderMessage header = new HeaderMessage();
  public float angle_min;
  public float angle_max;
  public float angle_increment;
  public float time_increment;
  public float scan_time;
  public float range_min;
  public float range_max;
  public float[] ranges = new float[0];
  public float[] intensities = new float[0];

  public LaserScanMessage withHeader(HeaderMessage value) {
    this.header = value;
    return this;
  }

  public LaserScanMessage withRanges(float... value) {
    this.ranges = value;
    return this;
  }

  public LaserScanMessage withIntensities(float... value) {
    this.intensities = value;
    return this;
  }
}
