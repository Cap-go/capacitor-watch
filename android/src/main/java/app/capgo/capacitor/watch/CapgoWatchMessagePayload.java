package app.capgo.capacitor.watch;

import com.getcapacitor.JSObject;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

/** Helpers for parsing Wear OS message payloads. */
public final class CapgoWatchMessagePayload {

    private CapgoWatchMessagePayload() {}

    public static String extractCallbackId(final JSONObject json) {
        final String callbackId = json.optString("callbackId", "");
        if (!callbackId.isEmpty()) {
            return callbackId;
        }
        return UUID.randomUUID().toString();
    }

    public static JSObject toMessageData(final JSONObject json) throws JSONException {
        if (json.has("data") && json.get("data") instanceof JSONObject) {
            return new JSObject(json.getJSONObject("data").toString());
        }
        final JSONObject copy = new JSONObject(json.toString());
        copy.remove("callbackId");
        return new JSObject(copy.toString());
    }
}
