package app.capgo.capacitor.watch;

import android.net.Uri;
import android.util.Log;
import com.getcapacitor.JSObject;
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
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Receives Wear OS events while the phone app process is not running.
 * Events are persisted and replayed when the Capacitor plugin loads.
 */
public class CapgoWatchWearableListenerService extends WearableListenerService {

    private static final String TAG = "CapgoWatchListenerSvc";

    private CapgoWatchEventStore eventStore;

    @Override
    public void onCreate() {
        super.onCreate();
        eventStore = new CapgoWatchEventStore(getApplicationContext());
        CapgoWatchEventBridge.initialize(eventStore);
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
        for (final DataEvent event : dataEvents) {
            if (event.getType() != DataEvent.TYPE_CHANGED) {
                continue;
            }

            final DataItem item = event.getDataItem();
            final Uri itemUri = item.getUri();
            final String path = itemUri.getPath();
            if (path == null) {
                continue;
            }

            final boolean isContext = CapgoWatchConstants.PATH_CONTEXT.equals(path);
            final boolean isUserInfo = path.startsWith(CapgoWatchConstants.PATH_USER_INFO);
            if (!isContext && !isUserInfo) {
                continue;
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
            } catch (JSONException e) {
                Log.e(TAG, "Error processing data change", e);
                if (isUserInfo) {
                    Wearable.getDataClient(this).deleteDataItems(itemUri);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing data change", e);
                if (isUserInfo) {
                    Wearable.getDataClient(this).deleteDataItems(itemUri);
                }
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
            .addOnSuccessListener((List<Node> nodes) -> CapgoWatchEventBridge.dispatchReachability(!nodes.isEmpty()))
            .addOnFailureListener((e) -> Log.w(TAG, "Failed to refresh reachability", e));
    }
}
