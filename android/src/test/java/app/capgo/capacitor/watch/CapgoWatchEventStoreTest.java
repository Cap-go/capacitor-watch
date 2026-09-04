package app.capgo.capacitor.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.getcapacitor.JSObject;
import org.json.JSONObject;
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
        context.getSharedPreferences(CapgoWatchConstants.PREF_EVENT_STORE, Context.MODE_PRIVATE).edit().clear().apply();
        context
            .getSharedPreferences(CapgoWatchConstants.PREF_EVENT_STORE, Context.MODE_PRIVATE)
            .edit()
            .remove(CapgoWatchConstants.PREF_LAST_CONTEXT)
            .apply();
        store = new CapgoWatchEventStore(context);
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
    public void drainSkipsUnreadableEvents() throws Exception {
        final Context context = ApplicationProvider.getApplicationContext();
        context
            .getSharedPreferences(CapgoWatchConstants.PREF_EVENT_STORE, Context.MODE_PRIVATE)
            .edit()
            .putString("events", "[{\"eventName\":\"messageReceived\",\"payload\":{\"ok\":true},\"timestamp\":1},{\"bad\":true}]")
            .apply();

        final var events = new CapgoWatchEventStore(context).drainAll();
        assertEquals(1, events.size());
        assertEquals("messageReceived", events.get(0).eventName);
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
