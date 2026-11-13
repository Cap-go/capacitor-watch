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
        final JSObject messageData = new JSObject(json.getJSONObject("data").toString());
        return new ReplyEnvelope(json.getString("callbackId"), messageData, extractWatchRequestId(json, messageData));
    }

    public static JSObject toMessageData(final JSONObject json) throws JSONException {
        return new JSObject(json.toString());
    }

    /** Prefer a top-level watchRequestId; fall back to the same key inside data. */
    public static Object extractWatchRequestId(final JSONObject json, final JSObject messageData) {
        if (json != null && json.has("watchRequestId")) {
            return json.opt("watchRequestId");
        }
        if (messageData != null && messageData.has("watchRequestId")) {
            return messageData.opt("watchRequestId");
        }
        return null;
    }

    static boolean isJsonSafeWatchRequestId(final Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
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
        public final Object watchRequestId;

        ReplyEnvelope(final String callbackId, final JSObject messageData) {
            this(callbackId, messageData, null);
        }

        ReplyEnvelope(final String callbackId, final JSObject messageData, final Object watchRequestId) {
            this.callbackId = callbackId;
            this.messageData = messageData;
            this.watchRequestId = isJsonSafeWatchRequestId(watchRequestId) ? watchRequestId : null;
        }
    }

    public static final class InvalidReplyEnvelopeException extends JSONException {

        InvalidReplyEnvelopeException(final String message) {
            super(message);
        }
    }
}
