package app.capgo.capacitor.watch;

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
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeClient;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Wear OS communication plugin for Capacitor.
 * Provides bidirectional messaging between Android phone and Wear OS watch
 * using the Wear OS Data Layer API (play-services-wearable).
 */
@CapacitorPlugin(name = "CapgoWatch")
public class CapgoWatchPlugin extends Plugin {

    private static final String TAG = "CapgoWatchPlugin";
    private static final String PLUGIN_VERSION = "8.1.3";
    private static final long PENDING_REPLY_TTL_MS = 5 * 60 * 1000L;

    private static volatile CapgoWatchPendingReplyManager activePendingReplyManager;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private CapgoWatchPendingReplyManager pendingReplyManager;
    private MessageClient messageClient;
    private DataClient dataClient;
    private NodeClient nodeClient;
    private CapabilityClient capabilityClient;
    private CapgoWatchEventStore eventStore;

    private String watchCapability = CapgoWatchConstants.DEFAULT_CAPABILITY;
    private final AtomicBoolean lastReachable = new AtomicBoolean(false);

    private final CapabilityClient.OnCapabilityChangedListener capabilityChangedListener = (info) -> refreshReachability();

    @Override
    public void load() {
        watchCapability = getConfig().getString("capability", CapgoWatchConstants.DEFAULT_CAPABILITY);
        eventStore = new CapgoWatchEventStore(getContext());
        CapgoWatchEventBridge.initialize(eventStore);
        CapgoWatchEventBridge.registerPlugin(this);

        messageClient = Wearable.getMessageClient(getContext());
        dataClient = Wearable.getDataClient(getContext());
        nodeClient = Wearable.getNodeClient(getContext());
        capabilityClient = Wearable.getCapabilityClient(getContext());

        pendingReplyManager = new CapgoWatchPendingReplyManager(PENDING_REPLY_TTL_MS);
        pendingReplyManager.initialize(messageClient, eventStore);
        final CapgoWatchPendingReplyManager previousManager = activePendingReplyManager;
        activePendingReplyManager = pendingReplyManager;
        if (previousManager != null && previousManager != pendingReplyManager) {
            previousManager.shutdown();
        }

        capabilityClient
            .addListener(capabilityChangedListener, watchCapability)
            .addOnFailureListener((e) -> Log.w(TAG, "Failed to register capability listener", e));

        refreshReachability();
    }

    @Override
    protected void handleOnDestroy() {
        CapgoWatchEventBridge.unregisterPlugin(this);
        if (capabilityClient != null) {
            capabilityClient.removeListener(capabilityChangedListener, watchCapability);
        }
        if (pendingReplyManager != null) {
            pendingReplyManager.shutdown();
            if (activePendingReplyManager == pendingReplyManager) {
                activePendingReplyManager = null;
            }
            pendingReplyManager = null;
        }
        executor.shutdown();
    }

    @Override
    @PluginMethod(returnType = PluginMethod.RETURN_NONE)
    public void addListener(final PluginCall call) {
        super.addListener(call);
        final String eventName = call.getString("eventName");
        if (eventName != null) {
            replayStoredEventsFor(eventName);
        }
    }

    boolean hasWatchListeners(final String eventName) {
        return hasListeners(eventName);
    }

    void dispatchWatchEvent(final String eventName, final JSObject payload, final boolean retainUntilConsumed) {
        notifyListeners(eventName, payload, retainUntilConsumed);
    }

    static void registerPendingReply(final String callbackId, final String nodeId) {
        final CapgoWatchPendingReplyManager manager = activePendingReplyManager;
        if (manager != null) {
            manager.registerIncoming(callbackId, nodeId);
            return;
        }
        CapgoWatchEventBridge.savePendingReply(callbackId, nodeId);
    }

    static void handleIncomingReply(final String path, final byte[] data) {
        final CapgoWatchPendingReplyManager manager = activePendingReplyManager;
        if (manager == null || !path.startsWith(CapgoWatchConstants.PATH_REPLY)) {
            return;
        }

        final String callbackId = path.substring(CapgoWatchConstants.PATH_REPLY.length());
        final CapgoWatchPendingReplyManager.OutgoingPendingReply pending = manager.removeOutgoing(callbackId);
        if (pending == null) {
            return;
        }

        try {
            final String payload = new String(data, StandardCharsets.UTF_8);
            final JSObject reply;
            if (payload.isEmpty() || "{}".equals(payload)) {
                reply = null;
            } else {
                reply = new JSObject(new JSONObject(payload).toString());
            }

            final JSObject result = new JSObject();
            if (reply == null) {
                result.put("reply", JSONObject.NULL);
            } else {
                result.put("reply", reply);
            }
            pending.call.resolve(result);
        } catch (JSONException e) {
            pending.call.reject("Failed to parse watch reply: " + e.getMessage(), e);
        }
    }

    private void replayStoredEventsFor(final String eventName) {
        final List<CapgoWatchEventStore.StoredEvent> storedEvents =
            eventName == null ? eventStore.drainAll() : eventStore.drainEventsFor(eventName);

        for (final CapgoWatchEventStore.StoredEvent storedEvent : storedEvents) {
            if ("messageReceivedWithReply".equals(storedEvent.eventName) && storedEvent.replyNodeId != null) {
                final String callbackId = storedEvent.payload.getString("callbackId", null);
                if (callbackId != null && pendingReplyManager != null) {
                    final CapgoWatchEventStore.PendingReplyRecord record = eventStore.loadPendingReplies().get(callbackId);
                    final long createdAt = record != null ? record.createdAt : storedEvent.timestamp;
                    if (System.currentTimeMillis() - createdAt > PENDING_REPLY_TTL_MS) {
                        continue;
                    }
                    if (pendingReplyManager.getIncoming(callbackId) == null) {
                        pendingReplyManager.restoreIncoming(callbackId, storedEvent.replyNodeId, createdAt);
                    }
                }
            }
            dispatchWatchEvent(storedEvent.eventName, storedEvent.payload, true);
        }
    }

