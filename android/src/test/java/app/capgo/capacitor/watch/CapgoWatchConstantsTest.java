package app.capgo.capacitor.watch;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CapgoWatchConstantsTest {

    @Test
    public void defaultCapabilityRemainsCapgoWatch() {
        assertEquals("capgo_watch", CapgoWatchConstants.DEFAULT_CAPABILITY);
    }

    @Test
    public void replyPathPrefixMatchesProtocol() {
        assertEquals("/capgo/reply/", CapgoWatchConstants.PATH_REPLY);
    }
}
