package app.capgo.capacitor.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class CapgoWatchPluginDestroyTest {

    private ExecutorService executor;

    @After
    public void tearDown() {
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
        executor = null;
    }

    @Test
    public void sendMessageAfterDestroyRejectsInsteadOfThrowing() {
        executor = Executors.newSingleThreadExecutor();
        final CapgoWatchPlugin plugin = new CapgoWatchPlugin(executor);
        plugin.handleOnDestroy();

        final JSObject options = new JSObject();
        options.put("data", new JSObject().put("ping", true));
        final RecordingPluginCall call = new RecordingPluginCall(options);

        plugin.sendMessage(call);

        assertEquals("Watch plugin destroyed", call.rejectedMessage);
        assertNull(call.resolved);
    }

    @Test
    public void runOnExecutorAfterShutdownRejectsWithoutThrowing() {
        executor = Executors.newSingleThreadExecutor();
        executor.shutdown();
        final CapgoWatchPlugin plugin = new CapgoWatchPlugin(executor);
        final RecordingPluginCall call = new RecordingPluginCall(new JSObject());

        plugin.runOnExecutor(call, () -> {
            throw new AssertionError("work must not run after shutdown");
        });

        assertEquals("Watch plugin destroyed", call.rejectedMessage);
    }

    static final class RecordingPluginCall extends PluginCall {

        String rejectedMessage;
        Boolean resolved;

        RecordingPluginCall(final JSObject data) {
            super(null, "CapgoWatch", "1", "sendMessage", data);
        }

        @Override
        public void reject(final String msg, final String code, final Exception ex, final JSObject data) {
            rejectedMessage = msg;
        }

        @Override
        public void resolve() {
            resolved = true;
        }

        @Override
        public void resolve(final JSObject data) {
            resolved = true;
        }
    }
}
