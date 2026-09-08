package app.capgo.capacitor.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import com.getcapacitor.JSObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class CapgoWatchPendingReplyManagerTest {

    private CapgoWatchEventStore eventStore;

    @Before
    public void setUp() {
        final Context context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences(CapgoWatchConstants.PREF_EVENT_STORE, Context.MODE_PRIVATE).edit().clear().commit();
        eventStore = new CapgoWatchEventStore(context);
    }

    @Test
    public void restoreIncomingSchedulesExpiryFromCreatedAt() {
        final CapgoWatchPendingReplyManager manager = new CapgoWatchPendingReplyManager(100L);
        manager.initialize(null, eventStore);

        final long createdAt = System.currentTimeMillis() - 90L;
        manager.restoreIncoming("callback-1", "node-1", createdAt);

        final CapgoWatchPendingReplyManager.IncomingPendingReply pending = manager.getIncoming("callback-1");
        assertNotNull(pending);
        assertEquals("node-1", pending.nodeId);

        try {
            Thread.sleep(150L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertNull(manager.getIncoming("callback-1"));
    }
}
