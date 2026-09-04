package app.capgo.capacitor.watch;

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

    private CapgoWatchConstants() {}
}
