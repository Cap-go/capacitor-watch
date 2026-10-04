package app.capgo.capacitor.watch;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class CapgoWatchConstantsTest {

    @Test
    public void defaultCapabilityRemainsCapgoWatch() {
        assertEquals("capgo_watch", CapgoWatchConstants.DEFAULT_CAPABILITY);
    }

    @Test
    public void replyPathPrefixMatchesProtocol() {
        assertEquals("/capgo/reply/", CapgoWatchConstants.PATH_REPLY);
    }

    @Test
    public void contextDataItemUriAvoidsDoubleSlash() {
        assertEquals("wear://*/capgo/context", CapgoWatchConstants.contextDataItemUri().toString());
    }
}
