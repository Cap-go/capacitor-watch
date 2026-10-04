package app.capgo.capacitor.watch;

import android.util.Log;
import com.getcapacitor.JSObject;
import java.lang.ref.WeakReference;

/**
 * Bridges watch events between the WearableListenerService and the active plugin instance.
 */
public final class CapgoWatchEventBridge {

    private static final String TAG = "CapgoWatchEventBridge";

    private static volatile WeakReference<CapgoWatchPlugin> pluginRef = new WeakReference<>(null);
    private static volatile CapgoWatchEventStore eventStore;

    private CapgoWatchEventBridge() {}

    public static void initialize(final CapgoWatchEventStore store) {
        eventStore = store;
    }

    public static void registerPlugin(final CapgoWatchPlugin plugin) {
        pluginRef = new WeakReference<>(plugin);
    }

    public static void unregisterPlugin(final CapgoWatchPlugin plugin) {
        final CapgoWatchPlugin current = pluginRef.get();
        if (current == null || current == plugin) {
            pluginRef = new WeakReference<>(null);
        }
    }

    /**
     * @return {@code true} when persisted, or when there is no store (nothing to fill);
     *         {@code false} when rejected at capacity
     */
    public static boolean savePendingReply(final String callbackId, final String nodeId) {
        if (eventStore != null) {
            return eventStore.savePendingReply(callbackId, nodeId);
        }
        // No durable queue — accept, matching PendingReplyManager when eventStore is null.
        return true;
    }

    public static void dispatch(final String eventName, final JSObject payload, final boolean retainUntilConsumed) {
        dispatch(eventName, payload, retainUntilConsumed, null);
    }

    private static final Object REACHABILITY_DISPATCH_LOCK = new Object();

    /**
     * @return {@code true} when the event was delivered live or persisted successfully
     */
    public static boolean dispatch(
        final String eventName,
        final JSObject payload,
        final boolean retainUntilConsumed,
        final String replyNodeId
    ) {
        final CapgoWatchPlugin plugin = pluginRef.get();

        if (plugin != null && plugin.hasWatchListeners(eventName)) {
            plugin.dispatchWatchEvent(eventName, payload, retainUntilConsumed);
            return true;
        }

        if (eventStore == null) {
            return false;
        }

        if ("messageReceivedWithReply".equals(eventName) && replyNodeId != null && !replyNodeId.isEmpty()) {
            final String callbackId = payload.getString("callbackId", null);
            if (callbackId == null || !eventStore.hasPendingReply(callbackId)) {
                Log.w(TAG, "Skipping queue of messageReceivedWithReply without durable pending reply callbackId=" + callbackId);
                return false;
            }
        }

        if (!eventStore.append(eventName, payload, replyNodeId)) {
            final CapgoWatchPlugin pluginAfterFailedAppend = pluginRef.get();
            if (pluginAfterFailedAppend != null && pluginAfterFailedAppend.hasWatchListeners(eventName)) {
                pluginAfterFailedAppend.dispatchWatchEvent(eventName, payload, retainUntilConsumed);
                return true;
            }
            return false;
        }

        final CapgoWatchPlugin pluginAfterAppend = pluginRef.get();
        if (pluginAfterAppend != null && pluginAfterAppend.hasWatchListeners(eventName)) {
            for (final CapgoWatchEventStore.StoredEvent storedEvent : eventStore.drainEventsFor(eventName)) {
                pluginAfterAppend.dispatchWatchEvent(storedEvent.eventName, storedEvent.payload, retainUntilConsumed);
            }
        }
        return true;
    }

    public static void dispatchReachability(final boolean isReachable) {
        synchronized (REACHABILITY_DISPATCH_LOCK) {
            dispatchReachabilityUnlocked(isReachable);
        }
    }

    private static void dispatchReachabilityUnlocked(final boolean isReachable) {
        final JSObject evt = new JSObject();
        evt.put("isReachable", isReachable);

        if (eventStore != null) {
            final CapgoWatchPlugin plugin = pluginRef.get();
            final boolean live = plugin != null && plugin.hasWatchListeners("reachabilityChanged");
            if (live) {
                if (!eventStore.saveLastReachableIfChanged(isReachable)) {
                    return;
                }
                final CapgoWatchPlugin pluginNow = pluginRef.get();
                if (pluginNow != null && pluginNow.hasWatchListeners("reachabilityChanged")) {
                    pluginNow.dispatchWatchEvent("reachabilityChanged", evt, true);
                } else if (!eventStore.append("reachabilityChanged", evt, null)) {
                    eventStore.clearLastReachableIf(isReachable);
                }
                return;
            }

            if (!eventStore.appendReachabilityIfAbsent(isReachable, evt)) {
                return;
            }

            final CapgoWatchPlugin pluginAfterAppend = pluginRef.get();
            if (pluginAfterAppend != null && pluginAfterAppend.hasWatchListeners("reachabilityChanged")) {
                for (final CapgoWatchEventStore.StoredEvent storedEvent : eventStore.drainEventsFor("reachabilityChanged")) {
                    pluginAfterAppend.dispatchWatchEvent(storedEvent.eventName, storedEvent.payload, true);
                }
            }
            return;
        }

        final CapgoWatchPlugin plugin = pluginRef.get();
        if (plugin != null && plugin.hasWatchListeners("reachabilityChanged")) {
            plugin.dispatchWatchEvent("reachabilityChanged", evt, true);
        }
    }
}
