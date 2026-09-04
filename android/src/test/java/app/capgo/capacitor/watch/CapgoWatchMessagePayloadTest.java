package app.capgo.capacitor.watch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.getcapacitor.JSObject;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class CapgoWatchMessagePayloadTest {

    @Test
    public void parseReplyEnvelopeUsesProvidedValues() throws Exception {
        final JSONObject json = new JSONObject("{\"callbackId\":\"abc-123\",\"data\":{\"ok\":true}}");
        final CapgoWatchMessagePayload.ReplyEnvelope envelope = CapgoWatchMessagePayload.parseReplyEnvelope(json);
        assertEquals("abc-123", envelope.callbackId);
        assertEquals("true", envelope.messageData.getString("ok"));
    }

    @Test
    public void isReplyEnvelopeRequiresCallbackIdAndObjectData() throws Exception {
        assertFalse(CapgoWatchMessagePayload.isReplyEnvelope(new JSONObject("{\"action\":\"ping\"}")));
        assertFalse(CapgoWatchMessagePayload.isReplyEnvelope(new JSONObject("{\"callbackId\":\"abc\",\"data\":\"literal\"}")));
        assertTrue(CapgoWatchMessagePayload.isReplyEnvelope(new JSONObject("{\"callbackId\":\"abc\",\"data\":{\"ok\":true}}")));
    }

    @Test
    public void toMessageDataPreservesFlatMessageOnRegularPath() throws Exception {
        final JSONObject json = new JSONObject("{\"callbackId\":\"abc\",\"action\":\"ping\"}");
        final JSObject message = CapgoWatchMessagePayload.toMessageData(json);
        assertEquals("ping", message.getString("action"));
        assertEquals("abc", message.getString("callbackId"));
    }
}
