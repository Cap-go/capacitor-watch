package app.capgo.capacitor.watch.sdk

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONException
import org.json.JSONObject

class CapgoWatchListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        try {
            handleMessage(event)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle message on path ${event.path}", e)
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) {
                continue
            }

            val itemUri = event.dataItem.uri
            val path = itemUri.path
            if (path == null) {
                continue
            }

            val isUserInfo = path.startsWith(CapgoWatchPaths.PATH_USER_INFO)
            try {
                when {
                    path == CapgoWatchPaths.PATH_CONTEXT -> {
                        val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                        val payload = dataMap.getString("payload", "{}")
                        val context = CapgoWatchJson.objectToMap(JSONObject(payload))
                        dispatch { it.onApplicationContextReceived(context) }
                    }
                    isUserInfo -> {
                        try {
                            val dataMap = DataMapItem.fromDataItem(event.dataItem).dataMap
                            val payload = dataMap.getString("payload", "{}")
                            val userInfo = CapgoWatchJson.objectToMap(JSONObject(payload))
                            dispatch { it.onUserInfoReceived(userInfo) }
                        } finally {
                            Wearable.getDataClient(this).deleteDataItems(itemUri)
                        }
                    }
                }
            } catch (e: JSONException) {
                Log.e(TAG, "Failed to parse data change on path $path", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle data change on path $path", e)
            }
        }
    }

    private fun handleMessage(event: MessageEvent) {
        val path = event.path
        if (path.startsWith(CapgoWatchPaths.PATH_REPLY)) {
            val callbackId = path.removePrefix(CapgoWatchPaths.PATH_REPLY)
            pendingReplies[callbackId]?.complete(event.data)
            return
        }

        try {
            when (path) {
                CapgoWatchPaths.PATH_MESSAGE -> {
                    val message = parsePayload(event.data)
                    dispatch { it.onMessageReceived(message) }
                }
                CapgoWatchPaths.PATH_MESSAGE_WITH_REPLY -> {
                    val envelope = JSONObject(String(event.data))
                    val callbackId = envelope.optString("callbackId")
                    if (callbackId.isEmpty()) {
                        Log.w(TAG, "Skipping reply message without callbackId on path $path")
                        return
                    }
                    val data = envelope.optJSONObject("data")
                    val message = if (data == null) emptyMap() else CapgoWatchJson.objectToMap(data)
                    dispatch { it.onMessageReceivedWithReply(message, callbackId) }
                }
            }
        } catch (e: JSONException) {
            Log.e(TAG, "Failed to parse message on path $path", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle message on path $path", e)
        }
    }

    private fun parsePayload(bytes: ByteArray): Map<String, Any?> {
        if (bytes.isEmpty()) {
            return emptyMap()
        }
        return CapgoWatchJson.objectToMap(JSONObject(String(bytes)))
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

        fun registerPendingReply(callbackId: String): CompletableDeferred<ByteArray> {
            return pendingReplies.getOrPut(callbackId) { CompletableDeferred() }
        }

        fun cancelPendingReply(callbackId: String) {
            pendingReplies.remove(callbackId)
        }
    }
}
