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
    public void extractCallbackIdUsesProvidedEnvelopeValue() throws Exception {
        final JSONObject json = new JSONObject("{\"callbackId\":\"abc-123\",\"data\":{\"ok\":true}}");
        assertEquals("abc-123", CapgoWatchMessagePayload.extractCallbackId(json, true));
    }

    @Test
    public void extractCallbackIdGeneratesForFlatReplyPathPayload() throws Exception {
        final JSONObject json = new JSONObject("{\"action\":\"ping\",\"data\":\"value\"}");
        final String callbackId = CapgoWatchMessagePayload.extractCallbackId(json, true);
        assertFalse(callbackId.isEmpty());
    }

    @Test
    public void toMessageDataUsesReplyEnvelope() throws Exception {
        final JSONObject json = new JSONObject("{\"callbackId\":\"abc\",\"data\":{\"action\":\"ping\"}}");
        final JSObject message = CapgoWatchMessagePayload.toMessageData(json, true);
        assertEquals("ping", message.getString("action"));
    }

    @Test
    public void toMessageDataPreservesFlatUserFieldsOnReplyPath() throws Exception {
        final JSONObject json = new JSONObject("{\"callbackId\":\"user-id\",\"data\":\"literal\"}");
        final JSObject message = CapgoWatchMessagePayload.toMessageData(json, true);
        assertEquals("user-id", message.getString("callbackId"));
        assertEquals("literal", message.getString("data"));
    }

    @Test
    public void toMessageDataPreservesFlatMessageOnRegularPath() throws Exception {
        final JSONObject json = new JSONObject("{\"callbackId\":\"abc\",\"action\":\"ping\"}");
        final JSObject message = CapgoWatchMessagePayload.toMessageData(json, false);
        assertEquals("ping", message.getString("action"));
        assertEquals("abc", message.getString("callbackId"));
    }
}
