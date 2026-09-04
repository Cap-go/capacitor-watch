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
    public void extractCallbackIdUsesProvidedValue() throws Exception {
        final JSONObject json = new JSONObject("{\"callbackId\":\"abc-123\",\"data\":{\"ok\":true}}");
        assertEquals("abc-123", CapgoWatchMessagePayload.extractCallbackId(json));
    }

    @Test
    public void extractCallbackIdGeneratesWhenMissing() throws Exception {
        final JSONObject json = new JSONObject("{\"action\":\"ping\"}");
        final String callbackId = CapgoWatchMessagePayload.extractCallbackId(json);
        assertFalse(callbackId.isEmpty());
    }

    @Test
    public void toMessageDataUsesNestedDataField() throws Exception {
        final JSONObject json = new JSONObject("{\"callbackId\":\"abc\",\"data\":{\"action\":\"ping\"}}");
        final JSObject message = CapgoWatchMessagePayload.toMessageData(json);
        assertEquals("ping", message.getString("action"));
    }

    @Test
    public void toMessageDataStripsCallbackIdFromFlatPayload() throws Exception {
        final JSONObject json = new JSONObject("{\"callbackId\":\"abc\",\"action\":\"ping\"}");
        final JSObject message = CapgoWatchMessagePayload.toMessageData(json);
        assertEquals("ping", message.getString("action"));
        assertFalse(message.has("callbackId"));
    }
}
