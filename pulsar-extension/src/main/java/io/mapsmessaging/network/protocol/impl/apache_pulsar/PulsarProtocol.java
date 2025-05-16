/*
 *  Copyright [ 2020 - 2024 ] Matthew Buckton
 *  Copyright [ 2024 - 2025 ] MapsMessaging B.V.
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
package io.mapsmessaging.network.protocol.impl.apache_pulsar;

import io.mapsmessaging.api.MessageBuilder;
import io.mapsmessaging.logging.Logger;
import io.mapsmessaging.logging.LoggerFactory;
import io.mapsmessaging.network.EndPointURL;
import io.mapsmessaging.network.io.EndPoint;
import io.mapsmessaging.network.protocol.impl.extension.Extension;
import io.mapsmessaging.dto.rest.config.protocol.impl.ExtensionConfigDTO;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;
import org.apache.pulsar.client.api.*;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;


public class PulsarProtocol extends Extension {

  private final Logger logger;
  private final EndPointURL url;
  private PulsarClient client;

  private final Map<String, Producer<byte[]>> producers;
  private final Map<String, Consumer<byte[]>> consumers;

  public PulsarProtocol(@NonNull @NotNull EndPoint endPoint, ExtensionConfigDTO protocolConfigDTO) {
    url = new EndPointURL(endPoint.getConfig().getUrl());
    logger = LoggerFactory.getLogger(PulsarProtocol.class);
    logger.log(PulsarLogMessages.INITIALISE_PULSAR_ENDPOINT, url.toString());
    producers = new LinkedHashMap<>();
    consumers = new LinkedHashMap<>();
  }

  @Override
  public void close() throws IOException {
    for(Consumer<byte[]> consumer : consumers.values()) {
      consumer.close();
    }
    for(Producer<byte[]> producer : producers.values()) {
      producer.close();
    }
    client.close();
    super.close();
  }

  /**
   * Called when the plugin has connected locally to the messaging engine and is ready to process requests
   */
  @Override
  public void initialise(){
    try {
      client = PulsarClient.builder()
          .serviceUrl("pulsar://" + url.getHost() + ":" + url.getPort())
          .build();
    }
    catch (PulsarClientException e) {
      logger.log(PulsarLogMessages.PULSAR_SESSION_CREATION_ERROR, e);
    }
  }

  @Override
  public @NonNull String getName() {
    return "PulsarProtocol";
  }

  @Override
  public String getVersion() {
    return "1.0";
  }

  @Override
  public boolean supportsRemoteFiltering() {
    return false;
  }

  /**
   * This is called when the configuration requires events to be pulled from the pulsar server.
   * @param destination The destination to subscribe to
   * @param filter If filtering is supported then the filter will contain a JMS style selector
   * @throws IOException
   */
  @Override
  public void registerRemoteLink(@NotNull @NotNull String destination, @Nullable String filter) throws IOException {
    consumers.put(destination, client.newConsumer()
        .subscriptionName(getSessionId())
        .topic(destination)
        .messageListener(new MessageListenerHandler())
        .subscribe());
    logger.log(PulsarLogMessages.PULSAR_SUBSCRIBE_REMOTE_SUCCESS, destination);
  }


  /**
   * Before any events are passed from the MAPS server this will be called to indicate that the configuration has
   * a local destination(s) to be sent to a remote Pulsar server. So whatever needs to happen to facilitate that happens here
   *
   * @param destination The name of the destination ( it could be a MQTT wild card subscription )
   * @throws IOException
   */
  @Override
  public void registerLocalLink(@NonNull @NotNull String destination) throws IOException{
    producers.put(destination, client.newProducer()
        .topic(destination)
        .producerName(getSessionId())
        .create());
    logger.log(PulsarLogMessages.PULSAR_SUBSCRIBE_LOCAL_SUCCESS, destination);
  }


  /**
   * Handle message coming from the MAPS server destined to the remote name
   * @param destinationName Fully Qualified remote name
   * @param message io.mapsmessaging.api.message.Message containing the data to send
   */
  @Override
  public void outbound(@NonNull @NotNull String destinationName, @NonNull @NotNull io.mapsmessaging.api.message.Message message) {
    try {
      Producer<byte[]> producer = producers.get(destinationName);
      if(producer != null) {
        producer.send(message.getOpaqueData());
      }
      logger.log(PulsarLogMessages.PULSAR_SEND_MESSAGE, destinationName);
    } catch (IOException ioException) {
      logger.log(PulsarLogMessages.PULSAR_FAILED_TO_SEND_MESSAGE, destinationName, ioException);
    }
  }




  private class MessageListenerHandler implements MessageListener<byte[]>{

    @Override
    public void received(Consumer<byte[]> consumer, Message<byte[]> message) {
      try {
        MessageBuilder messageBuilder = new MessageBuilder()
            .setOpaqueData(message.getData())
                .setDataMap(MapConverter.convertMap(message.getProperties()));
        inbound(message.getTopicName(), messageBuilder.build());
        consumer.acknowledge(message);
      } catch (Throwable ioException) {
        logger.log(PulsarLogMessages.PULSAR_FAILED_TO_PROCESS_INCOMING_EVENT, message.getTopicName(), ioException);
      }

    }

    @Override
    public void reachedEndOfTopic(Consumer<byte[]> consumer) {
      // This is called via pulsar, no action required here, for now
    }
  }

}
