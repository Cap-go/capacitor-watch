package app.capgo.capacitor.watch.sdk

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.data.FreezableUtils
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class CapgoWatchListenerService : WearableListenerService() {

    @Volatile
    private var localNodeId: String? = null
    private val pendingDataLock = Any()
    private var pendingDataEvents: MutableList<DataEvent>? = null
    private var pendingPersistedUris: MutableList<Uri>? = null
    private var inFlightPersistedUris: MutableList<Uri>? = null
    private val resolvingLocalNode = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var localNodeRetryAttempts = 0

    override fun onCreate() {
        super.onCreate()
        reloadPersistedPendingDataUris()
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
        val pending: List<DataEvent>?
        synchronized(pendingDataLock) {
            pending = pendingDataEvents
            pendingDataEvents = null
        }
        if (!pending.isNullOrEmpty()) {
            processDataEvents(pending, nodeId)
            synchronized(pendingDataLock) {
                for (event in pending) {
                    event.dataItem.uri?.let { removePendingUriLocked(it) }
                }
                persistPendingUrisLocked()
            }
        }
        // Claim durable URIs so a concurrent onLocalNodeResolved cannot double-fetch.
        val persisted: List<Uri>
        synchronized(pendingDataLock) {
            persisted = pendingPersistedUris?.toList() ?: emptyList()
            if (persisted.isNotEmpty()) {
                pendingPersistedUris = null
                val inFlight = inFlightPersistedUris ?: mutableListOf<Uri>().also { inFlightPersistedUris = it }
                for (uri in persisted) {
                    if (inFlight.none { it == uri }) {
                        inFlight.add(uri)
                    }
                }
                persistPendingUrisLocked()
            }
        }
        if (persisted.isNotEmpty()) {
            fetchAndProcessPersistedUris(persisted, nodeId)
        }
    }

    private fun enqueuePendingDataEvents(events: List<DataEvent>) {
        synchronized(pendingDataLock) {
            val pending = pendingDataEvents ?: mutableListOf<DataEvent>().also { pendingDataEvents = it }
            for (event in events) {
                if (pending.size >= MAX_PENDING_DATA_EVENTS) {
                    Log.w(TAG, "Dropping pending data event; queue at capacity $MAX_PENDING_DATA_EVENTS")
                    break
                }
                val uri = event.dataItem.uri
                if (uri != null && !addPendingUriLocked(uri)) {
                    continue
                }
                pending.add(event)
            }
            // Persist URIs on enqueue so process death before onDestroy cannot lose them.
            persistPendingUrisLocked()
        }
    }

    private fun addPendingUriLocked(uri: Uri): Boolean {
        val uris = pendingPersistedUris ?: mutableListOf<Uri>().also { pendingPersistedUris = it }
        if (uris.any { it == uri } || inFlightPersistedUris?.any { it == uri } == true) {
            return true
        }
        val inFlightCount = inFlightPersistedUris?.size ?: 0
        if (uris.size + inFlightCount >= MAX_PENDING_DATA_EVENTS) {
            Log.w(TAG, "Rejecting pending data URI; durable queue at capacity $MAX_PENDING_DATA_EVENTS")
            return false
        }
        uris.add(uri)
        return true
    }

    private fun removePendingUriLocked(uri: Uri) {
        pendingPersistedUris?.removeAll { it == uri }
        if (pendingPersistedUris.isNullOrEmpty()) {
            pendingPersistedUris = null
        }
        inFlightPersistedUris?.removeAll { it == uri }
        if (inFlightPersistedUris.isNullOrEmpty()) {
            inFlightPersistedUris = null
        }
    }

    private fun hasPendingData(): Boolean {
        synchronized(pendingDataLock) {
            return (
                !pendingDataEvents.isNullOrEmpty() ||
                    !pendingPersistedUris.isNullOrEmpty() ||
                    !inFlightPersistedUris.isNullOrEmpty()
            )
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
                if (!hasPendingData()) {
                    Log.e(TAG, "Giving up resolving local node id; no pending data events")
                    localNodeRetryAttempts = 0
                    return@addOnFailureListener
                }
                if (localNodeRetryAttempts >= LOCAL_NODE_MAX_RETRIES) {
                    Log.e(
                        TAG,
                        "Local node still unresolved after $localNodeRetryAttempts attempts; pausing rapid retries, scheduling long-interval retry",
                    )
                    // Stop the rapid backoff loop (CodeRabbit), but keep a slow retry so
                    // recovery without a new data event or service restart is still possible.
                    mainHandler.postDelayed(
                        {
                            localNodeRetryAttempts = 0
                            resolveLocalNodeAndProcessPending()
                        },
                        LOCAL_NODE_LONG_RETRY_MS,
                    )
                    return@addOnFailureListener
                }
                val delayMs = LOCAL_NODE_RETRY_MS * (1L shl (localNodeRetryAttempts - 1).coerceAtMost(4))
                mainHandler.postDelayed({ resolveLocalNodeAndProcessPending() }, delayMs)
            }
    }

    override fun onDestroy() {
        persistPendingDataUris()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun persistPendingDataUris() {
        synchronized(pendingDataLock) {
            // Fold any still-buffered event URIs into the durable set before writing.
            pendingDataEvents?.forEach { event ->
                event.dataItem.uri?.let { addPendingUriLocked(it) }
            }
            persistPendingUrisLocked()
        }
    }

    private fun persistPendingUrisLocked() {
        val uris = JSONArray()
        pendingPersistedUris?.forEach { uri -> uris.put(uri.toString()) }
        inFlightPersistedUris?.forEach { uri -> uris.put(uri.toString()) }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (uris.length() == 0) {
            prefs.edit().remove(PREF_PENDING_DATA_URIS).commit()
            return
        }
        if (!prefs.edit().putString(PREF_PENDING_DATA_URIS, uris.toString()).commit()) {
            Log.w(TAG, "Failed to persist pending data URIs")
        }
    }

    private fun reloadPersistedPendingDataUris() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_PENDING_DATA_URIS, null) ?: return
        try {
            val uris = JSONArray(raw)
            val loaded = mutableListOf<Uri>()
            for (i in 0 until uris.length()) {
                val uriString = uris.optString(i, null) ?: continue
                if (uriString.isEmpty()) continue
                loaded.add(Uri.parse(uriString))
            }
            if (loaded.isEmpty()) {
                prefs.edit().remove(PREF_PENDING_DATA_URIS).commit()
                return
            }
            synchronized(pendingDataLock) {
                for (uri in loaded) {
                    addPendingUriLocked(uri)
                }
                // Keep the durable copy until processing succeeds; rewrite normalized set.
                persistPendingUrisLocked()
            }
            resolveLocalNodeAndProcessPending()
        } catch (e: JSONException) {
            Log.w(TAG, "Resetting corrupted pending data URI store", e)
            prefs.edit().remove(PREF_PENDING_DATA_URIS).commit()
        }
    }

    private fun fetchAndProcessPersistedUris(
        uris: List<Uri>,
        cachedLocalNodeId: String,
        attempt: Int = 0,
    ) {
        for (uri in uris) {
            Wearable.getDataClient(this)
                .getDataItem(uri)
                .addOnSuccessListener { item ->
                    if (item != null) {
                        processDataItem(item, cachedLocalNodeId)
                    }
                    synchronized(pendingDataLock) {
                        removePendingUriLocked(uri)
                        persistPendingUrisLocked()
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "Failed to reload pending DataItem $uri; retaining for retry", e)
                    val nodeId = localNodeId
                    if (nodeId != null && attempt + 1 < LOCAL_NODE_MAX_RETRIES) {
                        mainHandler.postDelayed(
                            { fetchAndProcessPersistedUris(listOf(uri), nodeId, attempt + 1) },
                            LOCAL_NODE_RETRY_MS * (1L shl attempt.coerceAtMost(4)),
                        )
                    } else {
                        Log.e(TAG, "Exhausted getDataItem retries for $uri; URI retained until next service start")
                    }
                }
        }
    }

    private fun processDataEvents(dataEvents: List<DataEvent>, cachedLocalNodeId: String) {
        for (event in dataEvents) {
            if (event.type != DataEvent.TYPE_CHANGED) {
                continue
            }
            processDataItem(event.dataItem, cachedLocalNodeId)
        }
    }

    private fun processDataItem(item: DataItem, cachedLocalNodeId: String) {
        val itemUri = item.uri
        val path = itemUri.path ?: return

        val isContext = path == CapgoWatchPaths.PATH_CONTEXT
        val isUserInfo = path.startsWith(CapgoWatchPaths.PATH_USER_INFO)
        if (!isContext && !isUserInfo) {
            return
        }

        // Skip the watch's own outgoing DataItems (URI host == local node id).
        val host = itemUri.host
        if (host != null && host == cachedLocalNodeId) {
            return
        }

        try {
            when {
                isContext -> {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    val payload = dataMap.getString("payload", "{}")
                    val context = CapgoWatchJson.objectToMap(JSONObject(payload))
                    dispatch { it.onApplicationContextReceived(context) }
                }
                isUserInfo -> {
                    val dataMap = DataMapItem.fromDataItem(item).dataMap
                    val payload = dataMap.getString("payload", "{}")
                    try {
                        val userInfo = CapgoWatchJson.objectToMap(JSONObject(payload))
                        dispatch { it.onUserInfoReceived(userInfo) }
                        // Delete only after successful dispatch so transient listener
                        // failures can retry via Wear OS redelivery.
                        Wearable.getDataClient(this).deleteDataItems(itemUri)
                    } catch (e: JSONException) {
                        Log.e(TAG, "Failed to parse user info; discarding unreadable DataItem on path $path", e)
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
        private const val LOCAL_NODE_LONG_RETRY_MS = 60_000L
        private const val MAX_PENDING_DATA_EVENTS = 100
        private const val PREFS_NAME = "capgo_watch_sdk"
        private const val PREF_PENDING_DATA_URIS = "pending_data_uris"
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
