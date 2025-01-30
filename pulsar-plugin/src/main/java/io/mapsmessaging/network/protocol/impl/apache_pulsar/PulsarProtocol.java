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

import io.mapsmessaging.api.*;
import io.mapsmessaging.api.features.ClientAcknowledgement;
import io.mapsmessaging.api.features.DestinationType;
import io.mapsmessaging.api.features.QualityOfService;
import io.mapsmessaging.api.message.TypedData;
import io.mapsmessaging.api.transformers.Transformer;
import io.mapsmessaging.dto.rest.protocol.ProtocolInformationDTO;
import io.mapsmessaging.engine.session.ClientConnection;
import io.mapsmessaging.logging.Logger;
import io.mapsmessaging.logging.LoggerFactory;
import io.mapsmessaging.network.EndPointURL;
import io.mapsmessaging.network.io.EndPoint;
import io.mapsmessaging.network.io.Packet;
import io.mapsmessaging.network.protocol.Protocol;
import io.mapsmessaging.selector.operators.ParserExecutor;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;
import org.apache.pulsar.client.api.*;
import org.apache.pulsar.client.api.MessageListener;
import org.jetbrains.annotations.Nullable;


import javax.security.auth.Subject;
import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


public class PulsarProtocol extends Protocol implements MessageListener<byte[]>, ClientConnection {

  private final Map<String, String> nameMapping;
  private final Logger logger;
  private Session session;
  private boolean closed;
  private String sessionId;
  private final PulsarClient client;

  private final Map<String, Producer<byte[]>> producers;
  private final Map<String, Consumer<byte[]>> consumers;
  private final EndPointURL endPointURL;

  public PulsarProtocol(@NonNull @NotNull EndPoint endPoint) throws PulsarClientException {
    super(endPoint);
    endPointURL = new EndPointURL(endPoint.getConfig().getUrl());
    client = PulsarClient.builder()
        .serviceUrl("pulsar://"+endPointURL.getHost()+":"+endPointURL.getPort())
        .build();
    logger = LoggerFactory.getLogger(PulsarProtocol.class);
    closed = false;
    nameMapping = new ConcurrentHashMap<>();
    logger.log(PulsarLogMessages.INITIALISE_PULSAR_ENDPOINT, endPointURL.toString());
    producers = new LinkedHashMap<>();
    consumers = new LinkedHashMap<>();
  }

  @Override
  public Subject getSubject() {
    return (session != null) ? session.getSecurityContext().getSubject() : null;
  }

  @Override
  public void close() throws IOException {
    if (!closed) {
      closed = true;
      SessionManager.getInstance().close(session, true);
      super.close();
      logger.log(PulsarLogMessages.CLOSE_PULSAR_ENDPOINT);
    }
  }

  @Override
  public void sendMessage(@org.jetbrains.annotations.NotNull @NonNull MessageEvent messageEvent) {

    String lookup = nameMapping.get(messageEvent.getDestinationName());
    if(lookup != null){
      try {
        Producer<byte[]> producer = producers.get(messageEvent.getDestinationName());
        if(producer != null) {
          producer.send(messageEvent.getMessage().getOpaqueData());
        }
        messageEvent.getCompletionTask().run();
        logger.log(PulsarLogMessages.PULSAR_SEND_MESSAGE, messageEvent.getDestinationName());
      } catch (IOException ioException) {
        logger.log(PulsarLogMessages.PULSAR_FAILED_TO_SEND_MESSAGE,  messageEvent.getDestinationName(), ioException);
      }
    }
  }

  @Override
  public void connect(String sessionId, String username, String password) throws IOException{
    SessionContextBuilder scb = new SessionContextBuilder(sessionId, this);
    scb.setUsername(username);
    scb.setPassword(password.toCharArray());
    scb.setPersistentSession(true);
    try {
      session = SessionManager.getInstance().create(scb.build(), this);
    } catch (LoginException e) {
      logger.log(PulsarLogMessages.PULSAR_SESSION_CREATION_ERROR, e);
      IOException ioException = new IOException();
      e.initCause(e);
      throw ioException;
    }
    setConnected(true);
    this.sessionId = sessionId;
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
    SubscriptionContextBuilder scb = new SubscriptionContextBuilder(resource, ClientAcknowledgement.AUTO);
    scb.setAlias(resource);
    ClientAcknowledgement ackManger = QualityOfService.AT_MOST_ONCE.getClientAcknowledgement();
    SubscriptionContextBuilder builder = new SubscriptionContextBuilder(resource, ackManger);
    builder.setQos(QualityOfService.AT_MOST_ONCE);
    builder.setAllowOverlap(true);
    builder.setReceiveMaximum(1024);
    if(selector != null && !selector.isEmpty()) {
      builder.setSelector(selector);
    }
    session.addSubscription(builder.build());
    session.resumeState();
    logger.log(PulsarLogMessages.PULSAR_SUBSCRIBE_LOCAL_SUCCESS, resource, mappedResource);
  }

  @Override
  public ProtocolInformationDTO getInformation() {
    ProtocolInformationDTO dto = new ProtocolInformationDTO();
    dto.setSessionId(sessionId);
    dto.setMessageTransformationName(nameMapping.get(sessionId));
    dto.setType("pulsar");
    return null;
  }

  @Override
  public boolean processPacket(@NonNull @NotNull Packet packet) throws IOException {
    return false;
  }

  @Override
  public String getName() {
    return "LocalLoop";
  }

  @Override
  public String getSessionId() {
    return sessionId;
  }

  @Override
  public String getVersion() {
    return "1.0";
  }

  @Override
  public Principal getPrincipal() {
    return null;
  }

  @Override
  public String getAuthenticationConfig() {
    return endPoint.getAuthenticationConfig();
  }

  @Override
  public String getUniqueName() {
    return endPointURL.toString();
  }

  @Override
  public void received(Consumer<byte[]> consumer, org.apache.pulsar.client.api.Message<byte[]> message) {
    //------------------------------------------------------------------
    // Convert the properties in the Pulsar message to a base map
    Map<String, TypedData> dataMap = new LinkedHashMap<>();
    for(Map.Entry<String, String> entry:message.getProperties().entrySet()) {
      dataMap.put(entry.getKey(), new TypedData(entry.getValue()));
    }

    // Create a MapsMessage
    MessageBuilder mb = new MessageBuilder();
    mb.setOpaqueData(message.getData())
        .setDataMap(dataMap)
        .setCreation(message.getEventTime());
    // Add whatever other mapping you need here

    //------------------------------------------------------------------
    // Based on a match or not then do something
    String topicName = nameMapping.get(message.getTopicName());
    if (topicName != null) {
      try {
        Destination destination = session.findDestination(topicName, DestinationType.TOPIC).get();
        if (destination != null) {
          destination.storeMessage(mb.build());
        }
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
