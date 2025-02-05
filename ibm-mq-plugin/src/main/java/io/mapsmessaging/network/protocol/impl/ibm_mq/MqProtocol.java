package io.mapsmessaging.network.protocol.impl.ibm_mq;

import io.mapsmessaging.logging.Logger;
import io.mapsmessaging.logging.LoggerFactory;
import io.mapsmessaging.network.EndPointURL;
import io.mapsmessaging.network.io.EndPoint;
import io.mapsmessaging.network.protocol.impl.plugin.Plugin;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;
import org.jetbrains.annotations.Nullable;

import com.ibm.mq.*;
import com.ibm.mq.constants.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public class MqProtocol extends Plugin {

  private final Logger logger;
  private final EndPointURL url;
  private MQQueueManager queueManager;
  private final Map<String, MQQueue> producers;
  private final Map<String, MQQueue> consumers;

  public MqProtocol(@NonNull @NotNull EndPoint endPoint) {
    url = new EndPointURL(endPoint.getConfig().getUrl());
    logger = LoggerFactory.getLogger(MqProtocol.class);
    producers = new LinkedHashMap<>();
    consumers = new LinkedHashMap<>();
  }

  @Override
  public void close() throws IOException {
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
  public void initialise() {
    try {
      queueManager = new MQQueueManager(url.getHost());
      logger.log(MqLogMessages.MQ_INITIALIZED, url.getHost());
    } catch (MQException e) {
      logger.log(MqLogMessages.MQ_INITIALIZE_ERROR, e);
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
  public void outbound(@NonNull @NotNull String destinationName, @NonNull @NotNull byte[] data, Map<String, Object> map) {
    try {
      MQQueue queue = producers.get(destinationName);
      if (queue != null) {
        MQMessage message = new MQMessage();
        message.write(data);
        queue.put(message);
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

  public void pollMessages(@NotNull String destination) {
    try {
      MQQueue queue = consumers.get(destination);
      if (queue != null) {
        MQMessage message = new MQMessage();
        MQGetMessageOptions gmo = new MQGetMessageOptions();
        gmo.options = CMQC.MQGMO_WAIT;
        queue.get(message, gmo);
        byte[] data = new byte[message.getDataLength()];
        message.readFully(data);
        inbound(destination, data, null);
      }
    } catch (MQException | IOException e) {
      logger.log(MqLogMessages.MQ_POLL_ERROR, destination, e);
    }
  }
}
