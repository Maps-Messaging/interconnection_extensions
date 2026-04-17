package io.mapsmessaging.network.protocol.impl.ros.messages.std_msgs;

import id.jrosmessages.Message;
import id.jrosmessages.MessageMetadata;

@MessageMetadata(
    name = "std_msgs/Bool",
    fields = {"data"}
)
public class BoolMessage implements Message {
  public boolean data;
}
