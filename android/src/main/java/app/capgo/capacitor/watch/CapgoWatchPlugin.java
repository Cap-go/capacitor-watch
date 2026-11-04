package app.capgo.capacitor.watch;

import android.net.Uri;
import android.util.Log;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.CapabilityClient;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Wear OS communication plugin for Capacitor.
 * Provides bidirectional messaging between Android phone and Wear OS watch
 * using the Wear OS Data Layer API (play-services-wearable).
 *
 * <p>Message paths used over the Data Layer:</p>
 * <ul>
 *   <li>{@code /capgo/message} — regular one-way messages</li>
 *   <li>{@code /capgo/message/withreply} — messages that expect a reply</li>
 *   <li>{@code /capgo/reply/{callbackId}} — reply to a watch-initiated message</li>
 *   <li>{@code /capgo/context} — application context sync via DataItem</li>
 *   <li>{@code /capgo/userinfo/{uuid}} — queued user-info transfer via DataItem</li>
 * </ul>
 */
@CapacitorPlugin(name = "CapgoWatch")
public class CapgoWatchPlugin extends Plugin {

    private static final String TAG = "CapgoWatchPlugin";
    private static final String PLUGIN_VERSION = "8.0.25";

    /** Capability advertised by the companion Wear OS app. */
    static final String WATCH_APP_CAPABILITY = "capgo_watch";

    /** Path for regular messages sent/received via MessageClient. */
    static final String PATH_MESSAGE = "/capgo/message";
    /** Path prefix for messages that require a reply. */
    static final String PATH_MESSAGE_WITH_REPLY = "/capgo/message/withreply";
    /** Path prefix for reply messages sent back to the watch. */
    static final String PATH_REPLY = "/capgo/reply/";
    /** DataItem path for application context sync. */
    static final String PATH_CONTEXT = "/capgo/context";
    /** DataItem path prefix for user info transfers. */
    static final String PATH_USER_INFO = "/capgo/userinfo/";

    /** Pending reply callbacks expire after 5 minutes. */
    private static final long PENDING_REPLY_TTL_MS = 5 * 60 * 1000L;

    private static final int LOCAL_NODE_LOOKUP_MAX_ATTEMPTS = 4;
    private static final long LOCAL_NODE_LOOKUP_INITIAL_BACKOFF_MS = 100L;
    private static final long LOCAL_NODE_LOOKUP_MAX_BACKOFF_MS = 2000L;

    private static final int PENDING_USER_INFO_SCAN_MAX_ATTEMPTS = 4;
    private static final long PENDING_USER_INFO_SCAN_INITIAL_BACKOFF_MS = 200L;
    private static final long PENDING_USER_INFO_SCAN_MAX_BACKOFF_MS = 2000L;

    /** Maps callbackId to pending reply metadata. */
    private final Map<String, PendingReply> pendingReplies = new ConcurrentHashMap<>();

    /** URIs currently being delivered to avoid duplicate userInfoReceived events. */
    private final Set<String> userInfoDeliveryClaims = ConcurrentHashMap.newKeySet();

    /** Shared thread pool for all background Wear OS operations. */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private MessageClient messageClient;
    private DataClient dataClient;

    private volatile boolean pluginDestroyed;

    private final MessageClient.OnMessageReceivedListener messageListener = this::handleMessageReceived;
    private final DataClient.OnDataChangedListener dataListener = this::handleDataChanged;

    private static final class PendingReply {

        final String nodeId;
        final long createdAt;

        PendingReply(final String nodeId, final long createdAt) {
            this.nodeId = nodeId;
            this.createdAt = createdAt;
        }
    }

    @Override
    public void load() {
        pluginDestroyed = false;
        messageClient = Wearable.getMessageClient(getContext());
        dataClient = Wearable.getDataClient(getContext());
        messageClient.addListener(messageListener).addOnFailureListener((e) -> Log.e(TAG, "Failed to register message listener", e));
        dataClient.addListener(dataListener).addOnFailureListener((e) -> Log.e(TAG, "Failed to register data listener", e));
        scanPendingUserInfoItems();
    }

    @Override
    protected void handleOnDestroy() {
        pluginDestroyed = true;
        if (messageClient != null) {
            messageClient.removeListener(messageListener);
        }
        if (dataClient != null) {
            dataClient.removeListener(dataListener);
        }
        executor.shutdown();
    }

    @Override
    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    public void addListener(final PluginCall call) {
        super.addListener(call);
        if ("userInfoReceived".equals(call.getString("eventName"))) {
            scanPendingUserInfoItems();
        }
    }

