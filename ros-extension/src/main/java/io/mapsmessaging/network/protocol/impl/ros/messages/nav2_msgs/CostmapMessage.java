package io.mapsmessaging.network.protocol.impl.ros.messages.nav2_msgs;

import id.jros2messages.std_msgs.HeaderMessage;
import id.jrosmessages.Message;
import id.jrosmessages.MessageMetadata;

@MessageMetadata(
    name = "nav2_msgs/Costmap",
    fields = {"header", "metadata", "data"}
)
public class CostmapMessage implements Message {
  public HeaderMessage header = new HeaderMessage();
  public CostmapMetaDataMessage metadata = new CostmapMetaDataMessage();
  public byte[] data = new byte[0];

  public CostmapMessage withData(byte... value) {
    this.data = value;
    return this;
  }
}
