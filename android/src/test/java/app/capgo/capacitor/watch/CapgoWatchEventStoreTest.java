package app.capgo.capacitor.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.getcapacitor.JSObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class CapgoWatchEventStoreTest {

    private CapgoWatchEventStore store;

    @Before
    public void setUp() {
        final Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(CapgoWatchConstants.PREF_EVENT_STORE, Context.MODE_PRIVATE).edit().clear().commit();
        store = new CapgoWatchEventStore(context);
    }

    @Test
    public void appendEvictsOldestEventsWhenOverCountLimit() {
        for (int i = 0; i < CapgoWatchConstants.MAX_STORED_EVENTS + 5; i++) {
            final JSObject payload = new JSObject();
            payload.put("index", i);
            store.append("messageReceived", payload);
        }

        final var events = store.drainAll();
        assertEquals(CapgoWatchConstants.MAX_STORED_EVENTS, events.size());
        assertEquals(5, events.get(0).payload.getInteger("index").intValue());
        assertEquals(
            CapgoWatchConstants.MAX_STORED_EVENTS + 4,
            events
                .get(events.size() - 1)
                .payload.getInteger("index")
                .intValue()
        );
    }

    @Test
    public void appendDropsExpiredEvents() throws Exception {
        final Context context = ApplicationProvider.getApplicationContext();
        final long expiredAt = System.currentTimeMillis() - CapgoWatchConstants.MAX_EVENT_RETENTION_MS - 1_000L;
        context
            .getSharedPreferences(CapgoWatchConstants.PREF_EVENT_STORE, Context.MODE_PRIVATE)
            .edit()
            .putString("events", "[{\"eventName\":\"messageReceived\",\"payload\":{\"stale\":true},\"timestamp\":" + expiredAt + "}]")
            .commit();

        final JSObject payload = new JSObject();
        payload.put("fresh", true);
        new CapgoWatchEventStore(context).append("messageReceived", payload);

        final var events = new CapgoWatchEventStore(context).drainAll();
        assertEquals(1, events.size());
        assertEquals(true, events.get(0).payload.getBoolean("fresh"));
    }

    @Test
    public void appendAndDrainEvents() {
        final JSObject payload = new JSObject();
        payload.put("action", "ping");
        store.append("messageReceived", payload);

        final var events = store.drainAll();
        assertEquals(1, events.size());
        assertEquals("messageReceived", events.get(0).eventName);
        assertEquals("ping", events.get(0).payload.getString("action"));
        assertTrue(store.drainAll().isEmpty());
    }

    @Test
    public void drainDropsUnreadableEvents() {
        final Context context = ApplicationProvider.getApplicationContext();
        context
            .getSharedPreferences(CapgoWatchConstants.PREF_EVENT_STORE, Context.MODE_PRIVATE)
            .edit()
            .putString("events", "[{\"eventName\":\"messageReceived\",\"payload\":{\"ok\":true},\"timestamp\":1},{\"bad\":true}]")
            .commit();

        final var events = new CapgoWatchEventStore(context).drainAll();
        assertEquals(1, events.size());
        final String rawEvents = context
            .getSharedPreferences(CapgoWatchConstants.PREF_EVENT_STORE, Context.MODE_PRIVATE)
            .getString("events", "[]");
        assertEquals("[]", rawEvents);
        assertTrue(new CapgoWatchEventStore(context).drainAll().isEmpty());
    }

    @Test
    public void saveAndLoadPendingReply() {
        store.savePendingReply("callback-1", "node-1");
        final var pending = store.loadPendingReplies();
        assertEquals(1, pending.size());
        assertEquals("node-1", pending.get("callback-1").nodeId);
        store.removePendingReply("callback-1");
        assertTrue(store.loadPendingReplies().isEmpty());
    }

    @Test
    public void saveAndLoadLastContext() {
        final JSObject context = new JSObject();
        context.put("theme", "dark");
        store.saveLastContext(context);

        final JSObject loaded = store.loadLastContext();
        assertNotNull(loaded);
        assertEquals("dark", loaded.getString("theme"));
    }

    @Test
    public void loadLastContextReturnsNullWhenMissing() {
        assertNull(store.loadLastContext());
    }
}
