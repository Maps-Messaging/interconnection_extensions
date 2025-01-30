/*
 * Copyright [ 2020 - 2024 ] [Matthew Buckton]
 * Copyright [ 2024 - 2025 ] [Maps Messaging B.V.]
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package io.mapsmessaging.network.protocol.impl.apache_pulsar;

import io.mapsmessaging.api.transformers.Transformer;
import io.mapsmessaging.logging.Logger;
import io.mapsmessaging.logging.LoggerFactory;
import io.mapsmessaging.network.EndPointURL;
import io.mapsmessaging.network.io.EndPoint;
import io.mapsmessaging.network.protocol.impl.plugin.PluginProtocol;
import io.mapsmessaging.selector.operators.ParserExecutor;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;
import org.apache.pulsar.client.api.*;
import org.apache.pulsar.client.api.MessageListener;
import org.jetbrains.annotations.Nullable;


import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class PulsarProtocol extends PluginProtocol implements MessageListener<byte[]> {

  private final Map<String, String> nameMapping;
  private final Logger logger;
  private final PulsarClient client;

  private final Map<String, Producer<byte[]>> producers;
  private final Map<String, Consumer<byte[]>> consumers;

  public PulsarProtocol(@NonNull @NotNull EndPoint endPoint) throws PulsarClientException {
    super(endPoint);
    EndPointURL url = new EndPointURL(endPoint.getConfig().getUrl());
    client = PulsarClient.builder()
        .serviceUrl("pulsar://"+url.getHost()+":"+url.getPort())
        .build();
    logger = LoggerFactory.getLogger(PulsarProtocol.class);
    nameMapping = new ConcurrentHashMap<>();
    logger.log(PulsarLogMessages.INITIALISE_PULSAR_ENDPOINT, url.toString());
    producers = new LinkedHashMap<>();
    consumers = new LinkedHashMap<>();
  }

  @Override
  public void close() throws IOException {
    super.close();
    client.close();
  }

  @Override
  public void forwardMessage(String destinationName, byte[] data, Map<String, Object> map) {
    String lookup = nameMapping.get(destinationName);
    if(lookup != null) {
      try {
        Producer<byte[]> producer = producers.get(destinationName);
        if(producer != null) {
          producer.send(data);
        }
        logger.log(PulsarLogMessages.PULSAR_SEND_MESSAGE, destinationName);
      } catch (IOException ioException) {
        logger.log(PulsarLogMessages.PULSAR_FAILED_TO_SEND_MESSAGE, destinationName, ioException);
      }
    }
  }

  @Override
  public void connect(String sessionId, String username, String password) throws IOException{
    try {
      doConnect(sessionId,username,password);
    } catch (LoginException e) {
      logger.log(PulsarLogMessages.PULSAR_SESSION_CREATION_ERROR, e);
      IOException ioException = new IOException();
      e.initCause(e);
      throw ioException;
    }
  }

  @Override
  public void subscribeRemote(@NonNull @org.jetbrains.annotations.NotNull String resource, @NonNull @org.jetbrains.annotations.NotNull String mappedResource, @Nullable ParserExecutor parser, @Nullable Transformer transformer) throws IOException {
    nameMapping.put(resource, mappedResource);
    consumers.put(resource, client.newConsumer()
        .subscriptionName(sessionId)
        .topic(resource)
        .messageListener(this)
        .subscribe());
    logger.log(PulsarLogMessages.PULSAR_SUBSCRIBE_REMOTE_SUCCESS, resource, mappedResource);
  }

  @Override
  public void subscribeLocal(@NonNull @NotNull String resource, @NonNull @NotNull String mappedResource, String selector, @Nullable Transformer transformer) throws IOException {
    if(transformer != null) {
      destinationTransformerMap.put(resource, transformer);
    }
    nameMapping.put(resource, mappedResource);
    producers.put(resource, client.newProducer()
        .topic(resource)
        .producerName(sessionId)
        .create());
    super.subscribeLocal(selector, resource, mappedResource, transformer);
    logger.log(PulsarLogMessages.PULSAR_SUBSCRIBE_LOCAL_SUCCESS, resource, mappedResource);
  }

  @Override
  public void received(Consumer<byte[]> consumer, org.apache.pulsar.client.api.Message<byte[]> message) {
    String topicName = nameMapping.get(message.getTopicName());
    if (topicName != null) {
      try {
        saveMessage(topicName, message.getData(), MapConverter.convertMap(message.getProperties()));
        consumer.acknowledge(message);
      } catch (Throwable ioException) {
        logger.log(PulsarLogMessages.PULSAR_FAILED_TO_PROCESS_INCOMING_EVENT, topicName, ioException);
      }
    }
  }

  @Override
  public void reachedEndOfTopic(Consumer<byte[]> consumer) {
    // This is called via pulsar, no action required here, for now
  }

}
