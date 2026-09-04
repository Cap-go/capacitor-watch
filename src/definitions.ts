import type { PluginListenerHandle } from '@capacitor/core';

/**
 * Data that can be sent between iPhone and Apple Watch.
 * Values must be serializable (string, number, boolean, arrays, or nested objects).
 *
 * @since 8.0.0
 */
export type WatchMessageData = Record<string, unknown>;

/**
 * Options for sending a message to the watch.
 *
 * @since 8.0.0
 */
export interface SendMessageOptions {
  /**
   * The data to send to the watch.
   * Must be serializable (string, number, boolean, arrays, or nested objects).
   */
  data: WatchMessageData;
  /**
   * When true, wait for a reply from the watch and resolve with `{ reply }`.
   * When false or omitted, send without waiting (default).
   *
   * @default false
   * @since 8.2.0
   */
  expectsReply?: boolean;
}

/**
 * Result returned when `sendMessage` is called with `expectsReply: true`.
 *
 * @since 8.2.0
 */
export interface SendMessageResult {
  /**
   * Reply payload from the watch, or null when the watch returned an empty reply.
   */
  reply: WatchMessageData | null;
}

/**
 * Last persisted application context received from the watch.
 *
 * @since 8.2.0
 */
export interface ReceivedState {
  /**
   * The last application context received from the watch, or null if none is stored.
   */
  context: WatchMessageData | null;
}

/**
 * Plugin configuration for CapgoWatch.
 *
 * Configure in `capacitor.config`:
 * ```typescript
 * plugins: {
 *   CapgoWatch: {
 *     capability: 'capgo_watch', // default
 *   },
 * },
 * ```
 *
 * @since 8.2.0
 */
export interface CapgoWatchPluginConfig {
  /**
   * Wear OS capability name advertised by the companion watch app.
   *
   * @default 'capgo_watch'
   */
  capability?: string;
}

/** Default Wear OS capability when not configured. */
export const DEFAULT_WATCH_CAPABILITY = 'capgo_watch';

/**
 * Options for updating the application context.
 *
 * @since 8.0.0
 */
export interface UpdateContextOptions {
  /**
   * The context data to sync with the watch.
   * Only the latest context is kept - previous values are overwritten.
   */
  context: WatchMessageData;
}

/**
 * Options for transferring user info.
 *
 * @since 8.0.0
 */
export interface TransferUserInfoOptions {
  /**
   * The user info data to transfer.
   * Transfers are queued and delivered in order.
   */
  userInfo: WatchMessageData;
}

/**
 * Options for replying to a message from the watch.
 *
 * @since 8.0.0
 */
export interface ReplyMessageOptions {
  /**
   * The callback ID received in the messageReceivedWithReply event.
   */
  callbackId: string;
  /**
   * The reply data to send back to the watch.
   */
  data: WatchMessageData;
}

/**
 * Information about Watch / Wear OS connectivity status.
 *
 * @since 8.0.0
 */
export interface WatchInfo {
  /**
   * Whether the watch communication API is supported on this device.
   * - iOS: false on iPad; true on iPhone when WatchConnectivity is available.
   * - Android: true when Google Play Services with Wear OS support is available; false otherwise.
   * - Web: always false.
   */
  isSupported: boolean;
  /**
   * Whether a watch is currently paired/connected.
   * - iOS: whether an Apple Watch is paired with this iPhone.
   * - Android: whether at least one Wear OS node is currently connected.
   */
  isPaired: boolean;
  /**
   * Whether the watch companion app is installed.
   * - iOS: whether the paired Apple Watch has the companion app installed.
   * - Android: whether at least one connected Wear OS node is reachable (used as a proxy).
   */
  isWatchAppInstalled: boolean;
  /**
   * Whether the watch is currently reachable for immediate messaging.
   */
  isReachable: boolean;
  /**
   * The current session activation state.
   * - iOS: 0 = notActivated, 1 = inactive, 2 = activated (WCSessionActivationState).
   * - Android: 2 when a Wear OS node is connected, 0 otherwise.
   */
  activationState: number;
}

/**
 * Event data for received messages.
 *
 * @since 8.0.0
 */
export interface MessageReceivedEvent {
  /**
   * The message data received from the watch.
   */
  message: WatchMessageData;
}

/**
 * Event data for messages that require a reply.
 *
 * @since 8.0.0
 */
