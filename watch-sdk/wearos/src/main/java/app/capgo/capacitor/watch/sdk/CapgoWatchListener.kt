package app.capgo.capacitor.watch.sdk

interface CapgoWatchListener {
    fun onMessageReceived(message: Map<String, Any?>) {}
    fun onMessageReceivedWithReply(message: Map<String, Any?>, callbackId: String) {}
    fun onApplicationContextReceived(context: Map<String, Any?>) {}
    fun onUserInfoReceived(userInfo: Map<String, Any?>) {}
}
