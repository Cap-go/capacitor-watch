package app.capgo.capacitor.watch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.getcapacitor.JSObject;
import java.util.ArrayList;
import java.util.List;
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

    private final SharedPreferences preferences;

    public CapgoWatchEventStore(final Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(CapgoWatchConstants.PREF_EVENT_STORE, Context.MODE_PRIVATE);
    }

    public synchronized void append(final String eventName, final JSObject payload) {
        final JSONArray events = readEventsArray();
        final JSONObject entry = new JSONObject();
        try {
            entry.put("eventName", eventName);
            entry.put("payload", new JSONObject(payload.toString()));
            entry.put("timestamp", System.currentTimeMillis());
            events.put(entry);
            preferences.edit().putString(KEY_EVENTS, events.toString()).apply();
        } catch (JSONException e) {
            Log.e(TAG, "Failed to persist event " + eventName, e);
        }
    }

    public synchronized List<StoredEvent> drainAll() {
        final JSONArray events = readEventsArray();
        final List<StoredEvent> drained = new ArrayList<>();
        final JSONArray retained = new JSONArray();

        for (int i = 0; i < events.length(); i++) {
            try {
                final JSONObject entry = events.getJSONObject(i);
                final String eventName = entry.getString("eventName");
                final JSONObject payloadJson = entry.getJSONObject("payload");
                drained.add(new StoredEvent(eventName, new JSObject(payloadJson.toString())));
            } catch (JSONException e) {
                Log.w(TAG, "Skipping unreadable stored event at index " + i, e);
            }
        }

        preferences.edit().putString(KEY_EVENTS, retained.toString()).apply();
        return drained;
    }

    public synchronized void saveLastContext(final JSObject context) {
        preferences.edit().putString(CapgoWatchConstants.PREF_LAST_CONTEXT, context.toString()).apply();
    }

    public synchronized JSObject loadLastContext() {
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

    private JSONArray readEventsArray() {
        final String raw = preferences.getString(KEY_EVENTS, "[]");
        try {
            return new JSONArray(raw);
        } catch (JSONException e) {
            Log.w(TAG, "Resetting corrupted event store", e);
            return new JSONArray();
        }
    }

    public static final class StoredEvent {

        public final String eventName;
        public final JSObject payload;

        StoredEvent(final String eventName, final JSObject payload) {
            this.eventName = eventName;
            this.payload = payload;
        }
    }
}
