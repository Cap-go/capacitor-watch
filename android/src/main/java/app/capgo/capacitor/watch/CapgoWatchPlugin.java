package app.capgo.capacitor.watch;

import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.DataClient;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private static final String PLUGIN_VERSION = "8.0.11";

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

    /** Maps callbackId to sourceNodeId for pending reply messages. */
    private final Map<String, String> pendingReplies = new ConcurrentHashMap<>();

    /** Shared thread pool for all background Wear OS operations. */
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private MessageClient messageClient;
    private DataClient dataClient;

    private final MessageClient.OnMessageReceivedListener messageListener = this::handleMessageReceived;
    private final DataClient.OnDataChangedListener dataListener = this::handleDataChanged;

    @Override
    public void load() {
        messageClient = Wearable.getMessageClient(getContext());
        dataClient = Wearable.getDataClient(getContext());
        messageClient.addListener(messageListener);
        dataClient.addListener(dataListener);
    }

    @Override
    protected void handleOnDestroy() {
        if (messageClient != null) {
            messageClient.removeListener(messageListener);
        }
        if (dataClient != null) {
            dataClient.removeListener(dataListener);
        }
        executor.shutdown();
    }

    // ── Incoming message / data handlers ─────────────────────────────────────

    private void handleMessageReceived(MessageEvent event) {
        final String path = event.getPath();
        final String nodeId = event.getSourceNodeId();
        final String payload = new String(event.getData(), StandardCharsets.UTF_8);

        try {
            final JSONObject json = new JSONObject(payload);
            final JSObject messageData = new JSObject(json.toString());

            if (path != null && path.startsWith(PATH_MESSAGE_WITH_REPLY)) {
                final String callbackId = UUID.randomUUID().toString();
                pendingReplies.put(callbackId, nodeId);

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
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                final DataItem item = event.getDataItem();
                final String path = item.getUri().getPath();
                try {
                    final DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
                    final String payload = dataMap.getString("payload", "{}");
                    final JSONObject json = new JSONObject(payload);
                    final JSObject data = new JSObject(json.toString());

                    if (path != null && path.startsWith(PATH_CONTEXT)) {
                        final JSObject evt = new JSObject();
                        evt.put("context", data);
                        notifyListeners("applicationContextReceived", evt);
                    } else if (path != null && path.startsWith(PATH_USER_INFO)) {
                        final JSObject evt = new JSObject();
                        evt.put("userInfo", data);
                        notifyListeners("userInfoReceived", evt);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing data change", e);
                }
            }
        }
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
        executor.execute(
            () -> {
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
            }
        );
    }

    @PluginMethod
    public void updateApplicationContext(final PluginCall call) {
        final JSObject context = call.getObject("context");
        if (context == null) {
            call.reject("Missing required parameter: 'context'");
            return;
        }

        executor.execute(
            () -> {
                try {
                    final PutDataMapRequest request = PutDataMapRequest.create(PATH_CONTEXT);
                    request.getDataMap().putString("payload", context.toString());
                    request.setUrgent();
                    Tasks.await(dataClient.putDataItem(request.asPutDataRequest()));
                    call.resolve();
                } catch (ExecutionException | InterruptedException e) {
                    call.reject("Failed to update application context: " + e.getMessage(), e);
                }
            }
        );
    }

    @PluginMethod
    public void transferUserInfo(final PluginCall call) {
        final JSObject userInfo = call.getObject("userInfo");
        if (userInfo == null) {
            call.reject("Missing required parameter: 'userInfo'");
            return;
        }

        final String path = PATH_USER_INFO + UUID.randomUUID();
        executor.execute(
            () -> {
                try {
                    final PutDataMapRequest request = PutDataMapRequest.create(path);
                    request.getDataMap().putString("payload", userInfo.toString());
                    request.setUrgent();
                    Tasks.await(dataClient.putDataItem(request.asPutDataRequest()));
                    call.resolve();
                } catch (ExecutionException | InterruptedException e) {
                    call.reject("Failed to transfer user info: " + e.getMessage(), e);
                }
            }
        );
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

        final String nodeId = pendingReplies.remove(callbackId);
        if (nodeId == null) {
            call.reject("No pending reply found for callbackId: " + callbackId);
            return;
        }

        final byte[] payload = data.toString().getBytes(StandardCharsets.UTF_8);
        final String replyPath = PATH_REPLY + callbackId;
        executor.execute(
            () -> {
                try {
                    Tasks.await(messageClient.sendMessage(nodeId, replyPath, payload));
                    call.resolve();
                } catch (ExecutionException | InterruptedException e) {
                    call.reject("Failed to send reply: " + e.getMessage(), e);
                }
            }
        );
    }

    @PluginMethod
    public void getInfo(final PluginCall call) {
        executor.execute(
            () -> {
                try {
                    final List<Node> nodes = Tasks.await(Wearable.getNodeClient(getContext()).getConnectedNodes());
                    final boolean isReachable = !nodes.isEmpty();
                    final JSObject ret = new JSObject();
                    ret.put("isSupported", true);
                    ret.put("isPaired", isReachable);
                    ret.put("isWatchAppInstalled", isReachable);
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
            }
        );
    }

    @PluginMethod
    public void getPluginVersion(final PluginCall call) {
        final JSObject ret = new JSObject();
        ret.put("version", PLUGIN_VERSION);
        call.resolve(ret);
    }
}
