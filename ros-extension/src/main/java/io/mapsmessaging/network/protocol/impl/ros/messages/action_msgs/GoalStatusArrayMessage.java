package io.mapsmessaging.network.protocol.impl.ros.messages.action_msgs;

import id.jrosmessages.Message;
import id.jrosmessages.MessageMetadata;

@MessageMetadata(
    name = "action_msgs/GoalStatusArray",
    fields = {"status_list"}
)
public class GoalStatusArrayMessage implements Message {
  public GoalStatusMessage[] status_list = new GoalStatusMessage[0];
}
