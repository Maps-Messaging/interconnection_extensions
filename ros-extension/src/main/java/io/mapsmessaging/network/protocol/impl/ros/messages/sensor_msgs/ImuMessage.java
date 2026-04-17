/*
 *  Copyright [ 2020 - 2024 ] Matthew Buckton
 *  Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 *
 *  Licensed under the Apache License, Version 2.0 with the Commons Clause
 *  (the "License"); you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *      https://commonsclause.com/
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package io.mapsmessaging.network.protocol.impl.ros.messages.sensor_msgs;

import id.jros2messages.std_msgs.HeaderMessage;
import id.jrosmessages.Message;
import id.jrosmessages.MessageMetadata;
import id.jrosmessages.geometry_msgs.QuaternionMessage;
import id.jrosmessages.geometry_msgs.Vector3Message;
import lombok.ToString;

@MessageMetadata(
    name = "sensor_msgs/Imu",
    fields = {
        "header",
        "orientation",
        "orientation_covariance_0",
        "orientation_covariance_1",
        "orientation_covariance_2",
        "orientation_covariance_3",
        "orientation_covariance_4",
        "orientation_covariance_5",
        "orientation_covariance_6",
        "orientation_covariance_7",
        "orientation_covariance_8",
        "angular_velocity",
        "angular_velocity_covariance_0",
        "angular_velocity_covariance_1",
        "angular_velocity_covariance_2",
        "angular_velocity_covariance_3",
        "angular_velocity_covariance_4",
        "angular_velocity_covariance_5",
        "angular_velocity_covariance_6",
        "angular_velocity_covariance_7",
        "angular_velocity_covariance_8",
        "linear_acceleration",
        "linear_acceleration_covariance_0",
        "linear_acceleration_covariance_1",
        "linear_acceleration_covariance_2",
        "linear_acceleration_covariance_3",
        "linear_acceleration_covariance_4",
        "linear_acceleration_covariance_5",
        "linear_acceleration_covariance_6",
        "linear_acceleration_covariance_7",
        "linear_acceleration_covariance_8"
    }
)
@ToString
public class ImuMessage implements Message {

  public HeaderMessage header = new HeaderMessage();

  public QuaternionMessage orientation = new QuaternionMessage();

  public double orientation_covariance_0;
  public double orientation_covariance_1;
  public double orientation_covariance_2;
  public double orientation_covariance_3;
  public double orientation_covariance_4;
  public double orientation_covariance_5;
  public double orientation_covariance_6;
  public double orientation_covariance_7;
  public double orientation_covariance_8;

  public Vector3Message angular_velocity = new Vector3Message();

  public double angular_velocity_covariance_0;
  public double angular_velocity_covariance_1;
  public double angular_velocity_covariance_2;
  public double angular_velocity_covariance_3;
  public double angular_velocity_covariance_4;
  public double angular_velocity_covariance_5;
  public double angular_velocity_covariance_6;
  public double angular_velocity_covariance_7;
  public double angular_velocity_covariance_8;

  public Vector3Message linear_acceleration = new Vector3Message();

  public double linear_acceleration_covariance_0;
  public double linear_acceleration_covariance_1;
  public double linear_acceleration_covariance_2;
  public double linear_acceleration_covariance_3;
  public double linear_acceleration_covariance_4;
  public double linear_acceleration_covariance_5;
  public double linear_acceleration_covariance_6;
  public double linear_acceleration_covariance_7;
  public double linear_acceleration_covariance_8;

  public ImuMessage withHeader(HeaderMessage value) {
    this.header = value;
    return this;
  }

  public ImuMessage withOrientation(QuaternionMessage value) {
    this.orientation = value;
    return this;
  }

  public ImuMessage withAngularVelocity(Vector3Message value) {
    this.angular_velocity = value;
    return this;
  }

  public ImuMessage withLinearAcceleration(Vector3Message value) {
    this.linear_acceleration = value;
    return this;
  }

  public double[] getOrientationCovariance() {
    return new double[] {
        orientation_covariance_0,
        orientation_covariance_1,
        orientation_covariance_2,
        orientation_covariance_3,
        orientation_covariance_4,
        orientation_covariance_5,
        orientation_covariance_6,
        orientation_covariance_7,
        orientation_covariance_8
    };
  }

  public double[] getAngularVelocityCovariance() {
    return new double[] {
        angular_velocity_covariance_0,
        angular_velocity_covariance_1,
        angular_velocity_covariance_2,
        angular_velocity_covariance_3,
        angular_velocity_covariance_4,
        angular_velocity_covariance_5,
        angular_velocity_covariance_6,
        angular_velocity_covariance_7,
        angular_velocity_covariance_8
    };
  }

  public double[] getLinearAccelerationCovariance() {
    return new double[] {
        linear_acceleration_covariance_0,
        linear_acceleration_covariance_1,
        linear_acceleration_covariance_2,
        linear_acceleration_covariance_3,
        linear_acceleration_covariance_4,
        linear_acceleration_covariance_5,
        linear_acceleration_covariance_6,
        linear_acceleration_covariance_7,
        linear_acceleration_covariance_8
    };
  }
}