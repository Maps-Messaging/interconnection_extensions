package io.mapsmessaging.network.protocol.impl.ros.messages.action_msgs;

import id.jrosmessages.Message;
import id.jrosmessages.MessageMetadata;

@MessageMetadata(
    name = "action_msgs/GoalStatus",
    fields = {"goal_info", "status"}
)
public class GoalStatusMessage implements Message {
  public static final byte STATUS_UNKNOWN = 0;
  public static final byte STATUS_ACCEPTED = 1;
  public static final byte STATUS_EXECUTING = 2;
  public static final byte STATUS_CANCELING = 3;
  public static final byte STATUS_SUCCEEDED = 4;
  public static final byte STATUS_CANCELED = 5;
  public static final byte STATUS_ABORTED = 6;

  public GoalInfoMessage goal_info = new GoalInfoMessage();
  public byte status;
}
