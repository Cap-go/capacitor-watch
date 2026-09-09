package app.capgo.capacitor.watch.sdk

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.data.FreezableUtils
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONException
import org.json.JSONObject

class CapgoWatchListenerService : WearableListenerService() {

    @Volatile
    private var localNodeId: String? = null
    private val pendingDataLock = Any()
    private var pendingDataEvents: MutableList<DataEvent>? = null
    private val resolvingLocalNode = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var localNodeRetryAttempts = 0

    override fun onCreate() {
        super.onCreate()
        Wearable.getNodeClient(this)
            .localNode
            .addOnSuccessListener { onLocalNodeResolved(it.id) }
            .addOnFailureListener { e -> Log.w(TAG, "Failed to resolve local node id", e) }
    }

    override fun onMessageReceived(event: MessageEvent) {
        try {
            handleMessage(event)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle message on path ${event.path}", e)
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        val events = FreezableUtils.freezeIterable(dataEvents)
        val cachedLocalNodeId = localNodeId
        if (cachedLocalNodeId == null) {
            enqueuePendingDataEvents(events)
            resolveLocalNodeAndProcessPending()
            return
        }
        processDataEvents(events, cachedLocalNodeId)
    }

    private fun onLocalNodeResolved(nodeId: String) {
        localNodeId = nodeId
        localNodeRetryAttempts = 0
        resolvingLocalNode.set(false)
        val pending = synchronized(pendingDataLock) {
            val events = pendingDataEvents
            pendingDataEvents = null
            events
        }
        if (!pending.isNullOrEmpty()) {
            processDataEvents(pending, nodeId)
        }
    }

    private fun enqueuePendingDataEvents(events: List<DataEvent>) {
        synchronized(pendingDataLock) {
            val pending = pendingDataEvents ?: mutableListOf<DataEvent>().also { pendingDataEvents = it }
            pending.addAll(events)
        }
    }

    private fun resolveLocalNodeAndProcessPending() {
        if (!resolvingLocalNode.compareAndSet(false, true)) {
            return
        }
        Wearable.getNodeClient(this)
            .localNode
            .addOnSuccessListener { node -> onLocalNodeResolved(node.id) }
            .addOnFailureListener { e ->
                resolvingLocalNode.set(false)
                localNodeRetryAttempts++
                Log.w(
                    TAG,
                    "Failed to resolve local node id; deferring data events (attempt $localNodeRetryAttempts)",
                    e,
                )
                if (localNodeRetryAttempts <= LOCAL_NODE_MAX_RETRIES) {
                    mainHandler.postDelayed({ resolveLocalNodeAndProcessPending() }, LOCAL_NODE_RETRY_MS)
                } else {
                    Log.e(TAG, "Giving up resolving local node id; pending data events remain deferred")
                }
            }
    }

    private fun processDataEvents(dataEvents: List<DataEvent>, cachedLocalNodeId: String) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) {
                continue
            }

            val itemUri = event.dataItem.uri
            val path = itemUri.path
            if (path == null) {
                continue
            }

            val isContext = path == CapgoWatchPaths.PATH_CONTEXT
            val isUserInfo = path.startsWith(CapgoWatchPaths.PATH_USER_INFO)
            if (!isContext && !isUserInfo) {
                continue
            }

            // Skip the watch's own outgoing DataItems (URI host == local node id).
            val host = itemUri.host
            if (host != null && host == cachedLocalNodeId) {
                continue
            }

            try {
                when {
                    isContext -> {
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
        private const val LOCAL_NODE_RETRY_MS = 1_000L
        private const val LOCAL_NODE_MAX_RETRIES = 5
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
