# V2X STEP Extension Development History

## Unit Test Fixes - Date: 2025-11-26

### Changes Summary
Fixed unit tests in V2xStepProtocolOutboundTest to match the latest extension code implementation.

### Issue Identified
Tests were failing because they called `outbound()` with the wrong parameter:
- **How bindings are stored**: Push bindings stored using `local_namespace` (e.g., `/v2x/outbound/denm`) as map key (V2xStepProtocol.java:351)
- **How bindings are retrieved**: `outbound()` method looks up bindings using the `destination` parameter (V2xStepProtocol.java:222)
- **Test mismatch**: Tests called `outbound("DENM_TX_GROUP", message)` with `remote_namespace`, but binding stored with `local_namespace`

### Files Modified
1. **V2xStepProtocolOutboundTest.java**
   - Updated 5 test methods to call `outbound()` with `local_namespace` instead of `remote_namespace`:
     - `testOutbound_DenmMessage_FromDataMap_Success()` - line 85
     - `testOutbound_DenmMessage_FromXmlPayload_Success()` - line 134
     - `testOutbound_SdkError_LogsError()` - line 186
     - `testOutbound_MissingPayloadData_LogsError()` - line 201
     - `testRegisterLocalLink_PerLinkPublishGroupOverride()` - line 308
   - Changed from: `protocol.outbound("DENM_TX_GROUP", message)`
   - Changed to: `protocol.outbound("/v2x/outbound/denm", message)`

### Test Results
✅ All 9 tests passing in v2x-step-extension
✅ Build successful for all modules (mvn clean test)

### Key Insight
The `outbound()` method is called by MapsMessaging with the `local_namespace` (topic name), not the `remote_namespace` (STEP publish group). The extension internally maps from local topic to remote STEP destination via the PushBinding.

---

## Debug Logging Enhancement - Date: 2025-11-24

### Changes Summary
Added comprehensive debug logging to the V2X STEP extension to track message routing issues, particularly to diagnose why outbound sends may not be triggered even when links are configured.

### Files Modified

1. **V2xStepLogMessages.java**
   - Added 23 new debug log messages organized into categories:
     - Initialization and lifecycle (9 messages)
     - Link registration (7 messages)
     - Outbound message routing (10 messages)
   - All use LEVEL.DEBUG except warnings/errors

2. **V2xStepProtocol.java**
   - Constructor: Added debug logging for URL and configuration
   - initialise(): Added logging for STEP instance, DENM config, SDK startup, and service state
   - registerLocalLink(): Added comprehensive logging for link attributes, service type, publish group, and field mapping
   - outbound(): **CRITICAL** - Added detailed logging including:
     - Push bindings count and registered destinations
     - Binding lookup results
     - Message payload size
     - Parameter extraction process
     - DENM parameters
     - SDK trigger invocation
   - Removed all System.out.println statements

3. **V2xStepProtocolFactory.java**
   - Removed System.out.println from static initializer

### Key Debug Logs for Troubleshooting

**To diagnose "send not triggered" issues:**
- `V2X_STEP_OUTBOUND_CALLED` - Confirms outbound() was invoked
- `V2X_STEP_OUTBOUND_BINDINGS_COUNT` - Shows registered destinations
- `V2X_STEP_OUTBOUND_BINDING_LOOKUP` - Shows if destination matched

**To diagnose link registration issues:**
- `V2X_STEP_REGISTER_LOCAL_START` - Confirms registerLocalLink() called
- `V2X_STEP_REGISTER_LOCAL_ATTRS` - Shows link configuration found
- `V2X_STEP_REGISTER_LOCAL_SUCCESS` - Confirms binding registered

### Build Status
✅ Successfully compiled with `mvn clean compile -pl v2x-step-extension`

### Usage
Set logging level to DEBUG for package: `io.mapsmessaging.network.protocol.impl.v2x_step`

