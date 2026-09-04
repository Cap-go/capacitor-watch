package app.capgo.capacitor.watch.sdk

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class CapgoWatchListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        if (path.startsWith(CapgoWatchPaths.PATH_REPLY)) {
            val callbackId = path.removePrefix(CapgoWatchPaths.PATH_REPLY)
            pendingReplies.remove(callbackId)?.complete(event.data)
            return
        }

        when (path) {
            CapgoWatchPaths.PATH_MESSAGE -> {
                val message = parsePayload(event.data)
                dispatch { it.onMessageReceived(message) }
            }
            CapgoWatchPaths.PATH_MESSAGE_WITH_REPLY -> {
                val envelope = JSONObject(String(event.data))
                val callbackId = envelope.optString("callbackId")
                val data = envelope.optJSONObject("data")
                val message = if (data == null) emptyMap() else jsonObjectToMap(data)
                dispatch { it.onMessageReceivedWithReply(message, callbackId) }
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) {
                continue
            }

            val path = event.dataItem.uri.path ?: continue
            when {
                path == CapgoWatchPaths.PATH_CONTEXT -> {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val payload = dataMap.getString("payload", "{}")
                    val context = jsonObjectToMap(JSONObject(payload))
                    dispatch { it.onApplicationContextReceived(context) }
                }
                path.startsWith(CapgoWatchPaths.PATH_USER_INFO) -> {
                    val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                    val payload = dataMap.getString("payload", "{}")
                    val userInfo = jsonObjectToMap(JSONObject(payload))
                    dispatch { it.onUserInfoReceived(userInfo) }
                    Wearable.getDataClient(this).deleteDataItems(event.dataItem.uri)
                }
            }
        }
    }

    private fun parsePayload(bytes: ByteArray): Map<String, Any?> {
        if (bytes.isEmpty()) {
            return emptyMap()
        }
        return jsonObjectToMap(JSONObject(String(bytes)))
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.get(key)
            result[key] = when (value) {
                is JSONObject -> jsonObjectToMap(value)
                JSONObject.NULL -> null
                else -> value
            }
        }
        return result
    }

    private fun dispatch(block: (CapgoWatchListener) -> Unit) {
        val listener = registeredListener
        if (listener != null) {
            block(listener)
        } else {
            Log.w(TAG, "No CapgoWatchListener registered")
        }
    }

    companion object {
        private const val TAG = "CapgoWatchListener"
        private val pendingReplies = ConcurrentHashMap<String, CompletableDeferred<ByteArray>>()

        @Volatile
        var registeredListener: CapgoWatchListener? = null

        suspend fun awaitReply(callbackId: String, timeoutMs: Long = TimeUnit.MINUTES.toMillis(5)): ByteArray? {
            val deferred = CompletableDeferred<ByteArray>()
            pendingReplies[callbackId] = deferred
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
        }
    }
}
