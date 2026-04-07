package io.mapsmessaging.network.protocol.impl.ros.messages.action_msgs;

import id.jros2messages.unique_identifier_msgs.UUIDMessage;
import id.jrosmessages.Message;
import id.jrosmessages.MessageMetadata;
import id.jrosmessages.primitives.Time;

@MessageMetadata(
    name = "action_msgs/GoalInfo",
    fields = {"goal_id", "stamp"}
)
public class GoalInfoMessage implements Message {
  public UUIDMessage goal_id = new UUIDMessage();
  public Time stamp = new Time();
}
