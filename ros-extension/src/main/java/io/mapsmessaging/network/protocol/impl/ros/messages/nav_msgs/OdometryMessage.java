package io.mapsmessaging.network.protocol.impl.ros.messages.nav_msgs;

import id.jros2messages.std_msgs.HeaderMessage;
import id.jrosmessages.Message;
import id.jrosmessages.MessageMetadata;
import id.jrosmessages.geometry_msgs.PoseWithCovarianceMessage;
import id.jrosmessages.geometry_msgs.TwistWithCovarianceMessage;

@MessageMetadata(
    name = "nav_msgs/Odometry",
    fields = {"header", "child_frame_id", "pose", "twist"}
)
public class OdometryMessage implements Message {
  public HeaderMessage header = new HeaderMessage();
  public String child_frame_id = "";
  public PoseWithCovarianceMessage pose = new PoseWithCovarianceMessage();
  public TwistWithCovarianceMessage twist = new TwistWithCovarianceMessage();

  public OdometryMessage withHeader(HeaderMessage value) {
    this.header = value;
    return this;
  }

  public OdometryMessage withChildFrameId(String value) {
    this.child_frame_id = value;
    return this;
  }

  public OdometryMessage withPose(PoseWithCovarianceMessage value) {
    this.pose = value;
    return this;
  }

  public OdometryMessage withTwist(TwistWithCovarianceMessage value) {
    this.twist = value;
    return this;
  }
}
