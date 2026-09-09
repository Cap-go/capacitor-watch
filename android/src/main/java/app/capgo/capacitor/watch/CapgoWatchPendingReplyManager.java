package app.capgo.capacitor.watch;

import android.util.Log;
import com.getcapacitor.PluginCall;
import com.google.android.gms.wearable.MessageClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Tracks incoming/outgoing reply callbacks with scheduled expiry. */
final class CapgoWatchPendingReplyManager {

    private static final String TAG = "CapgoWatchPendingReplyMgr";

    private final long ttlMs;
    private final Object lifecycleLock = new Object();
    private final ScheduledThreadPoolExecutor scheduler;
    private final Map<String, IncomingPendingReply> incoming = new ConcurrentHashMap<>();
    private final Map<String, OutgoingPendingReply> outgoing = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> incomingExpiryTasks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> outgoingExpiryTasks = new ConcurrentHashMap<>();

    private volatile boolean shutdown = false;
    private volatile MessageClient messageClient;
    private volatile CapgoWatchEventStore eventStore;

    CapgoWatchPendingReplyManager(final long ttlMs) {
        this.ttlMs = ttlMs;
        scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
    }

    void initialize(final MessageClient messageClient, final CapgoWatchEventStore eventStore) {
        this.messageClient = messageClient;
        this.eventStore = eventStore;
        restorePersistedIncoming();
    }

    void shutdown() {
        synchronized (lifecycleLock) {
            shutdown = true;
            for (final ScheduledFuture<?> task : incomingExpiryTasks.values()) {
                task.cancel(false);
            }
            for (final ScheduledFuture<?> task : outgoingExpiryTasks.values()) {
                task.cancel(false);
            }
            incomingExpiryTasks.clear();
            outgoingExpiryTasks.clear();

            for (final OutgoingPendingReply pending : outgoing.values()) {
                pending.call.reject("Watch plugin destroyed");
            }
            outgoing.clear();
            incoming.clear();
            scheduler.shutdownNow();
        }
    }

    void registerIncoming(final String callbackId, final String nodeId) {
        incoming.put(callbackId, new IncomingPendingReply(nodeId, System.currentTimeMillis()));
        if (eventStore != null) {
            eventStore.savePendingReply(callbackId, nodeId);
        }
        scheduleIncomingExpiry(callbackId);
    }

    void restoreIncoming(final String callbackId, final String nodeId, final long createdAt) {
        incoming.put(callbackId, new IncomingPendingReply(nodeId, createdAt));
        scheduleIncomingExpiry(callbackId, createdAt);
    }

    IncomingPendingReply claimIncoming(final String callbackId) {
        cancelIncomingExpiry(callbackId);
        if (eventStore != null) {
            eventStore.removePendingReply(callbackId);
        }
        return incoming.remove(callbackId);
    }

    IncomingPendingReply removeIncoming(final String callbackId) {
        return claimIncoming(callbackId);
    }

    IncomingPendingReply getIncoming(final String callbackId) {
        return incoming.get(callbackId);
    }

    boolean registerOutgoing(final String callbackId, final PluginCall call) {
        synchronized (lifecycleLock) {
            if (shutdown) {
                call.reject("Watch plugin destroyed");
                return false;
            }
            outgoing.put(callbackId, new OutgoingPendingReply(call));
            try {
                scheduleOutgoingExpiry(callbackId);
            } catch (RejectedExecutionException e) {
                outgoing.remove(callbackId);
                call.reject("Watch plugin destroyed");
                return false;
            }
            return true;
        }
    }

    OutgoingPendingReply removeOutgoing(final String callbackId) {
        cancelOutgoingExpiry(callbackId);
        return outgoing.remove(callbackId);
    }

    void rejectOutgoing(final String callbackId, final String message) {
        final OutgoingPendingReply pending = removeOutgoing(callbackId);
        if (pending != null) {
            pending.call.reject(message);
        }
    }

    private void restorePersistedIncoming() {
        if (eventStore == null) {
            return;
        }
        for (final Map.Entry<String, CapgoWatchEventStore.PendingReplyRecord> entry : eventStore.loadPendingReplies().entrySet()) {
            restoreIncoming(entry.getKey(), entry.getValue().nodeId, entry.getValue().createdAt);
        }
    }

    private void scheduleIncomingExpiry(final String callbackId) {
        scheduleIncomingExpiry(callbackId, System.currentTimeMillis());
    }

    private void scheduleIncomingExpiry(final String callbackId, final long createdAt) {
        cancelIncomingExpiry(callbackId);
        final long delayMs = Math.max(0L, ttlMs - (System.currentTimeMillis() - createdAt));
        try {
            incomingExpiryTasks.put(callbackId, scheduler.schedule(() -> expireIncoming(callbackId), delayMs, TimeUnit.MILLISECONDS));
        } catch (RejectedExecutionException e) {
            abandonIncoming(callbackId);
        }
    }

    private void scheduleOutgoingExpiry(final String callbackId) {
        cancelOutgoingExpiry(callbackId);
        // Let RejectedExecutionException propagate to registerOutgoing so sendMessage can settle the PluginCall.
        outgoingExpiryTasks.put(callbackId, scheduler.schedule(() -> expireOutgoing(callbackId), ttlMs, TimeUnit.MILLISECONDS));
    }

    private void abandonIncoming(final String callbackId) {
        incoming.remove(callbackId);
        incomingExpiryTasks.remove(callbackId);
        if (eventStore != null) {
            eventStore.removePendingReply(callbackId);
        }
    }

    private void cancelIncomingExpiry(final String callbackId) {
        final ScheduledFuture<?> task = incomingExpiryTasks.remove(callbackId);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void cancelOutgoingExpiry(final String callbackId) {
        final ScheduledFuture<?> task = outgoingExpiryTasks.remove(callbackId);
        if (task != null) {
            task.cancel(false);
        }
    }

    private void expireIncoming(final String callbackId) {
        final IncomingPendingReply pending = incoming.remove(callbackId);
        incomingExpiryTasks.remove(callbackId);
        if (pending == null) {
            return;
        }
        if (eventStore != null) {
            eventStore.removePendingReply(callbackId);
        }
        sendReplyToWatch(pending.nodeId, callbackId, "{}");
        Log.w(TAG, "Expired incoming reply callbackId=" + callbackId);
    }

    private void expireOutgoing(final String callbackId) {
        final OutgoingPendingReply pending = removeOutgoing(callbackId);
        if (pending != null) {
            pending.call.reject("Timed out waiting for watch reply");
            Log.w(TAG, "Expired outgoing reply callbackId=" + callbackId);
        }
    }

    private void sendReplyToWatch(final String nodeId, final String callbackId, final String payload) {
        if (messageClient == null) {
            return;
        }
        final byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        final String replyPath = CapgoWatchConstants.PATH_REPLY + callbackId;
        messageClient
            .sendMessage(nodeId, replyPath, bytes)
            .addOnFailureListener((e) -> Log.w(TAG, "Failed to send expired reply to watch", e));
    }

    static final class IncomingPendingReply {

        final String nodeId;
        final long createdAt;

        IncomingPendingReply(final String nodeId, final long createdAt) {
            this.nodeId = nodeId;
            this.createdAt = createdAt;
        }
    }

    static final class OutgoingPendingReply {

        final PluginCall call;

        OutgoingPendingReply(final PluginCall call) {
            this.call = call;
        }
    }
}
