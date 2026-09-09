package app.capgo.capacitor.watch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.getcapacitor.JSObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Persists watch events so they can be replayed after process restart.
 * Skips unreadable entries instead of failing the entire batch.
 */
public class CapgoWatchEventStore {

    private static final String TAG = "CapgoWatchEventStore";
    private static final String KEY_EVENTS = "events";
    private static final String KEY_PENDING_REPLIES = "pending_replies";
    private static final Object STORE_LOCK = new Object();

    private final SharedPreferences preferences;

    public CapgoWatchEventStore(final Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(CapgoWatchConstants.PREF_EVENT_STORE, Context.MODE_PRIVATE);
    }

    public boolean append(final String eventName, final JSObject payload) {
        return append(eventName, payload, null);
    }

    /**
     * Persist an event for later replay.
     *
     * @return true when the events preference commit succeeded
     */
    public boolean append(final String eventName, final JSObject payload, final String replyNodeId) {
        synchronized (STORE_LOCK) {
            try {
                return appendUnlocked(eventName, payload, replyNodeId);
            } catch (JSONException e) {
                Log.e(TAG, "Failed to persist event " + eventName, e);
                return false;
            }
        }
    }

    /** Clear PREF_LAST_REACHABLE so a later reachability callback can retry persistence. */
    public void clearLastReachable() {
        clearLastReachableIf(null);
    }

    /**
     * Atomically clear PREF_LAST_REACHABLE only when it still matches {@code expected}
     * (or clear unconditionally when {@code expected} is null).
     */
    public boolean clearLastReachableIf(final Boolean expected) {
        synchronized (STORE_LOCK) {
            if (expected != null) {
                if (!preferences.contains(CapgoWatchConstants.PREF_LAST_REACHABLE)) {
                    return false;
                }
                if (preferences.getBoolean(CapgoWatchConstants.PREF_LAST_REACHABLE, false) != expected) {
                    return false;
                }
            }
            if (!preferences.edit().remove(CapgoWatchConstants.PREF_LAST_REACHABLE).commit()) {
                Log.w(TAG, "Failed to clear last reachable state");
                return false;
            }
            return true;
        }
    }

    /**
     * Atomically dedupe + persist a background reachabilityChanged event.
     *
     * @return true when the event was queued (caller may attempt live delivery)
     */
    public boolean appendReachabilityIfAbsent(final boolean isReachable, final JSObject payload) {
        synchronized (STORE_LOCK) {
            if (hasQueuedReachabilityUnlocked(isReachable)) {
                // Align PREF with the already-queued value if a prior PREF write failed.
                if (
                    !preferences.contains(CapgoWatchConstants.PREF_LAST_REACHABLE) ||
                    preferences.getBoolean(CapgoWatchConstants.PREF_LAST_REACHABLE, false) != isReachable
                ) {
                    forceSaveLastReachableUnlocked(isReachable);
                }
                return false;
            }
            // Skip when PREF already matches only if no opposite value remains queued.
            // Opposite-queued + stale PREF must still accept the newer transition.
            if (
                preferences.contains(CapgoWatchConstants.PREF_LAST_REACHABLE) &&
                preferences.getBoolean(CapgoWatchConstants.PREF_LAST_REACHABLE, false) == isReachable &&
                !hasQueuedReachabilityUnlocked(!isReachable)
            ) {
                return false;
            }
            try {
                if (!appendUnlocked("reachabilityChanged", payload, null)) {
                    return false;
                }
            } catch (JSONException e) {
                Log.e(TAG, "Failed to persist reachabilityChanged event", e);
                return false;
            }
            // Event commit succeeded — PREF write is best-effort so callers still drain.
            forceSaveLastReachableUnlocked(isReachable);
            return true;
        }
    }

    private boolean appendUnlocked(final String eventName, final JSObject payload, final String replyNodeId) throws JSONException {
        final JSONArray events = readEventsArray();
        final JSONObject entry = new JSONObject();
        entry.put("eventName", eventName);
        entry.put("payload", new JSONObject(payload.toString()));
        entry.put("timestamp", System.currentTimeMillis());
        if (replyNodeId != null && !replyNodeId.isEmpty()) {
            entry.put("replyNodeId", replyNodeId);
        }
        events.put(entry);
        final LimitResult limited = enforceEventQueueLimits(events);
        if (!preferences.edit().putString(KEY_EVENTS, limited.retained.toString()).commit()) {
            Log.w(TAG, "Failed to commit persisted event " + eventName);
            // Preserve PREF_LAST_REACHABLE when the events commit fails.
            return false;
        }
        applyReachabilityPrefAfterDrop(limited.retained, limited.droppedReachability);
        return true;
    }

