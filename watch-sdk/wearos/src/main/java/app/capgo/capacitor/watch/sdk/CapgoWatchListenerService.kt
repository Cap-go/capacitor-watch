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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
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
    private val drainInProgress = AtomicBoolean(false)
    private val pendingDrainRequested = AtomicBoolean(false)
    private val persistGeneration = AtomicLong(0)
    private val lastCommittedGeneration = AtomicLong(0)
    private val persistExecutor: ExecutorService = Executors.newSingleThreadExecutor()
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
        if (!drainInProgress.compareAndSet(false, true)) {
            // Another drain is active; request a follow-up so events enqueued during
            // that drain are not stranded once localNodeId is set.
            pendingDrainRequested.set(true)
            return
        }
        try {
            pendingDrainRequested.set(false)
            val pending: List<DataEvent>?
            val persisted: List<Uri>
            synchronized(pendingDataLock) {
                pending = pendingDataEvents
                pendingDataEvents = null
                // Claim durable URIs atomically with the pending-event drain.
                persisted = pendingPersistedUris?.toList() ?: emptyList()
                pendingPersistedUris = null
                if (persisted.isNotEmpty()) {
                    val inFlight = inFlightPersistedUris ?: mutableListOf<Uri>().also { inFlightPersistedUris = it }
                    for (uri in persisted) {
                        if (inFlight.none { it == uri }) {
                            inFlight.add(uri)
                        }
                    }
                }
                // Keep frozen-event URIs durable in inFlight until processDataEvents
                // completes; process death between persist and process must not drop them.
                pending?.forEach { event ->
                    event.dataItem.uri?.let { uri ->
                        val inFlight = inFlightPersistedUris ?: mutableListOf<Uri>().also { inFlightPersistedUris = it }
                        if (inFlight.none { it == uri }) {
                            inFlight.add(uri)
                        }
                    }
                }
            }
            flushPendingUris(syncDurable = true)
            if (!pending.isNullOrEmpty()) {
                processDataEvents(pending, nodeId)
                synchronized(pendingDataLock) {
                    pending.forEach { event ->
                        event.dataItem.uri?.let { removePendingUriLocked(it) }
                    }
                }
                flushPendingUris(syncDurable = false)
            }
            if (persisted.isNotEmpty()) {
                val toFetch = synchronized(pendingDataLock) {
                    persisted.filter { uri -> inFlightPersistedUris?.any { it == uri } == true }
                }
                if (toFetch.isNotEmpty()) {
                    fetchAndProcessPersistedUris(toFetch, nodeId)
                }
            }
        } finally {
            drainInProgress.set(false)
            if (pendingDrainRequested.compareAndSet(true, false) || hasQueuedPendingEvents()) {
                mainHandler.post { onLocalNodeResolved(nodeId) }
            }
        }
    }

    /** Pending events/URIs waiting to be drained (excludes in-flight fetches). */
    private fun hasQueuedPendingEvents(): Boolean {
        synchronized(pendingDataLock) {
            return !pendingDataEvents.isNullOrEmpty() || !pendingPersistedUris.isNullOrEmpty()
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
        }
        // Sync durability barrier outside the lock so process death cannot lose enqueued URIs.
        flushPendingUris(syncDurable = true)
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
        // Fold buffered events then sync-flush before tearing down the persist executor.
        synchronized(pendingDataLock) {
            pendingDataEvents?.forEach { event ->
                event.dataItem.uri?.let { addPendingUriLocked(it) }
            }
        }
        flushPendingUris(syncDurable = true)
        persistExecutor.shutdown()
        try {
            persistExecutor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /**
     * Snapshot pending/in-flight URIs under the lock (no I/O).
     * @return payload (null = clear) and monotonic generation for write serialization
     */
    private fun snapshotPendingUrisLocked(): Pair<String?, Long> {
        val uris = JSONArray()
        pendingPersistedUris?.forEach { uri -> uris.put(uri.toString()) }
        inFlightPersistedUris?.forEach { uri -> uris.put(uri.toString()) }
        val generation = persistGeneration.incrementAndGet()
        val payload = if (uris.length() == 0) null else uris.toString()
        return payload to generation
    }

    /**
     * Commit a previously taken snapshot. Runs only on [persistExecutor] so writes are
     * serialized; stale generations are skipped so older cleanup cannot clobber newer state.
     */
    private fun commitPendingUrisSnapshot(payload: String?, generation: Long) {
        if (generation < lastCommittedGeneration.get()) {
            return
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ok =
            if (payload == null) {
                prefs.edit().remove(PREF_PENDING_DATA_URIS).commit()
            } else {
                prefs.edit().putString(PREF_PENDING_DATA_URIS, payload).commit()
            }
        if (!ok) {
            Log.w(TAG, "Failed to persist pending data URIs")
            return
        }
        lastCommittedGeneration.set(generation)
    }

    /**
     * @param syncDurable when true, block until the commit finishes (enqueue / destroy barrier).
     *                    when false, queue cleanup on [persistExecutor] without blocking the
     *                    main-thread listener/lifecycle path.
     */
    private fun flushPendingUris(syncDurable: Boolean) {
        val (payload, generation) = synchronized(pendingDataLock) { snapshotPendingUrisLocked() }
        val task = Runnable { commitPendingUrisSnapshot(payload, generation) }
        if (syncDurable) {
            try {
                persistExecutor.submit(task).get(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                // Executor shut down or timed out — fall back to inline commit.
                Log.w(TAG, "Sync URI persist via executor failed; committing inline", e)
                commitPendingUrisSnapshot(payload, generation)
            }
        } else {
            try {
                persistExecutor.execute(task)
            } catch (e: Exception) {
                Log.w(TAG, "Async URI persist rejected; committing inline", e)
                commitPendingUrisSnapshot(payload, generation)
            }
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
            }
            // Keep the durable copy until processing succeeds; rewrite normalized set.
            flushPendingUris(syncDurable = true)
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
                    }
                    flushPendingUris(syncDurable = false)
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
