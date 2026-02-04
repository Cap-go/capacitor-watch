package app.capgo.capacitor.watch;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Apple Watch communication plugin for Capacitor.
 * This plugin only works on iOS - Android implementation provides stub methods
 * that return appropriate error messages.
 */
@CapacitorPlugin(name = "CapgoWatch")
public class CapgoWatchPlugin extends Plugin {

    private final String pluginVersion = "8.0.9";
    private static final String NOT_SUPPORTED_MSG = "Apple Watch is only supported on iOS";

    @PluginMethod
    public void sendMessage(final PluginCall call) {
        call.reject(NOT_SUPPORTED_MSG);
    }

    @PluginMethod
    public void updateApplicationContext(final PluginCall call) {
        call.reject(NOT_SUPPORTED_MSG);
    }

    @PluginMethod
    public void transferUserInfo(final PluginCall call) {
        call.reject(NOT_SUPPORTED_MSG);
    }

    @PluginMethod
    public void replyToMessage(final PluginCall call) {
        call.reject(NOT_SUPPORTED_MSG);
    }

    @PluginMethod
    public void getInfo(final PluginCall call) {
        final JSObject ret = new JSObject();
        ret.put("isSupported", false);
        ret.put("isPaired", false);
        ret.put("isWatchAppInstalled", false);
        ret.put("isReachable", false);
        ret.put("activationState", 0);
        call.resolve(ret);
    }

    @PluginMethod
    public void getPluginVersion(final PluginCall call) {
        try {
            final JSObject ret = new JSObject();
            ret.put("version", this.pluginVersion);
            call.resolve(ret);
        } catch (final Exception e) {
            call.reject("Could not get plugin version", e);
        }
    }
}