    public List<StoredEvent> drainAll() {
        synchronized (STORE_LOCK) {
            return drainMatchingEvents(null);
        }
    }

    public List<StoredEvent> drainEventsFor(final String eventName) {
        synchronized (STORE_LOCK) {
            return drainMatchingEvents(eventName);
        }
    }

    private List<StoredEvent> drainMatchingEvents(final String eventNameFilter) {
        final JSONArray events = readEventsArray();
        final List<StoredEvent> drained = new ArrayList<>();
        final JSONArray retained = new JSONArray();
        final long now = System.currentTimeMillis();
        boolean droppedReachability = false;

        for (int i = 0; i < events.length(); i++) {
            try {
                final JSONObject entry = events.getJSONObject(i);
                final String eventName = entry.getString("eventName");
                if (eventNameFilter != null && !eventNameFilter.equals(eventName)) {
                    retained.put(entry);
                    continue;
                }
                final long timestamp = entry.optLong("timestamp", now);
                if (now - timestamp > CapgoWatchConstants.MAX_EVENT_RETENTION_MS) {
                    // Discard expired matching events instead of replaying them.
                    if ("reachabilityChanged".equals(eventName)) {
                        droppedReachability = true;
                    }
                    continue;
                }
                final JSONObject payloadJson = entry.getJSONObject("payload");
                final String replyNodeId = entry.optString("replyNodeId", null);
                drained.add(new StoredEvent(eventName, new JSObject(payloadJson.toString()), replyNodeId, timestamp));
            } catch (JSONException e) {
                Log.w(TAG, "Dropping unreadable stored event at index " + i, e);
            }
        }

        if (!preferences.edit().putString(KEY_EVENTS, retained.toString()).commit()) {
            Log.w(TAG, "Failed to commit drained event store");
            // Preserve PREF_LAST_REACHABLE when the events commit fails.
            return new ArrayList<>();
        }
        applyReachabilityPrefAfterDrop(retained, droppedReachability);
        return drained;
    }

    /**
     * Persist a pending-reply registration.
     *
     * <p>Age-expired entries are pruned first. When the store is already at
     * {@link CapgoWatchConstants#MAX_PENDING_REPLIES}, the new registration is
     * rejected (existing durable callbacks are preserved). Callers must explicitly
     * reject/expire the rejected request — never silently drop older mappings.
     *
     * @return {@code true} when the registration was persisted
     */
    public boolean savePendingReply(final String callbackId, final String nodeId) {
        synchronized (STORE_LOCK) {
            final JSONObject pending = readPendingRepliesObject();
            try {
                pruneExpiredPendingRepliesUnlocked(pending);
                final boolean updatingExisting = pending.has(callbackId);
                if (!updatingExisting && pending.length() >= CapgoWatchConstants.MAX_PENDING_REPLIES) {
                    Log.w(
                        TAG,
                        "Rejecting pending reply callbackId=" +
                            callbackId +
                            "; durable queue at capacity " +
                            CapgoWatchConstants.MAX_PENDING_REPLIES
                    );
                    return false;
                }
                final JSONObject entry = new JSONObject();
                entry.put("nodeId", nodeId);
                entry.put("createdAt", System.currentTimeMillis());
                pending.put(callbackId, entry);
                if (!preferences.edit().putString(KEY_PENDING_REPLIES, pending.toString()).commit()) {
                    Log.w(TAG, "Failed to commit pending reply for " + callbackId);
                    return false;
                }
                return true;
            } catch (JSONException e) {
                Log.e(TAG, "Failed to persist pending reply for " + callbackId, e);
                return false;
            }
        }
    }

    private void pruneExpiredPendingRepliesUnlocked(final JSONObject pending) {
        final long now = System.currentTimeMillis();
        final List<String> expired = new ArrayList<>();
        final Iterator<String> keys = pending.keys();
        while (keys.hasNext()) {
            final String key = keys.next();
            final JSONObject entry = pending.optJSONObject(key);
            if (entry == null || now - entry.optLong("createdAt", now) > CapgoWatchConstants.MAX_PENDING_REPLY_AGE_MS) {
                expired.add(key);
            }
        }
        for (final String key : expired) {
            pending.remove(key);
        }
    }

