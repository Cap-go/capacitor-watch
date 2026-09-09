package app.capgo.capacitor.watch;

import com.getcapacitor.JSObject;
import org.json.JSONException;
import org.json.JSONObject;

/** Helpers for parsing Wear OS message payloads. */
public final class CapgoWatchMessagePayload {

    private CapgoWatchMessagePayload() {}

    public static ReplyEnvelope parseReplyEnvelope(final JSONObject json) throws JSONException {
        if (!isReplyEnvelope(json)) {
            throw new InvalidReplyEnvelopeException("Reply-path message must include callbackId and JSONObject data");
        }
        return new ReplyEnvelope(json.getString("callbackId"), new JSObject(json.getJSONObject("data").toString()));
    }

    public static JSObject toMessageData(final JSONObject json) throws JSONException {
        return new JSObject(json.toString());
    }

    static boolean isReplyEnvelope(final JSONObject json) {
        if (!json.has("callbackId") || !json.has("data")) {
            return false;
        }
        try {
            final Object callbackId = json.get("callbackId");
            if (!(callbackId instanceof String) || ((String) callbackId).isEmpty()) {
                return false;
            }
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

    public static final class ReplyEnvelope {

        public final String callbackId;
        public final JSObject messageData;

        ReplyEnvelope(final String callbackId, final JSObject messageData) {
            this.callbackId = callbackId;
            this.messageData = messageData;
        }
    }

    public static final class InvalidReplyEnvelopeException extends JSONException {

        InvalidReplyEnvelopeException(final String message) {
            super(message);
        }
    }
}
