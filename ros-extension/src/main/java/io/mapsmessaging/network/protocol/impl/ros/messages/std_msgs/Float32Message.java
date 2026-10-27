package io.mapsmessaging.network.protocol.impl.ros.messages.std_msgs;

import id.jrosmessages.Message;
import id.jrosmessages.MessageMetadata;

@MessageMetadata(
    name = "std_msgs/Float32",
    fields = {"data"}
)
public class Float32Message implements Message {
  public float data;
}