    private void expirePendingReplies() {
        final long now = System.currentTimeMillis();
        final Iterator<Map.Entry<String, PendingReply>> iterator = pendingReplies.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<String, PendingReply> entry = iterator.next();
            if (now - entry.getValue().createdAt > PENDING_REPLY_TTL_MS) {
                iterator.remove();
            }
        }
    }

    // ── Incoming message / data handlers ─────────────────────────────────────

    private void handleMessageReceived(MessageEvent event) {
        final String path = event.getPath();
        if (!PATH_MESSAGE.equals(path) && !PATH_MESSAGE_WITH_REPLY.equals(path)) {
            return;
        }

        expirePendingReplies();

        final String nodeId = event.getSourceNodeId();
        final String payload = new String(event.getData(), StandardCharsets.UTF_8);

        try {
            final JSONObject json = new JSONObject(payload);
            final JSObject messageData = new JSObject(json.toString());

            if (PATH_MESSAGE_WITH_REPLY.equals(path)) {
                final String callbackId = UUID.randomUUID().toString();
                pendingReplies.put(callbackId, new PendingReply(nodeId, System.currentTimeMillis()));

                final JSObject evt = new JSObject();
                evt.put("message", messageData);
                evt.put("callbackId", callbackId);
                notifyListeners("messageReceivedWithReply", evt);
            } else {
                final JSObject evt = new JSObject();
                evt.put("message", messageData);
                notifyListeners("messageReceived", evt);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing received message", e);
        }
    }

    private void handleDataChanged(DataEventBuffer dataEvents) {
        for (DataEvent event : dataEvents) {
            if (event.getType() != DataEvent.TYPE_CHANGED) {
                continue;
            }

            final DataItem item = event.getDataItem();
            final String path = item.getUri().getPath();
            if (path == null) {
                continue;
            }

            final boolean isContext = PATH_CONTEXT.equals(path);
            final boolean isUserInfo = path.startsWith(PATH_USER_INFO);
            if (!isContext && !isUserInfo) {
                continue;
            }

            try {
                final DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                final String payload = dataMap.getString("payload", null);
                if (payload == null) {
                    continue;
                }
                final JSONObject json = new JSONObject(payload);
                final JSObject data = new JSObject(json.toString());

                if (isContext) {
                    final JSObject evt = new JSObject();
                    evt.put("context", data);
                    notifyListeners("applicationContextReceived", evt);
                } else {
                    scheduleUserInfoDelivery(item, data);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing data change", e);
            }
        }
    }

    private void scheduleUserInfoDelivery(final DataItem item, final JSObject userInfoPayload) {
        final Uri itemUri = item.getUri();
        final String claimKey = itemUri.toString();
        if (!userInfoDeliveryClaims.add(claimKey)) {
            return;
        }
        final String itemHost = itemUri.getHost();
        executor.execute(() -> {
            boolean bridgeTaskPosted = false;
            try {
                if (pluginDestroyed) {
                    return;
                }
                final String localId = resolveLocalNodeIdWithRetry();
                if (localId == null) {
                    return;
                }
                if (localId.equals(itemHost)) {
                    return;
                }
                execute(() -> deliverUserInfoFromRemote(itemUri, userInfoPayload, claimKey));
                bridgeTaskPosted = true;
            } finally {
                if (!bridgeTaskPosted) {
                    userInfoDeliveryClaims.remove(claimKey);
                }
            }
        });
    }

    private void deliverUserInfoFromRemote(final Uri itemUri, final JSObject userInfoPayload, final String claimKey) {
        if (pluginDestroyed || !hasListeners("userInfoReceived")) {
            userInfoDeliveryClaims.remove(claimKey);
            return;
        }
        final JSObject evt = new JSObject();
        evt.put("userInfo", userInfoPayload);
        notifyListeners("userInfoReceived", evt);
        deleteDataItem(itemUri, () -> userInfoDeliveryClaims.remove(claimKey));
    }

    private void scanPendingUserInfoItems() {
        if (dataClient == null) {
            return;
        }
        executor.execute(() -> scanPendingUserInfoItemsAttempt(0, PENDING_USER_INFO_SCAN_INITIAL_BACKOFF_MS));
    }

    private void scanPendingUserInfoItemsAttempt(final int attempt, final long backoffMs) {
        if (pluginDestroyed || !hasListeners("userInfoReceived")) {
            return;
        }
        try {
            final Uri prefix = Uri.parse("wear://*" + PATH_USER_INFO);
            final DataItemBuffer buffer = Tasks.await(dataClient.getDataItems(prefix, DataClient.FILTER_PREFIX));
            try {
                for (final DataItem item : buffer) {
                    if (pluginDestroyed) {
                        return;
                    }
                    final String path = item.getUri().getPath();
                    if (path == null || !path.startsWith(PATH_USER_INFO)) {
                        continue;
                    }
                    try {
                        final DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                        final String payload = dataMap.getString("payload", null);
                        if (payload == null) {
                            continue;
                        }
                        final JSONObject json = new JSONObject(payload);
                        final JSObject data = new JSObject(json.toString());
                        scheduleUserInfoDelivery(item, data);
                    } catch (Exception e) {
                        Log.e(TAG, "Error scanning pending user info DataItem", e);
                    }
                }
            } finally {
                buffer.release();
            }
        } catch (ExecutionException | InterruptedException e) {
            Log.w(TAG, "Failed to scan pending user info DataItems", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            schedulePendingUserInfoScanRetry(attempt, backoffMs);
        }
    }

    private void schedulePendingUserInfoScanRetry(final int attempt, final long backoffMs) {
        if (pluginDestroyed || !hasListeners("userInfoReceived")) {
            return;
        }
        if (attempt + 1 >= PENDING_USER_INFO_SCAN_MAX_ATTEMPTS) {
            Log.w(TAG, "Pending user info scan exhausted retries");
            return;
        }
        final int nextAttempt = attempt + 1;
        final long nextBackoffMs = Math.min(backoffMs * 2, PENDING_USER_INFO_SCAN_MAX_BACKOFF_MS);
        executor.execute(() -> {
            if (pluginDestroyed || !hasListeners("userInfoReceived")) {
                return;
            }
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            if (pluginDestroyed || !hasListeners("userInfoReceived")) {
                return;
            }
            scanPendingUserInfoItemsAttempt(nextAttempt, nextBackoffMs);
        });
    }

    /**
     * Resolves the local Wear node id, retrying transient {@link ExecutionException} failures.
     */
    private String resolveLocalNodeIdWithRetry() {
        long backoffMs = LOCAL_NODE_LOOKUP_INITIAL_BACKOFF_MS;
        ExecutionException lastFailure = null;
        for (int attempt = 0; attempt < LOCAL_NODE_LOOKUP_MAX_ATTEMPTS; attempt++) {
            try {
                return Tasks.await(Wearable.getNodeClient(getContext()).getLocalNode()).getId();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Could not resolve local node for user info filter", e);
                return null;
            } catch (ExecutionException e) {
                lastFailure = e;
                if (attempt + 1 >= LOCAL_NODE_LOOKUP_MAX_ATTEMPTS) {
                    break;
                }
                Log.w(TAG, "Local node lookup failed, retrying", e);
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "Could not resolve local node for user info filter", ie);
                    return null;
                }
                backoffMs = Math.min(backoffMs * 2, LOCAL_NODE_LOOKUP_MAX_BACKOFF_MS);
            }
        }
        Log.w(TAG, "Could not resolve local node for user info filter", lastFailure);
        return null;
    }

    private void deleteDataItem(final Uri uri, final Runnable onComplete) {
        if (dataClient == null) {
            onComplete.run();
            return;
        }
        dataClient
            .deleteDataItems(uri)
            .addOnFailureListener((e) -> Log.w(TAG, "Failed to delete delivered user info DataItem", e))
            .addOnCompleteListener((task) -> onComplete.run());
    }

    // ── Plugin methods ────────────────────────────────────────────────────────

    @PluginMethod
    public void sendMessage(final PluginCall call) {
        final JSObject data = call.getObject("data");
        if (data == null) {
            call.reject("Missing required parameter: 'data'");
            return;
        }

        final byte[] payload = data.toString().getBytes(StandardCharsets.UTF_8);
        executor.execute(() -> {
            try {
                final List<Node> nodes = Tasks.await(Wearable.getNodeClient(getContext()).getConnectedNodes());
                if (nodes.isEmpty()) {
                    call.reject("No connected Wear OS devices found");
                    return;
                }
                for (final Node node : nodes) {
                    Tasks.await(messageClient.sendMessage(node.getId(), PATH_MESSAGE, payload));
                }
                call.resolve();
            } catch (ExecutionException | InterruptedException e) {
                call.reject("Failed to send message: " + e.getMessage(), e);
            }
        });
    }

    @PluginMethod
    public void updateApplicationContext(final PluginCall call) {
        final JSObject context = call.getObject("context");
        if (context == null) {
            call.reject("Missing required parameter: 'context'");
            return;
        }

        executor.execute(() -> {
            try {
                final PutDataMapRequest request = PutDataMapRequest.create(PATH_CONTEXT);
                request.getDataMap().putString("payload", context.toString());
                request.setUrgent();
                Tasks.await(dataClient.putDataItem(request.asPutDataRequest()));
                call.resolve();
            } catch (ExecutionException | InterruptedException e) {
                call.reject("Failed to update application context: " + e.getMessage(), e);
            }
        });
    }

    @PluginMethod
    public void transferUserInfo(final PluginCall call) {
        final JSObject userInfo = call.getObject("userInfo");
        if (userInfo == null) {
            call.reject("Missing required parameter: 'userInfo'");
            return;
        }

        final String path = PATH_USER_INFO + UUID.randomUUID();
        executor.execute(() -> {
            try {
                final PutDataMapRequest request = PutDataMapRequest.create(path);
                request.getDataMap().putString("payload", userInfo.toString());
                request.setUrgent();
                Tasks.await(dataClient.putDataItem(request.asPutDataRequest()));
                call.resolve();
            } catch (ExecutionException | InterruptedException e) {
                call.reject("Failed to transfer user info: " + e.getMessage(), e);
            }
        });
    }

    @PluginMethod
    public void replyToMessage(final PluginCall call) {
        final String callbackId = call.getString("callbackId");
        final JSObject data = call.getObject("data");

        if (callbackId == null || callbackId.isEmpty()) {
            call.reject("Missing required parameter: 'callbackId'");
            return;
        }
        if (data == null) {
            call.reject("Missing required parameter: 'data'");
            return;
        }

        expirePendingReplies();

        final PendingReply pendingReply = pendingReplies.get(callbackId);
        if (pendingReply == null) {
            call.reject("No pending reply found for callbackId: " + callbackId);
            return;
        }
        if (System.currentTimeMillis() - pendingReply.createdAt > PENDING_REPLY_TTL_MS) {
            pendingReplies.remove(callbackId);
            call.reject("Pending reply expired for callbackId: " + callbackId);
            return;
        }

        final String nodeId = pendingReply.nodeId;
        final byte[] payload = data.toString().getBytes(StandardCharsets.UTF_8);
        final String replyPath = PATH_REPLY + callbackId;
        executor.execute(() -> {
            try {
                Tasks.await(messageClient.sendMessage(nodeId, replyPath, payload));
                pendingReplies.remove(callbackId);
                call.resolve();
            } catch (ExecutionException | InterruptedException e) {
                call.reject("Failed to send reply: " + e.getMessage(), e);
            }
        });
    }

    @PluginMethod
    public void getInfo(final PluginCall call) {
        executor.execute(() -> {
            try {
                final List<Node> nodes = Tasks.await(Wearable.getNodeClient(getContext()).getConnectedNodes());
                final boolean isReachable = !nodes.isEmpty();
                final CapabilityClient capabilityClient = Wearable.getCapabilityClient(getContext());
                final CapabilityInfo capabilityInfo = Tasks.await(
                    capabilityClient.getCapability(WATCH_APP_CAPABILITY, CapabilityClient.FILTER_ALL)
                );
                final boolean isWatchAppInstalled = !capabilityInfo.getNodes().isEmpty();
                final JSObject ret = new JSObject();
                ret.put("isSupported", true);
                ret.put("isPaired", isReachable);
                ret.put("isWatchAppInstalled", isWatchAppInstalled);
                ret.put("isReachable", isReachable);
                ret.put("activationState", isReachable ? 2 : 0);
                call.resolve(ret);
            } catch (ExecutionException | InterruptedException e) {
                // Wear OS API unavailable (e.g. Google Play Services missing)
                final JSObject ret = new JSObject();
                ret.put("isSupported", false);
                ret.put("isPaired", false);
                ret.put("isWatchAppInstalled", false);
                ret.put("isReachable", false);
                ret.put("activationState", 0);
                call.resolve(ret);
            }
        });
    }

    @PluginMethod
    public void getPluginVersion(final PluginCall call) {
        final JSObject ret = new JSObject();
        ret.put("version", PLUGIN_VERSION);
        call.resolve(ret);
    }
}