export interface MessageReceivedWithReplyEvent {
  /**
   * The message data received from the watch.
   */
  message: WatchMessageData;
  /**
   * The callback ID to use when replying with replyToMessage().
   */
  callbackId: string;
}

/**
 * Event data for application context updates.
 *
 * @since 8.0.0
 */
export interface ContextReceivedEvent {
  /**
   * The context data received from the watch.
   */
  context: WatchMessageData;
}

/**
 * Event data for user info transfers.
 *
 * @since 8.0.0
 */
export interface UserInfoReceivedEvent {
  /**
   * The user info data received from the watch.
   */
  userInfo: WatchMessageData;
}

/**
 * Event data for reachability changes.
 *
 * @since 8.0.0
 */
export interface ReachabilityChangedEvent {
  /**
   * Whether the watch is now reachable.
   */
  isReachable: boolean;
}

/**
 * Event data for activation state changes.
 *
 * @since 8.0.0
 */
export interface ActivationStateChangedEvent {
  /**
   * The new activation state.
   * 0 = notActivated, 1 = inactive, 2 = activated
   */
  state: number;
}

/**
 * Watch / Wear OS communication plugin for Capacitor.
 * Provides bidirectional messaging between the phone and a paired watch.
 *
 * - **iOS**: uses Apple WatchConnectivity framework to communicate with Apple Watch.
 * - **Android**: uses Google Wear OS Data Layer API (play-services-wearable) to communicate with a Wear OS watch.
 * - **Web**: not supported; all methods throw or return safe defaults.
 *
 * @since 8.0.0
 */
export interface CapgoWatchPlugin {
  /**
   * Send an interactive message to the watch.
   * The watch must be reachable for this to succeed.
   * Use this for time-sensitive, interactive communication.
   *
   * @param options - The message options
   * @returns Promise that resolves when the message is sent
   * @throws Error if the watch is not reachable or session is not active
   * @since 8.0.0
   * @example
   * ```typescript
   * await CapgoWatch.sendMessage({
   *   data: { action: 'refresh', timestamp: Date.now() }
   * });
   *
   * const { reply } = await CapgoWatch.sendMessage({
   *   data: { action: 'getStatus' },
   *   expectsReply: true,
   * });
   * ```
   */
  sendMessage(options: SendMessageOptions): Promise<void | SendMessageResult>;

  /**
   * Update the application context shared with the watch.
   * Only the latest context is kept - this overwrites any previous context.
   * Use this for syncing app state that the watch needs to display.
   *
   * @param options - The context options
   * @returns Promise that resolves when the context is updated
   * @throws Error if session is not active
   * @since 8.0.0
   * @example
   * ```typescript
   * await CapgoWatch.updateApplicationContext({
   *   context: { theme: 'dark', lastSync: Date.now() }
   * });
   * ```
   */
  updateApplicationContext(options: UpdateContextOptions): Promise<void>;

  /**
   * Transfer user info to the watch.
   * Transfers are queued and delivered in order, even if the watch is not currently reachable.
   * Use this for important data that must be delivered reliably.
   *
   * @param options - The user info options
   * @returns Promise that resolves when the transfer is queued
   * @throws Error if session is not active
   * @since 8.0.0
   * @example
   * ```typescript
   * await CapgoWatch.transferUserInfo({
   *   userInfo: { recordId: '123', action: 'created' }
   * });
   * ```
   */
  transferUserInfo(options: TransferUserInfoOptions): Promise<void>;

  /**
   * Reply to a message from the watch that requested a reply.
   * Use this in response to the messageReceivedWithReply event.
   *
   * @param options - The reply options including the callbackId
   * @returns Promise that resolves when the reply is sent
   * @throws Error if the callback ID is invalid or session is not active
   * @since 8.0.0
   * @example
   * ```typescript
   * CapgoWatch.addListener('messageReceivedWithReply', async (event) => {
   *   const result = await processRequest(event.message);
   *   await CapgoWatch.replyToMessage({
   *     callbackId: event.callbackId,
   *     data: { result }
   *   });
   * });
   * ```
   */
  replyToMessage(options: ReplyMessageOptions): Promise<void>;

  /**
   * Get information about the watch connectivity status.
   *
   * @returns Promise that resolves with watch connectivity info
   * @since 8.0.0
   * @example
   * ```typescript
   * const info = await CapgoWatch.getInfo();
   * if (info.isReachable) {
   *   await CapgoWatch.sendMessage({ data: { ping: true } });
   * }
   * ```
   */
  getInfo(): Promise<WatchInfo>;

