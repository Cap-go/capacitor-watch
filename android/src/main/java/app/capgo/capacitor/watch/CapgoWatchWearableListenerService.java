package app.capgo.capacitor.watch;

import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import com.getcapacitor.JSObject;
import com.google.android.gms.common.data.FreezableUtils;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Receives Wear OS events while the phone app process is not running.
 * Events are persisted and replayed when the Capacitor plugin loads.
 */
public class CapgoWatchWearableListenerService extends WearableListenerService {

    private static final String TAG = "CapgoWatchListenerSvc";

    private static final long LOCAL_NODE_RETRY_MS = 1_000L;
    private static final int LOCAL_NODE_MAX_RETRIES = 5;

    private CapgoWatchEventStore eventStore;
    private volatile String localNodeId;
    private final Object pendingDataLock = new Object();
    private List<DataEvent> pendingDataEvents;
    private List<Uri> pendingPersistedUris;
    private final AtomicBoolean resolvingLocalNode = new AtomicBoolean(false);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int localNodeRetryAttempts = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        eventStore = new CapgoWatchEventStore(getApplicationContext());
        CapgoWatchEventBridge.initialize(eventStore);
        reloadPersistedPendingDataUris();
        Wearable.getNodeClient(this)
            .getLocalNode()
            .addOnSuccessListener((node) -> onLocalNodeResolved(node.getId()))
            .addOnFailureListener((e) -> Log.w(TAG, "Failed to resolve local node id", e));
    }

    @Override
    public void onMessageReceived(final MessageEvent event) {
        final String path = event.getPath();
        if (path == null || !path.startsWith("/capgo/")) {
            return;
        }

        if (path.startsWith(CapgoWatchConstants.PATH_REPLY)) {
            CapgoWatchPlugin.handleIncomingReply(path, event.getData());
            return;
        }

        if (!CapgoWatchConstants.PATH_MESSAGE.equals(path) && !CapgoWatchConstants.PATH_MESSAGE_WITH_REPLY.equals(path)) {
            return;
        }

        final boolean isReplyPath = CapgoWatchConstants.PATH_MESSAGE_WITH_REPLY.equals(path);
        final String payload = new String(event.getData(), StandardCharsets.UTF_8);
        try {
            final JSONObject json = new JSONObject(payload);

            if (isReplyPath) {
                if (!CapgoWatchMessagePayload.isReplyEnvelope(json)) {
                    Log.w(TAG, "Skipping non-envelope reply-path message");
                    return;
                }

                final CapgoWatchMessagePayload.ReplyEnvelope envelope = CapgoWatchMessagePayload.parseReplyEnvelope(json);
                CapgoWatchPlugin.registerPendingReply(envelope.callbackId, event.getSourceNodeId());

                final JSObject evt = new JSObject();
                evt.put("message", envelope.messageData);
                evt.put("callbackId", envelope.callbackId);
                CapgoWatchEventBridge.dispatch("messageReceivedWithReply", evt, true, event.getSourceNodeId());
            } else {
                final JSObject messageData = CapgoWatchMessagePayload.toMessageData(json);
                final JSObject evt = new JSObject();
                evt.put("message", messageData);
                CapgoWatchEventBridge.dispatch("messageReceived", evt, true);
            }
        } catch (JSONException e) {
            Log.e(TAG, "Error parsing received message", e);
        }
    }

    @Override
    public void onDataChanged(final DataEventBuffer dataEvents) {
        final List<DataEvent> events = FreezableUtils.freezeIterable(dataEvents);
        final String cachedLocalNodeId = localNodeId;
        if (cachedLocalNodeId == null) {
            enqueuePendingDataEvents(events);
            resolveLocalNodeAndProcessPending();
            return;
        }
        processDataEvents(events, cachedLocalNodeId);
    }

    private void onLocalNodeResolved(final String nodeId) {
        localNodeId = nodeId;
        localNodeRetryAttempts = 0;
        resolvingLocalNode.set(false);
        final List<DataEvent> pending;
        final List<Uri> persisted;
        synchronized (pendingDataLock) {
            pending = pendingDataEvents;
            pendingDataEvents = null;
            persisted = pendingPersistedUris;
            pendingPersistedUris = null;
        }
        if (pending != null && !pending.isEmpty()) {
            processDataEvents(pending, nodeId);
        }
        if (persisted != null && !persisted.isEmpty()) {
            fetchAndProcessPersistedUris(persisted, nodeId);
        }
    }

    private void enqueuePendingDataEvents(final List<DataEvent> events) {
        synchronized (pendingDataLock) {
            if (pendingDataEvents == null) {
                pendingDataEvents = new ArrayList<>();
            }
            pendingDataEvents.addAll(events);
        }
    }

    private boolean hasPendingData() {
        synchronized (pendingDataLock) {
            return (
                (pendingDataEvents != null && !pendingDataEvents.isEmpty()) ||
                (pendingPersistedUris != null && !pendingPersistedUris.isEmpty())
            );
        }
    }

    private void resolveLocalNodeAndProcessPending() {
        if (!resolvingLocalNode.compareAndSet(false, true)) {
            return;
        }
        Wearable.getNodeClient(this)
            .getLocalNode()
            .addOnSuccessListener((node) -> onLocalNodeResolved(node.getId()))
            .addOnFailureListener((e) -> {
                resolvingLocalNode.set(false);
                localNodeRetryAttempts++;
                Log.w(TAG, "Failed to resolve local node id; deferring data events (attempt " + localNodeRetryAttempts + ")", e);
                if (!hasPendingData()) {
                    Log.e(TAG, "Giving up resolving local node id; no pending data events");
                    localNodeRetryAttempts = 0;
                    return;
                }
                if (localNodeRetryAttempts >= LOCAL_NODE_MAX_RETRIES) {
                    Log.e(
                        TAG,
                        "Local node still unresolved after " +
                            localNodeRetryAttempts +
                            " attempts; retrying while pending data events remain"
                    );
                }
                mainHandler.postDelayed(this::resolveLocalNodeAndProcessPending, LOCAL_NODE_RETRY_MS);
            });
    }

    @Override
    public void onDestroy() {
        // Persist pending DataItem URIs before cancelling retries so a later service
        // start can reload them (Wear OS does not always redeliver UUID-backed items).
        persistPendingDataUris();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void persistPendingDataUris() {
        final JSONArray uris = new JSONArray();
        synchronized (pendingDataLock) {
            if (pendingDataEvents != null) {
                for (final DataEvent event : pendingDataEvents) {
                    final Uri uri = event.getDataItem().getUri();
                    if (uri != null) {
                        uris.put(uri.toString());
                    }
                }
                pendingDataEvents = null;
            }
            if (pendingPersistedUris != null) {
                for (final Uri uri : pendingPersistedUris) {
                    if (uri != null) {
                        uris.put(uri.toString());
                    }
                }
                pendingPersistedUris = null;
            }
        }
        final SharedPreferences prefs = getSharedPreferences(CapgoWatchConstants.PREF_EVENT_STORE, MODE_PRIVATE);
        if (uris.length() == 0) {
            prefs.edit().remove(CapgoWatchConstants.PREF_PENDING_DATA_URIS).commit();
            return;
        }
        if (!prefs.edit().putString(CapgoWatchConstants.PREF_PENDING_DATA_URIS, uris.toString()).commit()) {
            Log.w(TAG, "Failed to persist pending data URIs");
        }
    }

    private void reloadPersistedPendingDataUris() {
        final SharedPreferences prefs = getSharedPreferences(CapgoWatchConstants.PREF_EVENT_STORE, MODE_PRIVATE);
        final String raw = prefs.getString(CapgoWatchConstants.PREF_PENDING_DATA_URIS, null);
        if (raw == null || raw.isEmpty()) {
            return;
        }
        prefs.edit().remove(CapgoWatchConstants.PREF_PENDING_DATA_URIS).commit();
        try {
            final JSONArray uris = new JSONArray(raw);
            final List<Uri> loaded = new ArrayList<>();
            for (int i = 0; i < uris.length(); i++) {
                final String uriString = uris.optString(i, null);
                if (uriString == null || uriString.isEmpty()) {
                    continue;
                }
                loaded.add(Uri.parse(uriString));
            }
            if (loaded.isEmpty()) {
                return;
            }
            synchronized (pendingDataLock) {
                if (pendingPersistedUris == null) {
                    pendingPersistedUris = new ArrayList<>();
                }
                pendingPersistedUris.addAll(loaded);
            }
            resolveLocalNodeAndProcessPending();
        } catch (JSONException e) {
            Log.w(TAG, "Resetting corrupted pending data URI store", e);
        }
    }

    private void fetchAndProcessPersistedUris(final List<Uri> uris, final String cachedLocalNodeId) {
        for (final Uri uri : uris) {
            Wearable.getDataClient(this)
                .getDataItem(uri)
                .addOnSuccessListener((item) -> {
                    if (item != null) {
                        processDataItem(item, cachedLocalNodeId);
                    }
                })
                .addOnFailureListener((e) -> Log.w(TAG, "Failed to reload pending DataItem " + uri, e));
        }
    }

    private void processDataEvents(final List<DataEvent> dataEvents, final String cachedLocalNodeId) {
        for (final DataEvent event : dataEvents) {
            if (event.getType() != DataEvent.TYPE_CHANGED) {
                continue;
            }
            processDataItem(event.getDataItem(), cachedLocalNodeId);
        }
    }

    private void processDataItem(final DataItem item, final String cachedLocalNodeId) {
        final Uri itemUri = item.getUri();
        final String path = itemUri.getPath();
        if (path == null) {
            return;
        }

        final boolean isContext = CapgoWatchConstants.PATH_CONTEXT.equals(path);
        final boolean isUserInfo = path.startsWith(CapgoWatchConstants.PATH_USER_INFO);
        if (!isContext && !isUserInfo) {
            return;
        }

        // Skip the phone's own outgoing DataItems (URI host == local node id).
        final String host = itemUri.getHost();
        if (host != null && host.equals(cachedLocalNodeId)) {
            return;
        }

        try {
            final DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
            final String payload = dataMap.getString("payload", "{}");
            final JSONObject json = new JSONObject(payload);
            final JSObject data = new JSObject(json.toString());

            if (isContext) {
                eventStore.saveLastContext(data);
                final JSObject evt = new JSObject();
                evt.put("context", data);
                CapgoWatchEventBridge.dispatch("applicationContextReceived", evt, true);
            } else {
                final JSObject evt = new JSObject();
                evt.put("userInfo", data);
                CapgoWatchEventBridge.dispatch("userInfoReceived", evt, true);
                Wearable.getDataClient(this).deleteDataItems(itemUri);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing data change", e);
            if (isUserInfo) {
                Wearable.getDataClient(this).deleteDataItems(itemUri);
            }
        }
    }

    @Override
    public void onCapabilityChanged(final CapabilityInfo capabilityInfo) {
        refreshReachability();
    }

    @Override
    public void onPeerConnected(final Node peer) {
        refreshReachability();
    }

    @Override
    public void onPeerDisconnected(final Node peer) {
        refreshReachability();
    }

    private void refreshReachability() {
        Wearable.getNodeClient(this)
            .getConnectedNodes()
            .addOnSuccessListener((List<Node> nodes) -> {
                // CapgoWatchEventBridge compares against the last persisted value and
                // dispatches/persists only when reachability actually changed.
                CapgoWatchEventBridge.dispatchReachability(!nodes.isEmpty());
            })
            .addOnFailureListener((e) -> Log.w(TAG, "Failed to refresh reachability", e));
    }
}