    public void removePendingReply(final String callbackId) {
        synchronized (STORE_LOCK) {
            final JSONObject pending = readPendingRepliesObject();
            pending.remove(callbackId);
            preferences.edit().putString(KEY_PENDING_REPLIES, pending.toString()).commit();
        }
    }

    public Map<String, PendingReplyRecord> loadPendingReplies() {
        synchronized (STORE_LOCK) {
            final JSONObject pending = readPendingRepliesObject();
            final Map<String, PendingReplyRecord> records = new HashMap<>();
            final Iterator<String> keys = pending.keys();
            while (keys.hasNext()) {
                final String callbackId = keys.next();
                try {
                    final JSONObject entry = pending.getJSONObject(callbackId);
                    records.put(callbackId, new PendingReplyRecord(entry.getString("nodeId"), entry.getLong("createdAt")));
                } catch (JSONException e) {
                    Log.w(TAG, "Skipping unreadable pending reply for " + callbackId, e);
                }
            }
            return records;
        }
    }

    public void saveLastContext(final JSObject context) {
        synchronized (STORE_LOCK) {
            preferences.edit().putString(CapgoWatchConstants.PREF_LAST_CONTEXT, context.toString()).commit();
        }
    }

    /**
     * Persist reachability when it changed.
     *
     * @return true when the value changed (or was previously unset) and was saved
     */
    public boolean saveLastReachableIfChanged(final boolean isReachable) {
        synchronized (STORE_LOCK) {
            if (preferences.contains(CapgoWatchConstants.PREF_LAST_REACHABLE)) {
                final boolean previous = preferences.getBoolean(CapgoWatchConstants.PREF_LAST_REACHABLE, false);
                if (previous == isReachable) {
                    return false;
                }
            }
            if (!preferences.edit().putBoolean(CapgoWatchConstants.PREF_LAST_REACHABLE, isReachable).commit()) {
                Log.w(TAG, "Failed to commit last reachable state");
                return false;
            }
            return true;
        }
    }

    /**
     * True when a retained queued reachabilityChanged event already has this value.
     */
    public boolean hasQueuedReachability(final boolean isReachable) {
        synchronized (STORE_LOCK) {
            return hasQueuedReachabilityUnlocked(isReachable);
        }
    }

    private boolean hasQueuedReachabilityUnlocked(final boolean isReachable) {
        final JSONArray events = readEventsArray();
        final long now = System.currentTimeMillis();
        for (int i = 0; i < events.length(); i++) {
            try {
                final JSONObject entry = events.getJSONObject(i);
                if (!"reachabilityChanged".equals(entry.optString("eventName", ""))) {
                    continue;
                }
                final long timestamp = entry.optLong("timestamp", now);
                if (now - timestamp > CapgoWatchConstants.MAX_EVENT_RETENTION_MS) {
                    continue;
                }
                final JSONObject payload = entry.optJSONObject("payload");
                if (payload != null && payload.optBoolean("isReachable", false) == isReachable) {
                    return true;
                }
            } catch (JSONException e) {
                Log.w(TAG, "Skipping unreadable reachability event at index " + i, e);
            }
        }
        return false;
    }

    private boolean forceSaveLastReachableUnlocked(final boolean isReachable) {
        if (!preferences.edit().putBoolean(CapgoWatchConstants.PREF_LAST_REACHABLE, isReachable).commit()) {
            Log.w(TAG, "Failed to commit last reachable state");
            return false;
        }
        return true;
    }

    public JSObject loadLastContext() {
        synchronized (STORE_LOCK) {
            final String raw = preferences.getString(CapgoWatchConstants.PREF_LAST_CONTEXT, null);
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            try {
                return new JSObject(raw);
            } catch (Exception e) {
                Log.w(TAG, "Failed to load stored application context", e);
                return null;
            }
        }
    }

