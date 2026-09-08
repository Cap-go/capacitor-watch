package app.capgo.capacitor.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

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
    public void restoreIncomingSchedulesExpiryFromCreatedAt() throws InterruptedException {
        final long ttlMs = 200L;
        manager = new CapgoWatchPendingReplyManager(ttlMs);
        manager.initialize(null, eventStore);

        final long createdAt = System.currentTimeMillis() - (ttlMs - 80L);
        manager.restoreIncoming("callback-1", "node-1", createdAt);

        final CapgoWatchPendingReplyManager.IncomingPendingReply pending = manager.getIncoming("callback-1");
        assertNotNull(pending);
        assertEquals("node-1", pending.nodeId);

        Thread.sleep(40L);
        assertNotNull(manager.getIncoming("callback-1"));

        Thread.sleep(60L);
        assertNull(manager.getIncoming("callback-1"));
    }
}
