package app.capgo.capacitor.watch;

import android.net.Uri;

/**
 * Shared constants for Capgo Watch phone and Wear OS SDK integrations.
 */
public final class CapgoWatchConstants {

    public static final String DEFAULT_CAPABILITY = "capgo_watch";

    /** Path for regular messages sent/received via MessageClient. */
    public static final String PATH_MESSAGE = "/capgo/message";
    /** Path for messages that require a reply. */
    public static final String PATH_MESSAGE_WITH_REPLY = "/capgo/message/withreply";
    /** Path prefix for reply messages. */
    public static final String PATH_REPLY = "/capgo/reply/";
    /** DataItem path for application context sync. */
    public static final String PATH_CONTEXT = "/capgo/context";
    /** DataItem path prefix for user info transfers. */
    public static final String PATH_USER_INFO = "/capgo/userinfo/";

    public static final String PREF_EVENT_STORE = "capgo_watch_event_store";
    public static final String PREF_LAST_CONTEXT = "capgo_watch_last_context";
    public static final String PREF_LAST_REACHABLE = "capgo_watch_last_reachable";
    public static final String PREF_PENDING_DATA_URIS = "capgo_watch_pending_data_uris";

    /** Maximum number of persisted watch events retained for replay. */
    public static final int MAX_STORED_EVENTS = 100;
    /** Maximum serialized size of the persisted event queue in bytes. */
    public static final int MAX_STORED_EVENTS_BYTES = 256 * 1024;
    /** Maximum age of a persisted watch event before eviction. */
    public static final long MAX_EVENT_RETENTION_MS = 24L * 60L * 60L * 1000L;

    private CapgoWatchConstants() {}

    public static Uri contextDataItemUri() {
        return Uri.parse("wear://*" + PATH_CONTEXT);
    }
}
