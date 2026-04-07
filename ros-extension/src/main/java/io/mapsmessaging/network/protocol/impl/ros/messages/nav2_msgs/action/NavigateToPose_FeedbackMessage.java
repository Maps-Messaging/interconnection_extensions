package io.mapsmessaging.network.protocol.impl.ros.messages.nav2_msgs.action;

import id.jros2messages.unique_identifier_msgs.UUIDMessage;
import id.jrosmessages.Message;
import id.jrosmessages.MessageMetadata;
import id.jrosmessages.RosInterfaceType;

@MessageMetadata(
    name = "nav2_msgs/NavigateToPoseActionGoalFeedback",
    fields = {"goal_id", "feedback"},
    interfaceType = RosInterfaceType.ACTION
)
public class NavigateToPose_FeedbackMessage implements Message {
  public UUIDMessage goal_id = new UUIDMessage();
  public NavigateToPose_Feedback feedback = new NavigateToPose_Feedback();
}
