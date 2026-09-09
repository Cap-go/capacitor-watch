package app.capgo.capacitor.watch;

import com.getcapacitor.JSObject;
import java.lang.ref.WeakReference;

/**
 * Bridges watch events between the WearableListenerService and the active plugin instance.
 */
public final class CapgoWatchEventBridge {

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

    public static void savePendingReply(final String callbackId, final String nodeId) {
        if (eventStore != null) {
            eventStore.savePendingReply(callbackId, nodeId);
        }
    }

    public static void dispatch(final String eventName, final JSObject payload, final boolean retainUntilConsumed) {
        dispatch(eventName, payload, retainUntilConsumed, null);
    }

    public static void dispatch(
        final String eventName,
        final JSObject payload,
        final boolean retainUntilConsumed,
        final String replyNodeId
    ) {
        final CapgoWatchPlugin plugin = pluginRef.get();

        if (plugin != null && plugin.hasWatchListeners(eventName)) {
            plugin.dispatchWatchEvent(eventName, payload, retainUntilConsumed);
            return;
        }

        if (eventStore != null) {
            eventStore.append(eventName, payload, replyNodeId);

            final CapgoWatchPlugin pluginAfterAppend = pluginRef.get();
            if (pluginAfterAppend != null && pluginAfterAppend.hasWatchListeners(eventName)) {
                for (final CapgoWatchEventStore.StoredEvent storedEvent : eventStore.drainEventsFor(eventName)) {
                    pluginAfterAppend.dispatchWatchEvent(storedEvent.eventName, storedEvent.payload, retainUntilConsumed);
                }
            }
        }
    }

    public static void dispatchReachability(final boolean isReachable) {
        if (eventStore != null && !eventStore.saveLastReachableIfChanged(isReachable)) {
            return;
        }

        final JSObject evt = new JSObject();
        evt.put("isReachable", isReachable);
        dispatch("reachabilityChanged", evt, true);
    }
}
