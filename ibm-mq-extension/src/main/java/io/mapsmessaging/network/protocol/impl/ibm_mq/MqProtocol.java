/*
 *
 *  Copyright [ 2020 - 2024 ] [Matthew Buckton]
 *  Copyright [ 2024 - 2025 ] [Maps Messaging B.V.]
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package io.mapsmessaging.network.protocol.impl.ibm_mq;

import io.mapsmessaging.api.MessageBuilder;
import io.mapsmessaging.api.message.Message;
import io.mapsmessaging.api.message.TypedData;
import io.mapsmessaging.dto.rest.config.protocol.impl.ExtensionConfigDTO;
import io.mapsmessaging.logging.Logger;
import io.mapsmessaging.logging.LoggerFactory;
import io.mapsmessaging.network.EndPointURL;
import io.mapsmessaging.network.io.EndPoint;
import io.mapsmessaging.network.protocol.impl.extension.Extension;
import io.mapsmessaging.utilities.threads.SimpleTaskScheduler;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;
import org.jetbrains.annotations.Nullable;

import com.ibm.mq.*;
import com.ibm.mq.constants.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class MqProtocol extends Extension {

  private MQQueueManager queueManager;

  private final ScheduledFuture<?> scheduledTask;
  private final Logger logger;
  private final EndPointURL url;
  private final ExtensionConfigDTO protocolConfig;
  private final Map<String, MQQueue> producers;
  private final Map<String, MQQueue> consumers;

  public MqProtocol(@NonNull @NotNull EndPoint endPoint, ExtensionConfigDTO protocolConfigDTO) {
    protocolConfig = protocolConfigDTO;
    url = new EndPointURL(endPoint.getConfig().getUrl());
    logger = LoggerFactory.getLogger(MqProtocol.class);
    producers = new LinkedHashMap<>();
    consumers = new LinkedHashMap<>();
    scheduledTask = SimpleTaskScheduler.getInstance().scheduleAtFixedRate(new ScheduleRunner(), 1, 1, TimeUnit.SECONDS);
  }

  @Override
  public void close() throws IOException {
    if(scheduledTask != null) {
      scheduledTask.cancel(true);
    }
    try {
      for (MQQueue consumer : consumers.values()) {
        consumer.close();
      }
      for (MQQueue producer : producers.values()) {
        producer.close();
      }
      if (queueManager != null) queueManager.disconnect();
    } catch (MQException e) {
      logger.log(MqLogMessages.MQ_CLOSE_ERROR, e);
    }
    super.close();
  }

  @Override
  public void initialise() throws IOException {
    try {
      Map<String, Object> config = protocolConfig.getConfig();
      config.put(CMQC.HOST_NAME_PROPERTY, url.getHost());
      Hashtable<String, Object> hash = new Hashtable<>();
      hash.putAll(config);
      String queueManagerName = (String)protocolConfig.getConfig().get("queueManager");
      queueManager = new MQQueueManager(queueManagerName, hash);
      logger.log(MqLogMessages.MQ_INITIALIZED, url.getHost());
    } catch (MQException e) {
      logger.log(MqLogMessages.MQ_INITIALIZE_ERROR, e);
      this.close();
      throw new IOException(e.getMessage(), e);
    }
  }

  @Override
  public @NonNull String getName() {
    return "MqProtocol";
  }

  @Override
  public String getVersion() {
    return "1.0";
  }

  @Override
  public boolean supportsRemoteFiltering() {
    return false; // Native MQ API does not support selectors like JMS
  }

  @Override
  public void outbound(@NonNull @NotNull String destinationName, @NonNull @NotNull Message message) {
    try {
      MQQueue queue = producers.get(destinationName);
      if (queue != null) {
        MQMessage mqMessage = new MQMessage();
        mqMessage.write(message.getOpaqueData());
        if(message.getCorrelationData() != null) {
          mqMessage.correlationId = message.getCorrelationData();
        }
        if(message.getContentType() != null) {
          mqMessage.format = message.getContentType();
        }
        for(Map.Entry<String, TypedData> entry:message.getDataMap().entrySet()) {
          switch(entry.getValue().getType()){
            case STRING:
              mqMessage.setStringProperty(entry.getKey(), (String)entry.getValue().getData());
              break;

            case INT:
              mqMessage.setIntProperty(entry.getKey(), (Integer)entry.getValue().getData());
              break;

            case LONG:
              mqMessage.setLongProperty(entry.getKey(), (Long)entry.getValue().getData());
              break;

            case FLOAT:
              mqMessage.setFloatProperty(entry.getKey(), (Float)entry.getValue().getData());
              break;

            case DOUBLE:
              mqMessage.setDoubleProperty(entry.getKey(), (Double)entry.getValue().getData());
              break;

            case BOOLEAN:
              mqMessage.setBooleanProperty(entry.getKey(), (Boolean)entry.getValue().getData());
              break;

            case SHORT:
              mqMessage.setShortProperty(entry.getKey(), (Short)entry.getValue().getData());
              break;

            case BYTE:
              mqMessage.setByteProperty(entry.getKey(), (Byte)entry.getValue().getData());
              break;

            case CHAR:
              mqMessage.setStringProperty(entry.getKey(), (String)entry.getValue().getData());
              break;

              default:
                break;
          }
        }
        queue.put(mqMessage);
        logger.log(MqLogMessages.MQ_MESSAGE_SENT, destinationName);
      } else {
        logger.log(MqLogMessages.MQ_PRODUCER_NOT_FOUND, destinationName);
      }
    } catch (MQException | IOException e) {
      logger.log(MqLogMessages.MQ_SEND_ERROR, destinationName, e);
    }
  }

  @Override
  public void registerRemoteLink(@NotNull @NotNull String destination, @Nullable String filter) throws IOException {
    try {
      MQQueue queue = queueManager.accessQueue(destination, CMQC.MQOO_INPUT_AS_Q_DEF);
      consumers.put(destination, queue);
      logger.log(MqLogMessages.MQ_SUBSCRIBE_REMOTE_SUCCESS, destination);
    } catch (MQException e) {
      throw new IOException("Error registering remote MQ link", e);
    }
  }

  @Override
  public void registerLocalLink(@NonNull @NotNull String destination) throws IOException {
    try {
      MQQueue queue = queueManager.accessQueue(destination, CMQC.MQOO_OUTPUT);
      producers.put(destination, queue);
      logger.log(MqLogMessages.MQ_SUBSCRIBE_LOCAL_SUCCESS, destination);
    } catch (MQException e) {
      throw new IOException("Error registering local MQ link", e);
    }
  }

  public void pollMessages(@NotNull String destination,  @NotNull MQQueue queue ) {
    try {
      MQMessage message = new MQMessage();
      MQGetMessageOptions gmo = new MQGetMessageOptions();
      gmo.options = CMQC.MQGMO_NO_WAIT;
      queue.get(message, gmo);
      byte[] data = new byte[message.getDataLength()];
      message.readFully(data);
      MessageBuilder messageBuilder = new MessageBuilder();
      messageBuilder.setCorrelationData(message.correlationId);
      messageBuilder.setExpiry(message.expiry);
      messageBuilder.setContentType(message.format);

      Enumeration<String> propertyNames = message.getPropertyNames("%");
      Map<String, TypedData> map = new LinkedHashMap<>();
      while(propertyNames.hasMoreElements()) {
        String propertyName = propertyNames.nextElement();
        Object obj = message.getObjectProperty(propertyName);
        map.put(propertyName, new TypedData(obj));
      }
      messageBuilder.setDataMap(map);


      inbound(destination, messageBuilder.build());
    } catch (MQException | IOException e) {
//      logger.log(MqLogMessages.MQ_POLL_ERROR, destination, e);
    }
  }



  private final class ScheduleRunner implements Runnable {

    @Override
    public void run() {
      for (Map.Entry<String, MQQueue> entry:consumers.entrySet()) {
        pollMessages(entry.getKey(), entry.getValue());
      }
    }
  }
}
