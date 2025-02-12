package io.mapsmessaging.network.protocol.impl.aws_sns;


import io.mapsmessaging.api.message.Message;
import io.mapsmessaging.dto.rest.config.protocol.impl.PluginConfigDTO;
import io.mapsmessaging.logging.Logger;
import io.mapsmessaging.logging.LoggerFactory;
import io.mapsmessaging.network.io.EndPoint;
import io.mapsmessaging.network.protocol.impl.plugin.Plugin;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class SnsProtocol extends Plugin {

  private final Logger logger;
  private final PluginConfigDTO protocolConfig;
  private final SnsClient snsClient;
  private final String topicArn;
  private final Map<String, String> subscriptions;

  public SnsProtocol(EndPoint endPoint, PluginConfigDTO protocolConfigDTO) {
    this.protocolConfig = protocolConfigDTO;
    this.logger = LoggerFactory.getLogger(SnsProtocol.class);
    this.snsClient = SnsClient.create();
    this.topicArn = (String) protocolConfigDTO.getConfig().getOrDefault("topicArn", "");
    this.subscriptions = new HashMap<>();
  }

  @Override
  public void close() throws IOException {
    try {
      snsClient.close();
      logger.log(SnsLogMessages.SNS_INITIALIZED);
    } catch (Exception e) {
      logger.log(SnsLogMessages.SNS_CLOSE_ERROR, e);
    }
    super.close();
  }

  @Override
  public void initialise() throws IOException {
    try {
      logger.log(SnsLogMessages.SNS_INITIALIZED, topicArn);
    } catch (Exception e) {
      logger.log(SnsLogMessages.SNS_INITIALIZE_ERROR, e);
      throw new IOException("Error initializing SNS", e);
    }
  }

  @Override
  public String getName() {
    return "SnsProtocol";
  }

  @Override
  public String getVersion() {
    return "1.0";
  }

  @Override
  public boolean supportsRemoteFiltering() {
    return false; // SNS does not support direct filtering like EventBridge
  }

  @Override
  public void outbound(String destination, Message message) {
    try {
      PublishRequest request = PublishRequest.builder()
          .topicArn(topicArn)
          .message(new String(message.getOpaqueData()))
          .build();

      snsClient.publish(request);
      logger.log(SnsLogMessages.SNS_MESSAGE_SENT, destination);
    } catch (Exception e) {
      logger.log(SnsLogMessages.SNS_SEND_ERROR, destination, e);
    }
  }

  @Override
  public void registerRemoteLink(String destination, String filter) throws IOException {
    try {
      SubscribeRequest subscribeRequest = SubscribeRequest.builder()
          .topicArn(topicArn)
          .protocol("sqs") // Could be "http", "https", "email", "lambda", etc.
          .endpoint(destination) // This should be an SQS queue ARN or HTTP URL
          .build();

      SubscribeResponse response = snsClient.subscribe(subscribeRequest);
      subscriptions.put(destination, response.subscriptionArn());
      logger.log(SnsLogMessages.SNS_SUBSCRIBE_REMOTE_SUCCESS, destination);
    } catch (Exception e) {
      throw new IOException("Error subscribing to SNS topic", e);
    }
  }

  @Override
  public void registerLocalLink(String destination) throws IOException {
    // In SNS, there is no concept of local vs remote subscriptions
    registerRemoteLink(destination, null);
    logger.log(SnsLogMessages.SNS_SUBSCRIBE_LOCAL_SUCCESS, destination);
  }
}
