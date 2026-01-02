# @capgo/capacitor-watch

Apple Watch communication plugin for Capacitor with bidirectional messaging support.

<a href="https://capgo.app/"><img src='https://raw.githubusercontent.com/AntoineDly/Aurehon-frontend/refs/heads/main/public/images/capgo_banner.webp' alt='Capgo - Instant updates for capacitor'/></a>

<div class="dropdown">
  <a href="https://capgo.app/">Capgo</a> — Live updates, build hosting, and analytics for Capacitor apps — no App Store review needed.
</div>

## Features

- *Bidirectional messaging* — Send and receive messages between iPhone and Apple Watch
- *Application context* — Sync app state with latest-value-only semantics
- *User info transfers* — Queue reliable data transfers that deliver even when watch is offline
- *Reply handling* — Respond to watch requests with callback-based replies
- *Reachability monitoring* — Track watch connectivity status in real-time
- *iOS 15+ support* — Built for modern iOS with Swift Package Manager

## Requirements

- iOS 15.0+
- Capacitor 8.0+
- watchOS app with WatchConnectivity integration

> **Note:** This plugin only works on iOS. Android builds will compile but all methods will reject with "Apple Watch is only supported on iOS".

## Installation

```bash
npm install @capgo/capacitor-watch
npx cap sync
```

### iOS Setup

1. Add the Watch Connectivity capability to your iOS app in Xcode
2. Create a watchOS app target in your Xcode project
3. Implement the watch-side connectivity code using WatchConnectivity framework

## API

<docgen-index>

