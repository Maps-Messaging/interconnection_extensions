package io.mapsmessaging.network.protocol.impl.v2x_step;

import com.vodafone.v2xsdk4javav2.facade.events.BaseEvent;
import com.vodafone.v2xsdk4javav2.facade.events.EventListener;
import com.vodafone.v2xsdk4javav2.facade.events.EventType;
import com.vodafone.v2xsdk4javav2.facade.events.EventDenmListChanged;
import com.vodafone.v2xsdk4javav2.facade.records.denm.DENMRecord;
import io.mapsmessaging.logging.Logger;
import io.mapsmessaging.logging.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Event handler for DENM subscriptions from the V2X SDK.
 * This class listens for DENM_LIST_CHANGED events from the SDK and routes
 * received DENMs to configured MapsMessaging topics.
 */
public class DenmEventHandler implements EventListener {

  private final Logger logger;
  private final Map<String, PullBinding> pullBindings;
  private final BiConsumer<String, DENMRecord> messageCallback;
  private long ownStationId = -1;  // Track own station ID to filter echoes

  /**
   * Create a new DENM event handler.
   *
   * @param messageCallback Callback invoked when a DENM is received: (destination, denmRecord) -> void
   */
  public DenmEventHandler(BiConsumer<String, DENMRecord> messageCallback) {
    this.logger = LoggerFactory.getLogger(DenmEventHandler.class);
    this.pullBindings = new ConcurrentHashMap<>();
    this.messageCallback = messageCallback;
    logger.log(V2xStepLogMessages.V2X_STEP_INITIALIZED, "DENM event handler created");
  }

  /**
   * Register a pull binding for routing received DENMs to a MAPS topic.
   *
   * @param destination The MapsMessaging topic to publish received DENMs to
   * @param binding The pull binding configuration
   */
  public void registerPullBinding(String destination, PullBinding binding) {
    pullBindings.put(destination, binding);
    logger.log(V2xStepLogMessages.V2X_STEP_INITIALIZED,
        "Registered pull binding for destination: " + destination);
  }

  /**
   * Remove a pull binding.
   *
   * @param destination The destination to unregister
   */
  public void unregisterPullBinding(String destination) {
    pullBindings.remove(destination);
    logger.log(V2xStepLogMessages.V2X_STEP_INITIALIZED,
        "Unregistered pull binding for destination: " + destination);
  }

  @Override
  public void onMessageBusEvent(BaseEvent baseEvent) {
    if (baseEvent.getEventType() != EventType.DENM_LIST_CHANGED) {
      return; // Not a DENM event, ignore
    }

    EventDenmListChanged event = (EventDenmListChanged) baseEvent;

    for (DENMRecord denm : event.getList()) {
      // Detect own station ID from first message
      if (ownStationId == -1) {
        ownStationId = denm.getOriginatorID();
        logger.log(V2xStepLogMessages.V2X_STEP_INITIALIZED,
            "Detected own Station ID: " + ownStationId);
      }

      // Check if this is our own message (echo)
      boolean isOwnMessage = (denm.getOriginatorID() == ownStationId);
      String origin = isOwnMessage ? "[OWN]" : "[OTHER]";

      logger.log(V2xStepLogMessages.V2X_STEP_INBOUND_DENM_RECEIVED,
          denm.getOriginatorID(), denm.getSequenceNumber(),
          denm.getCauseCode(), denm.getSubCauseCode());

      // Route to all registered pull bindings
      for (Map.Entry<String, PullBinding> entry : pullBindings.entrySet()) {
        String destination = entry.getKey();
        PullBinding binding = entry.getValue();

        // Filter own messages if configured
        if (binding.isFilterOwnMessages() && isOwnMessage) {
          logger.log(V2xStepLogMessages.V2X_STEP_INBOUND_FILTERING_OWN,
              denm.getOriginatorID());
          continue;
        }

        // Invoke callback to route message to MAPS
        try {
          messageCallback.accept(destination, denm);
          logger.log(V2xStepLogMessages.V2X_STEP_INBOUND_SUCCESS,
              destination, 0); // Size will be logged by handleInboundDenm
        } catch (Exception e) {
          logger.log(V2xStepLogMessages.V2X_STEP_INBOUND_ERROR,
              destination, e.getMessage());
        }
      }
    }
  }
}