    private LimitResult enforceEventQueueLimits(final JSONArray events) {
        final long now = System.currentTimeMillis();
        final JSONArray retained = new JSONArray();
        boolean droppedReachability = false;

        for (int i = 0; i < events.length(); i++) {
            try {
                final JSONObject entry = events.getJSONObject(i);
                final long timestamp = entry.optLong("timestamp", now);
                if (now - timestamp <= CapgoWatchConstants.MAX_EVENT_RETENTION_MS) {
                    retained.put(entry);
                } else if ("reachabilityChanged".equals(entry.optString("eventName", ""))) {
                    droppedReachability = true;
                }
            } catch (JSONException e) {
                Log.w(TAG, "Dropping unreadable stored event at index " + i, e);
            }
        }

        while (retained.length() > CapgoWatchConstants.MAX_STORED_EVENTS) {
            if (isReachabilityEvent(retained.optJSONObject(0))) {
                droppedReachability = true;
            }
            retained.remove(0);
        }

        int retainedBytes = retained.toString().getBytes(StandardCharsets.UTF_8).length;
        while (retained.length() > 0 && retainedBytes > CapgoWatchConstants.MAX_STORED_EVENTS_BYTES) {
            final JSONObject removed = retained.optJSONObject(0);
            if (isReachabilityEvent(removed)) {
                droppedReachability = true;
            }
            final int entryBytes = removed != null ? removed.toString().getBytes(StandardCharsets.UTF_8).length : 0;
            retained.remove(0);
            if (retained.length() == 0) {
                retainedBytes = 2; // "[]"
            } else {
                // Drop the entry payload plus the separating comma.
                retainedBytes -= entryBytes + 1;
            }
        }

        return new LimitResult(retained, droppedReachability);
    }

    /**
     * After unreplayed reachability events are dropped, recompute PREF from the latest
     * retained reachability event (if any). Do not clear when a newer event remains.
     */
    private void applyReachabilityPrefAfterDrop(final JSONArray retained, final boolean droppedReachability) {
        if (!droppedReachability) {
            return;
        }
        final Boolean latest = latestQueuedReachability(retained);
        if (latest == null) {
            if (!preferences.edit().remove(CapgoWatchConstants.PREF_LAST_REACHABLE).commit()) {
                Log.w(TAG, "Failed to clear last reachable state after drop");
            }
        } else if (!preferences.edit().putBoolean(CapgoWatchConstants.PREF_LAST_REACHABLE, latest).commit()) {
            Log.w(TAG, "Failed to recompute last reachable state after drop");
        }
    }

    private static Boolean latestQueuedReachability(final JSONArray events) {
        final long now = System.currentTimeMillis();
        Boolean latest = null;
        long latestTs = Long.MIN_VALUE;
        for (int i = 0; i < events.length(); i++) {
            final JSONObject entry = events.optJSONObject(i);
            if (!isReachabilityEvent(entry)) {
                continue;
            }
            final long timestamp = entry.optLong("timestamp", now);
            if (now - timestamp > CapgoWatchConstants.MAX_EVENT_RETENTION_MS) {
                continue;
            }
            if (timestamp >= latestTs) {
                latestTs = timestamp;
                final JSONObject payload = entry.optJSONObject("payload");
                latest = payload != null && payload.optBoolean("isReachable", false);
            }
        }
        return latest;
    }

    private static boolean isReachabilityEvent(final JSONObject entry) {
        return entry != null && "reachabilityChanged".equals(entry.optString("eventName", ""));
    }

    private static final class LimitResult {

        final JSONArray retained;
        final boolean droppedReachability;

        LimitResult(final JSONArray retained, final boolean droppedReachability) {
            this.retained = retained;
            this.droppedReachability = droppedReachability;
        }
    }

    private JSONArray readEventsArray() {
        final String raw = preferences.getString(KEY_EVENTS, "[]");
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            Log.w(TAG, "Resetting corrupted event store", e);
            return new JSONArray();
        }
    }

    private JSONObject readPendingRepliesObject() {
        final String raw = preferences.getString(KEY_PENDING_REPLIES, "{}");
        try {
            return new JSONObject(raw);
        } catch (JSONException e) {
            Log.w(TAG, "Resetting corrupted pending reply store", e);
            return new JSONObject();
        }
    }

    public static final class StoredEvent {

        public final String eventName;
        public final JSObject payload;
        public final String replyNodeId;
        public final long timestamp;

        StoredEvent(final String eventName, final JSObject payload, final String replyNodeId, final long timestamp) {
            this.eventName = eventName;
            this.payload = payload;
            this.replyNodeId = replyNodeId;
            this.timestamp = timestamp;
        }
    }

    public static final class PendingReplyRecord {

        public final String nodeId;
        public final long createdAt;

        PendingReplyRecord(final String nodeId, final long createdAt) {
            this.nodeId = nodeId;
            this.createdAt = createdAt;
        }
    }
}
