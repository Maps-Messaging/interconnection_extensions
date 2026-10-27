package io.mapsmessaging.network.protocol.impl.ros.messages.tf2_msgs;

import id.jros2messages.geometry_msgs.TransformStampedMessage;
import id.jrosmessages.Message;
import id.jrosmessages.MessageMetadata;

@MessageMetadata(
    name = "tf2_msgs/TFMessage",
    fields = {"transforms"}
)
public class TFMessage implements Message {
  public TransformStampedMessage[] transforms = new TransformStampedMessage[0];

  public TFMessage withTransforms(TransformStampedMessage... value) {
    this.transforms = value;
    return this;
  }
}
