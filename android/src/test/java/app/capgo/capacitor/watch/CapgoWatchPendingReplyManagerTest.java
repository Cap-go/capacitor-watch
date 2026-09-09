package app.capgo.capacitor.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class CapgoWatchPendingReplyManagerTest {

    private CapgoWatchEventStore eventStore;
    private CapgoWatchPendingReplyManager manager;

    @Before
    public void setUp() {
        final Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(CapgoWatchConstants.PREF_EVENT_STORE, Context.MODE_PRIVATE).edit().clear().commit();
        eventStore = new CapgoWatchEventStore(context);
    }

    @After
    public void tearDown() {
        if (manager != null) {
            manager.shutdown();
            manager = null;
        }
    }

    @Test
    public void claimIncomingRemovesPendingReply() {
        manager = new CapgoWatchPendingReplyManager(60_000L);
        manager.initialize(null, eventStore);
        manager.registerIncoming("callback-1", "node-1");

        final CapgoWatchPendingReplyManager.IncomingPendingReply claimed = manager.claimIncoming("callback-1");
        assertNotNull(claimed);
        assertEquals("node-1", claimed.nodeId);
        assertNull(manager.getIncoming("callback-1"));
        assertNull(manager.claimIncoming("callback-1"));
    }

    @Test
    public void restoreIncomingSchedulesExpiryFromCreatedAt() throws InterruptedException {
        final long ttlMs = 500L;
        manager = new CapgoWatchPendingReplyManager(ttlMs);
        manager.initialize(null, eventStore);

        // Leave substantially more remaining TTL so scheduling jitter cannot expire early.
        final long createdAt = System.currentTimeMillis() - (ttlMs - 300L);
        manager.restoreIncoming("callback-1", "node-1", createdAt);

        final CapgoWatchPendingReplyManager.IncomingPendingReply pending = manager.getIncoming("callback-1");
        assertNotNull(pending);
        assertEquals("node-1", pending.nodeId);

        Thread.sleep(100L);
        assertNotNull(manager.getIncoming("callback-1"));

        Thread.sleep(350L);
        assertNull(manager.getIncoming("callback-1"));
    }

    @Test
    public void registerIncomingExpiresCapacityEvictedCallbacks() throws Exception {
        final Context context = ApplicationProvider.getApplicationContext();
        final org.json.JSONObject seeded = new org.json.JSONObject();
        // Keep createdAt well within the manager TTL so restore does not immediately expire.
        final long base = System.currentTimeMillis() - 1_000L;
        for (int i = 0; i < CapgoWatchConstants.MAX_PENDING_REPLIES; i++) {
            final org.json.JSONObject entry = new org.json.JSONObject();
            entry.put("nodeId", "node-" + i);
            entry.put("createdAt", base + i);
            seeded.put("callback-" + i, entry);
        }
        context
            .getSharedPreferences(CapgoWatchConstants.PREF_EVENT_STORE, Context.MODE_PRIVATE)
            .edit()
            .putString("pending_replies", seeded.toString())
            .commit();

        eventStore = new CapgoWatchEventStore(context);
        manager = new CapgoWatchPendingReplyManager(300_000L);
        manager.initialize(null, eventStore);

        // Restore fills memory from the seeded store.
        assertNotNull(manager.getIncoming("callback-0"));

        manager.registerIncoming("callback-new", "node-new");

        // Oldest durable entry was capacity-evicted and explicitly expired from memory.
        assertNull(manager.getIncoming("callback-0"));
        assertNotNull(manager.getIncoming("callback-new"));
        assertFalse(eventStore.loadPendingReplies().containsKey("callback-0"));
        assertTrue(eventStore.loadPendingReplies().containsKey("callback-new"));
    }
}