* [`sendMessage(...)`](#sendmessage)
* [`updateApplicationContext(...)`](#updateapplicationcontext)
* [`transferUserInfo(...)`](#transferuserinfo)
* [`replyToMessage(...)`](#replytomessage)
* [`getInfo()`](#getinfo)
* [`getPluginVersion()`](#getpluginversion)
* [`addListener('messageReceived', ...)`](#addlistenermessagereceived-)
* [`addListener('messageReceivedWithReply', ...)`](#addlistenermessagereceivedwithreply-)
* [`addListener('applicationContextReceived', ...)`](#addlistenerapplicationcontextreceived-)
* [`addListener('userInfoReceived', ...)`](#addlisteneruserinforeceived-)
* [`addListener('reachabilityChanged', ...)`](#addlistenerreachabilitychanged-)
* [`addListener('activationStateChanged', ...)`](#addlisteneractivationstatechanged-)
* [`removeAllListeners()`](#removealllisteners)
* [Interfaces](#interfaces)
* [Type Aliases](#type-aliases)

</docgen-index>

<docgen-api>
<!--Update the source file JSDoc comments and rerun docgen to update the docs below-->

Apple Watch communication plugin for Capacitor.
Provides bidirectional messaging between iPhone and Apple Watch using WatchConnectivity.

### sendMessage(...)

```typescript
sendMessage(options: SendMessageOptions) => Promise<void>
```

Send an interactive message to the watch.
The watch must be reachable for this to succeed.
Use this for time-sensitive, interactive communication.

| Param         | Type                                                              | Description           |
| ------------- | ----------------------------------------------------------------- | --------------------- |
| **`options`** | <code><a href="#sendmessageoptions">SendMessageOptions</a></code> | - The message options |

**Since:** 8.0.0

--------------------


### updateApplicationContext(...)

```typescript
updateApplicationContext(options: UpdateContextOptions) => Promise<void>
```

Update the application context shared with the watch.
Only the latest context is kept - this overwrites any previous context.
Use this for syncing app state that the watch needs to display.

| Param         | Type                                                                  | Description           |
| ------------- | --------------------------------------------------------------------- | --------------------- |
| **`options`** | <code><a href="#updatecontextoptions">UpdateContextOptions</a></code> | - The context options |

**Since:** 8.0.0

--------------------


### transferUserInfo(...)

```typescript
transferUserInfo(options: TransferUserInfoOptions) => Promise<void>
```

Transfer user info to the watch.
Transfers are queued and delivered in order, even if the watch is not currently reachable.
Use this for important data that must be delivered reliably.

| Param         | Type                                                                        | Description             |
| ------------- | --------------------------------------------------------------------------- | ----------------------- |
| **`options`** | <code><a href="#transferuserinfooptions">TransferUserInfoOptions</a></code> | - The user info options |

**Since:** 8.0.0

--------------------


### replyToMessage(...)

```typescript
replyToMessage(options: ReplyMessageOptions) => Promise<void>
```

Reply to a message from the watch that requested a reply.
Use this in response to the messageReceivedWithReply event.

| Param         | Type                                                                | Description                                  |
| ------------- | ------------------------------------------------------------------- | -------------------------------------------- |
| **`options`** | <code><a href="#replymessageoptions">ReplyMessageOptions</a></code> | - The reply options including the callbackId |

**Since:** 8.0.0

--------------------


### getInfo()

```typescript
getInfo() => Promise<WatchInfo>
```

Get information about the watch connectivity status.

**Returns:** <code>Promise&lt;<a href="#watchinfo">WatchInfo</a>&gt;</code>

**Since:** 8.0.0

--------------------


### getPluginVersion()

```typescript
getPluginVersion() => Promise<{ version: string; }>
```

Get the native Capacitor plugin version.

**Returns:** <code>Promise&lt;{ version: string; }&gt;</code>

**Since:** 8.0.0

--------------------


### addListener('messageReceived', ...)

```typescript
addListener(eventName: 'messageReceived', listenerFunc: (event: MessageReceivedEvent) => void) => Promise<PluginListenerHandle>
```

Listen for messages received from the watch.

| Param              | Type                                                                                      | Description         |
| ------------------ | ----------------------------------------------------------------------------------------- | ------------------- |
| **`eventName`**    | <code>'messageReceived'</code>                                                            | - The event name    |
| **`listenerFunc`** | <code>(event: <a href="#messagereceivedevent">MessageReceivedEvent</a>) =&gt; void</code> | - Callback function |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

**Since:** 8.0.0

--------------------


### addListener('messageReceivedWithReply', ...)

```typescript
addListener(eventName: 'messageReceivedWithReply', listenerFunc: (event: MessageReceivedWithReplyEvent) => void) => Promise<PluginListenerHandle>
```

Listen for messages from the watch that require a reply.

| Param              | Type                                                                                                        | Description         |
| ------------------ | ----------------------------------------------------------------------------------------------------------- | ------------------- |
| **`eventName`**    | <code>'messageReceivedWithReply'</code>                                                                     | - The event name    |
| **`listenerFunc`** | <code>(event: <a href="#messagereceivedwithreplyevent">MessageReceivedWithReplyEvent</a>) =&gt; void</code> | - Callback function |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

**Since:** 8.0.0

--------------------


### addListener('applicationContextReceived', ...)

```typescript
addListener(eventName: 'applicationContextReceived', listenerFunc: (event: ContextReceivedEvent) => void) => Promise<PluginListenerHandle>
```

Listen for application context updates from the watch.

| Param              | Type                                                                                      | Description         |
| ------------------ | ----------------------------------------------------------------------------------------- | ------------------- |
| **`eventName`**    | <code>'applicationContextReceived'</code>                                                 | - The event name    |
| **`listenerFunc`** | <code>(event: <a href="#contextreceivedevent">ContextReceivedEvent</a>) =&gt; void</code> | - Callback function |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

**Since:** 8.0.0

--------------------


### addListener('userInfoReceived', ...)

```typescript
addListener(eventName: 'userInfoReceived', listenerFunc: (event: UserInfoReceivedEvent) => void) => Promise<PluginListenerHandle>
```

Listen for user info transfers from the watch.

| Param              | Type                                                                                        | Description         |
| ------------------ | ------------------------------------------------------------------------------------------- | ------------------- |
| **`eventName`**    | <code>'userInfoReceived'</code>                                                             | - The event name    |
| **`listenerFunc`** | <code>(event: <a href="#userinforeceivedevent">UserInfoReceivedEvent</a>) =&gt; void</code> | - Callback function |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

**Since:** 8.0.0

--------------------


### addListener('reachabilityChanged', ...)

```typescript
addListener(eventName: 'reachabilityChanged', listenerFunc: (event: ReachabilityChangedEvent) => void) => Promise<PluginListenerHandle>
```

Listen for watch reachability changes.

| Param              | Type                                                                                              | Description         |
| ------------------ | ------------------------------------------------------------------------------------------------- | ------------------- |
| **`eventName`**    | <code>'reachabilityChanged'</code>                                                                | - The event name    |
| **`listenerFunc`** | <code>(event: <a href="#reachabilitychangedevent">ReachabilityChangedEvent</a>) =&gt; void</code> | - Callback function |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

**Since:** 8.0.0

--------------------


### addListener('activationStateChanged', ...)

```typescript
addListener(eventName: 'activationStateChanged', listenerFunc: (event: ActivationStateChangedEvent) => void) => Promise<PluginListenerHandle>
```

Listen for session activation state changes.

| Param              | Type                                                                                                    | Description         |
| ------------------ | ------------------------------------------------------------------------------------------------------- | ------------------- |
| **`eventName`**    | <code>'activationStateChanged'</code>                                                                   | - The event name    |
| **`listenerFunc`** | <code>(event: <a href="#activationstatechangedevent">ActivationStateChangedEvent</a>) =&gt; void</code> | - Callback function |

**Returns:** <code>Promise&lt;<a href="#pluginlistenerhandle">PluginListenerHandle</a>&gt;</code>

**Since:** 8.0.0

--------------------


### removeAllListeners()

```typescript
removeAllListeners() => Promise<void>
```

Remove all listeners for this plugin.

**Since:** 8.0.0

--------------------


### Interfaces


#### SendMessageOptions

Options for sending a message to the watch.

| Prop       | Type                                                          | Description                                                                                               |
| ---------- | ------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| **`data`** | <code><a href="#watchmessagedata">WatchMessageData</a></code> | The data to send to the watch. Must be serializable (string, number, boolean, arrays, or nested objects). |


#### UpdateContextOptions

Options for updating the application context.

| Prop          | Type                                                          | Description                                                                                                 |
| ------------- | ------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| **`context`** | <code><a href="#watchmessagedata">WatchMessageData</a></code> | The context data to sync with the watch. Only the latest context is kept - previous values are overwritten. |


#### TransferUserInfoOptions

Options for transferring user info.

| Prop           | Type                                                          | Description                                                                  |
| -------------- | ------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| **`userInfo`** | <code><a href="#watchmessagedata">WatchMessageData</a></code> | The user info data to transfer. Transfers are queued and delivered in order. |


#### ReplyMessageOptions

Options for replying to a message from the watch.

| Prop             | Type                                                          | Description                                                     |
| ---------------- | ------------------------------------------------------------- | --------------------------------------------------------------- |
| **`callbackId`** | <code>string</code>                                           | The callback ID received in the messageReceivedWithReply event. |
| **`data`**       | <code><a href="#watchmessagedata">WatchMessageData</a></code> | The reply data to send back to the watch.                       |


#### WatchInfo

Information about Watch connectivity status.

| Prop                      | Type                 | Description                                                                                    |
| ------------------------- | -------------------- | ---------------------------------------------------------------------------------------------- |
| **`isSupported`**         | <code>boolean</code> | Whether WatchConnectivity is supported on this device. Always false on iPad, web, and Android. |
| **`isPaired`**            | <code>boolean</code> | Whether an Apple Watch is paired with this iPhone.                                             |
| **`isWatchAppInstalled`** | <code>boolean</code> | Whether the paired watch has the companion app installed.                                      |
| **`isReachable`**         | <code>boolean</code> | Whether the watch is currently reachable for immediate messaging.                              |
| **`activationState`**     | <code>number</code>  | The current activation state of the WCSession. 0 = notActivated, 1 = inactive, 2 = activated   |


#### PluginListenerHandle

| Prop         | Type                                      |
| ------------ | ----------------------------------------- |
| **`remove`** | <code>() =&gt; Promise&lt;void&gt;</code> |


#### MessageReceivedEvent

Event data for received messages.

| Prop          | Type                                                          | Description                               |
| ------------- | ------------------------------------------------------------- | ----------------------------------------- |
| **`message`** | <code><a href="#watchmessagedata">WatchMessageData</a></code> | The message data received from the watch. |


#### MessageReceivedWithReplyEvent

Event data for messages that require a reply.

| Prop             | Type                                                          | Description                                                 |
| ---------------- | ------------------------------------------------------------- | ----------------------------------------------------------- |
| **`message`**    | <code><a href="#watchmessagedata">WatchMessageData</a></code> | The message data received from the watch.                   |
| **`callbackId`** | <code>string</code>                                           | The callback ID to use when replying with replyToMessage(). |


#### ContextReceivedEvent

Event data for application context updates.

| Prop          | Type                                                          | Description                               |
| ------------- | ------------------------------------------------------------- | ----------------------------------------- |
| **`context`** | <code><a href="#watchmessagedata">WatchMessageData</a></code> | The context data received from the watch. |


#### UserInfoReceivedEvent

Event data for user info transfers.

| Prop           | Type                                                          | Description                                 |
| -------------- | ------------------------------------------------------------- | ------------------------------------------- |
| **`userInfo`** | <code><a href="#watchmessagedata">WatchMessageData</a></code> | The user info data received from the watch. |


#### ReachabilityChangedEvent

Event data for reachability changes.

| Prop              | Type                 | Description                         |
| ----------------- | -------------------- | ----------------------------------- |
| **`isReachable`** | <code>boolean</code> | Whether the watch is now reachable. |


#### ActivationStateChangedEvent

Event data for activation state changes.

| Prop        | Type                | Description                                                             |
| ----------- | ------------------- | ----------------------------------------------------------------------- |
| **`state`** | <code>number</code> | The new activation state. 0 = notActivated, 1 = inactive, 2 = activated |


### Type Aliases


#### WatchMessageData

Data that can be sent between iPhone and Apple Watch.
Values must be serializable (string, number, boolean, arrays, or nested objects).

<code><a href="#record">Record</a>&lt;string, unknown&gt;</code>


#### Record

Construct a type with a set of properties K of type T

<code>{ [P in K]: T; }</code>

</docgen-api>

## Usage Examples

### Send a Message to Watch

```typescript
import { CapgoWatch } from '@capgo/capacitor-watch';

// Check if watch is reachable first
const info = await CapgoWatch.getInfo();
if (info.isReachable) {
  await CapgoWatch.sendMessage({
    data: { action: 'refresh', timestamp: Date.now() }
  });
}
```

### Receive Messages from Watch

```typescript
import { CapgoWatch } from '@capgo/capacitor-watch';

// Listen for messages
await CapgoWatch.addListener('messageReceived', (event) => {
  console.log('Message from watch:', event.message);
});

// Listen for messages that need a reply
await CapgoWatch.addListener('messageReceivedWithReply', async (event) => {
  const result = await processRequest(event.message);
  await CapgoWatch.replyToMessage({
    callbackId: event.callbackId,
    data: { result }
  });
});
```

### Sync Application State

```typescript
import { CapgoWatch } from '@capgo/capacitor-watch';

// Update context (latest value only)
await CapgoWatch.updateApplicationContext({
  context: { theme: 'dark', userId: '123' }
});

// Transfer user info (queued, reliable delivery)
await CapgoWatch.transferUserInfo({
  userInfo: { recordId: '456', action: 'created' }
});
```

### Monitor Connectivity

```typescript
import { CapgoWatch } from '@capgo/capacitor-watch';

await CapgoWatch.addListener('reachabilityChanged', (event) => {
  console.log('Watch reachable:', event.isReachable);
});

await CapgoWatch.addListener('activationStateChanged', (event) => {
  // 0 = notActivated, 1 = inactive, 2 = activated
  console.log('Session state:', event.state);
});
```

## Watch App Implementation

Your watchOS app needs to implement WatchConnectivity. Here's a basic example:

```swift
import WatchConnectivity

class WatchViewModel: NSObject, ObservableObject, WCSessionDelegate {
    static let shared = WatchViewModel()

    override init() {
        super.init()
        if WCSession.isSupported() {
            WCSession.default.delegate = self
            WCSession.default.activate()
        }
    }

    func session(_ session: WCSession, didReceiveMessage message: [String: Any]) {
        // Handle message from iPhone
        print("Received: \(message)")
    }

    func sendToPhone(_ data: [String: Any]) {
        guard WCSession.default.isReachable else { return }
        WCSession.default.sendMessage(data, replyHandler: nil)
    }

    // Required delegate methods
    func session(_ session: WCSession, activationDidCompleteWith activationState: WCSessionActivationState, error: Error?) {}
}
```

## Credits

This plugin is based on the enhanced WatchConnectivity implementation from [CapacitorWatchEnhanced](https://github.com/macsupport/CapacitorWatchEnhanced), which added bidirectional messaging support to the original Ionic watch plugin.

## License

MPL-2.0
