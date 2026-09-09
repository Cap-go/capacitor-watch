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

    /**
     * @return {@code true} when persisted (or no store); {@code false} when rejected at capacity
     */
    public static boolean savePendingReply(final String callbackId, final String nodeId) {
        if (eventStore != null) {
            return eventStore.savePendingReply(callbackId, nodeId);
        }
        return false;
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
        final JSObject evt = new JSObject();
        evt.put("isReachable", isReachable);

        if (eventStore != null) {
            final CapgoWatchPlugin plugin = pluginRef.get();
            final boolean live = plugin != null && plugin.hasWatchListeners("reachabilityChanged");
            if (live) {
                // Live listeners: preference-based change detection.
                if (!eventStore.saveLastReachableIfChanged(isReachable)) {
                    return;
                }
                // Recheck after PREF write — plugin may have been destroyed in between.
                final CapgoWatchPlugin pluginNow = pluginRef.get();
                if (pluginNow != null && pluginNow.hasWatchListeners("reachabilityChanged")) {
                    pluginNow.dispatchWatchEvent("reachabilityChanged", evt, true);
                } else {
                    // Queue so a later listener still receives the transition.
                    // Roll back PREF on commit failure so a later callback can retry.
                    if (!eventStore.append("reachabilityChanged", evt, null)) {
                        // Only roll back if PREF still reflects this failed transition.
                        eventStore.clearLastReachableIf(isReachable);
                    }
                }
                return;
            }

            // Background queue: check + append must be atomic so overlapping callbacks
            // cannot both pass hasQueuedReachability before either persists.
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
