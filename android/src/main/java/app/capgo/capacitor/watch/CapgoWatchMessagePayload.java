package app.capgo.capacitor.watch;

import com.getcapacitor.JSObject;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** Helpers for parsing Wear OS message payloads. */
public final class CapgoWatchMessagePayload {

    private CapgoWatchMessagePayload() {}

    public static String extractCallbackId(final JSONObject json, final boolean isReplyPath) throws JSONException {
        if (isReplyPath && isReplyEnvelope(json)) {
            return json.getString("callbackId");
        }
        return UUID.randomUUID().toString();
    }

    public static JSObject toMessageData(final JSONObject json, final boolean isReplyPath) throws JSONException {
        if (isReplyPath && isReplyEnvelope(json)) {
            return new JSObject(json.getJSONObject("data").toString());
        }
        return new JSObject(json.toString());
    }

    static boolean isReplyEnvelope(final JSONObject json) {
        if (!json.has("callbackId") || !json.has("data")) {
            return false;
        }
        try {
            return json.get("data") instanceof JSONObject;
        } catch (JSONException e) {
            return false;
        }
    }

    public static JSONObject buildReplyEnvelope(final String callbackId, final JSONObject data) throws JSONException {
        final JSONObject envelope = new JSONObject();
        envelope.put("callbackId", callbackId);
        envelope.put("data", data);
        return envelope;
    }
}
