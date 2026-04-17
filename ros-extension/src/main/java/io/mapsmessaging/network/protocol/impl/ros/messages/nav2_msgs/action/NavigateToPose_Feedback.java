package io.mapsmessaging.network.protocol.impl.ros.messages.nav2_msgs.action;

import id.jros2messages.geometry_msgs.PoseStampedMessage;
import id.jrosmessages.Message;
import id.jrosmessages.MessageMetadata;
import id.jrosmessages.primitives.Duration;

@MessageMetadata(
    name = "nav2_msgs/action/NavigateToPose_Feedback",
    fields = {
        "current_pose",
        "navigation_time",
        "estimated_time_remaining",
        "number_of_recoveries",
        "distance_remaining"
    }
)
public class NavigateToPose_Feedback implements Message {
  public PoseStampedMessage current_pose = new PoseStampedMessage();
  public Duration navigation_time = new Duration();
  public Duration estimated_time_remaining = new Duration();
  public int number_of_recoveries;
  public float distance_remaining;
}
