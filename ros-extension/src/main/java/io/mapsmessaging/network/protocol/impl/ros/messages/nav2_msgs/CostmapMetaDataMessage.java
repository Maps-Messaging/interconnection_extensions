package io.mapsmessaging.network.protocol.impl.ros.messages.nav2_msgs;

import id.jrosmessages.Message;
import id.jrosmessages.MessageMetadata;
import id.jrosmessages.geometry_msgs.PoseMessage;
import id.jrosmessages.primitives.Time;

@MessageMetadata(
    name = "nav2_msgs/CostmapMetaData",
    fields = {"map_load_time", "update_time", "layer", "resolution", "size_x", "size_y", "origin"}
)
public class CostmapMetaDataMessage implements Message {
  public Time map_load_time = new Time();
  public Time update_time = new Time();
  public String layer = "";
  public float resolution;
  public int size_x;
  public int size_y;
  public PoseMessage origin = new PoseMessage();

  public CostmapMetaDataMessage withLayer(String value) {
    this.layer = value;
    return this;
  }
}