  /**
   * Get the last persisted application context received from the watch.
   * Useful after app restart to restore watch-synced state without waiting for a new event.
   *
   * @returns Promise that resolves with the last received context
   * @since 8.2.0
   * @example
   * ```typescript
   * const { context } = await CapgoWatch.getReceivedState();
   * if (context) {
   *   console.log('Restored watch context:', context);
   * }
   * ```
   */
  getReceivedState(): Promise<ReceivedState>;

  /**
   * Get the native Capacitor plugin version.
   *
   * @returns Promise that resolves with the plugin version
   * @since 8.0.0
   * @example
   * ```typescript
   * const { version } = await CapgoWatch.getPluginVersion();
   * console.log('Plugin version:', version);
   * ```
   */
  getPluginVersion(): Promise<{ version: string }>;

  /**
   * Listen for messages received from the watch.
   *
   * @param eventName - The event name
   * @param listenerFunc - Callback function
   * @returns Handle to remove the listener
   * @since 8.0.0
   * @example
   * ```typescript
   * const handle = await CapgoWatch.addListener('messageReceived', (event) => {
   *   console.log('Message from watch:', event.message);
   * });
   * // Later: handle.remove();
   * ```
   */
  addListener(
    eventName: 'messageReceived',
    listenerFunc: (event: MessageReceivedEvent) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Listen for messages from the watch that require a reply.
   *
   * @param eventName - The event name
   * @param listenerFunc - Callback function
   * @returns Handle to remove the listener
   * @since 8.0.0
   * @example
   * ```typescript
   * await CapgoWatch.addListener('messageReceivedWithReply', async (event) => {
   *   const response = await handleRequest(event.message);
   *   await CapgoWatch.replyToMessage({
   *     callbackId: event.callbackId,
   *     data: response
   *   });
   * });
   * ```
   */
  addListener(
    eventName: 'messageReceivedWithReply',
    listenerFunc: (event: MessageReceivedWithReplyEvent) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Listen for application context updates from the watch.
   *
   * @param eventName - The event name
   * @param listenerFunc - Callback function
   * @returns Handle to remove the listener
   * @since 8.0.0
   * @example
   * ```typescript
   * await CapgoWatch.addListener('applicationContextReceived', (event) => {
   *   console.log('Context from watch:', event.context);
   * });
   * ```
   */
  addListener(
    eventName: 'applicationContextReceived',
    listenerFunc: (event: ContextReceivedEvent) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Listen for user info transfers from the watch.
   *
   * @param eventName - The event name
   * @param listenerFunc - Callback function
   * @returns Handle to remove the listener
   * @since 8.0.0
   * @example
   * ```typescript
   * await CapgoWatch.addListener('userInfoReceived', (event) => {
   *   console.log('User info from watch:', event.userInfo);
   * });
   * ```
   */
  addListener(
    eventName: 'userInfoReceived',
    listenerFunc: (event: UserInfoReceivedEvent) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Listen for watch reachability changes.
   *
   * @param eventName - The event name
   * @param listenerFunc - Callback function
   * @returns Handle to remove the listener
   * @since 8.0.0
   * @example
   * ```typescript
   * await CapgoWatch.addListener('reachabilityChanged', (event) => {
   *   console.log('Watch reachable:', event.isReachable);
   * });
   * ```
   */
  addListener(
    eventName: 'reachabilityChanged',
    listenerFunc: (event: ReachabilityChangedEvent) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Listen for session activation state changes.
   *
   * @param eventName - The event name
   * @param listenerFunc - Callback function
   * @returns Handle to remove the listener
   * @since 8.0.0
   * @example
   * ```typescript
   * await CapgoWatch.addListener('activationStateChanged', (event) => {
   *   console.log('Activation state:', event.state);
   * });
   * ```
   */
  addListener(
    eventName: 'activationStateChanged',
    listenerFunc: (event: ActivationStateChangedEvent) => void,
  ): Promise<PluginListenerHandle>;

  /**
   * Remove all listeners for this plugin.
   *
   * @since 8.0.0
   * @example
   * ```typescript
   * await CapgoWatch.removeAllListeners();
   * ```
   */
  removeAllListeners(): Promise<void>;
}
