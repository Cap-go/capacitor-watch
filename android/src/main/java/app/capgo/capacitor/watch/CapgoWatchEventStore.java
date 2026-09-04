package app.capgo.capacitor.watch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.getcapacitor.JSObject;
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
 * Skips individual unreadable entries instead of failing the entire batch.
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

    public void append(final String eventName, final JSObject payload) {
        append(eventName, payload, null);
    }

    public void append(final String eventName, final JSObject payload, final String replyNodeId) {
        synchronized (STORE_LOCK) {
            final JSONArray events = readEventsArray();
            final JSONObject entry = new JSONObject();
            try {
                entry.put("eventName", eventName);
                entry.put("payload", new JSONObject(payload.toString()));
                entry.put("timestamp", System.currentTimeMillis());
                if (replyNodeId != null && !replyNodeId.isEmpty()) {
                    entry.put("replyNodeId", replyNodeId);
                }
                events.put(entry);
                if (!preferences.edit().putString(KEY_EVENTS, events.toString()).commit()) {
                    Log.w(TAG, "Failed to commit persisted event " + eventName);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Failed to persist event " + eventName, e);
            }
        }
    }

    public List<StoredEvent> drainAll() {
        synchronized (STORE_LOCK) {
            final JSONArray events = readEventsArray();
            final List<StoredEvent> drained = new ArrayList<>();
            final JSONArray retained = new JSONArray();

            for (int i = 0; i < events.length(); i++) {
                try {
                    final JSONObject entry = events.getJSONObject(i);
                    final String eventName = entry.getString("eventName");
                    final JSONObject payloadJson = entry.getJSONObject("payload");
                    final String replyNodeId = entry.optString("replyNodeId", null);
                    drained.add(new StoredEvent(eventName, new JSObject(payloadJson.toString()), replyNodeId));
                } catch (JSONException e) {
                    Log.w(TAG, "Retaining unreadable stored event at index " + i, e);
                    try {
                        retained.put(events.get(i));
                    } catch (JSONException retainError) {
                        Log.w(TAG, "Dropping completely unreadable stored event at index " + i, retainError);
                    }
                }
            }

            if (!preferences.edit().putString(KEY_EVENTS, retained.toString()).commit()) {
                Log.w(TAG, "Failed to commit drained event store");
            }
            return drained;
        }
    }

    public void savePendingReply(final String callbackId, final String nodeId) {
        synchronized (STORE_LOCK) {
            final JSONObject pending = readPendingRepliesObject();
            try {
                final JSONObject entry = new JSONObject();
                entry.put("nodeId", nodeId);
                entry.put("createdAt", System.currentTimeMillis());
                pending.put(callbackId, entry);
                if (!preferences.edit().putString(KEY_PENDING_REPLIES, pending.toString()).commit()) {
                    Log.w(TAG, "Failed to commit pending reply for " + callbackId);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Failed to persist pending reply for " + callbackId, e);
            }
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

        StoredEvent(final String eventName, final JSObject payload, final String replyNodeId) {
            this.eventName = eventName;
            this.payload = payload;
            this.replyNodeId = replyNodeId;
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