    private void refreshReachability() {
        executor.execute(() -> {
            try {
                final List<Node> nodes = Tasks.await(nodeClient.getConnectedNodes());
                final boolean isReachable = !nodes.isEmpty();
                while (true) {
                    final boolean previous = lastReachable.get();
                    if (previous == isReachable) {
                        break;
                    }
                    if (lastReachable.compareAndSet(previous, isReachable)) {
                        CapgoWatchEventBridge.dispatchReachability(isReachable);
                        break;
                    }
                }
            } catch (ExecutionException | InterruptedException e) {
                Log.w(TAG, "Failed to determine reachability", e);
            }
        });
    }

    @PluginMethod
    public void sendMessage(final PluginCall call) {
        final JSObject data = call.getObject("data");
        if (data == null) {
            call.reject("Missing required parameter: 'data'");
            return;
        }

        final boolean expectsReply = Boolean.TRUE.equals(call.getBoolean("expectsReply", false));

        executor.execute(() -> {
            final CapgoWatchPendingReplyManager manager = pendingReplyManager;
            if (manager == null) {
                call.reject("Watch plugin destroyed");
                return;
            }

            String callbackId = null;
            try {
                final List<Node> nodes = Tasks.await(nodeClient.getConnectedNodes());
                if (nodes.isEmpty()) {
                    call.reject("No connected Wear OS devices found");
                    return;
                }

                if (expectsReply) {
                    callbackId = UUID.randomUUID().toString();
                    if (!manager.registerOutgoing(callbackId, call)) {
                        return;
                    }

                    final JSONObject envelope = CapgoWatchMessagePayload.buildReplyEnvelope(callbackId, new JSONObject(data.toString()));
                    final byte[] payload = envelope.toString().getBytes(StandardCharsets.UTF_8);

                    for (final Node node : nodes) {
                        Tasks.await(messageClient.sendMessage(node.getId(), CapgoWatchConstants.PATH_MESSAGE_WITH_REPLY, payload));
                    }
                } else {
                    final byte[] payload = data.toString().getBytes(StandardCharsets.UTF_8);
                    for (final Node node : nodes) {
                        Tasks.await(messageClient.sendMessage(node.getId(), CapgoWatchConstants.PATH_MESSAGE, payload));
                    }
                    call.resolve();
                }
            } catch (ExecutionException | InterruptedException | JSONException e) {
                if (callbackId != null) {
                    manager.rejectOutgoing(callbackId, "Failed to send message: " + e.getMessage());
                } else {
                    call.reject("Failed to send message: " + e.getMessage(), e);
                }
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
                final PutDataMapRequest request = PutDataMapRequest.create(CapgoWatchConstants.PATH_CONTEXT);
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

        final String path = CapgoWatchConstants.PATH_USER_INFO + UUID.randomUUID();
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

        final CapgoWatchPendingReplyManager.IncomingPendingReply pendingReply = pendingReplyManager.claimIncoming(callbackId);
        if (pendingReply == null) {
            call.reject("No pending reply found for callbackId: " + callbackId);
            return;
        }
        if (System.currentTimeMillis() - pendingReply.createdAt > PENDING_REPLY_TTL_MS) {
            call.reject("Pending reply expired for callbackId: " + callbackId);
            return;
        }

        final String nodeId = pendingReply.nodeId;
        final byte[] payload = data.toString().getBytes(StandardCharsets.UTF_8);
        final String replyPath = CapgoWatchConstants.PATH_REPLY + callbackId;
        executor.execute(() -> {
            try {
                Tasks.await(messageClient.sendMessage(nodeId, replyPath, payload));
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
                final List<Node> nodes = Tasks.await(nodeClient.getConnectedNodes());
                final boolean isReachable = !nodes.isEmpty();
                final CapabilityInfo capabilityInfo = Tasks.await(
                    capabilityClient.getCapability(watchCapability, CapabilityClient.FILTER_ALL)
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
    public void getReceivedState(final PluginCall call) {
        executor.execute(() -> {
            JSObject context = eventStore.loadLastContext();
            if (context == null) {
                DataItemBuffer buffer = null;
                try {
                    final String localNodeId = Tasks.await(nodeClient.getLocalNode()).getId();
                    buffer = Tasks.await(dataClient.getDataItems(CapgoWatchConstants.contextDataItemUri()));
                    for (final DataItem item : buffer) {
                        final String host = item.getUri().getHost();
                        if (host != null && host.equals(localNodeId)) {
                            // Skip the phone's own outgoing context written by updateApplicationContext.
                            continue;
                        }
                        final String payload = com.google.android.gms.wearable.DataMapItem.fromDataItem(item)
                            .getDataMap()
                            .getString("payload", "{}");
                        context = new JSObject(new JSONObject(payload).toString());
                        eventStore.saveLastContext(context);
                        break;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to load application context from Data Layer", e);
                } finally {
                    if (buffer != null) {
                        buffer.release();
                    }
                }
            }

            final JSObject ret = new JSObject();
            if (context == null) {
                ret.put("context", JSONObject.NULL);
            } else {
                ret.put("context", context);
            }
            call.resolve(ret);
        });
    }

    @PluginMethod
    public void getPluginVersion(final PluginCall call) {
        final JSObject ret = new JSObject();
        ret.put("version", PLUGIN_VERSION);
        call.resolve(ret);
    }
}
