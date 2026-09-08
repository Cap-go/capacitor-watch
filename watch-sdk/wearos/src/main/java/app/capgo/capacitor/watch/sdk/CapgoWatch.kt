package app.capgo.capacitor.watch.sdk

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Wear OS watch-side SDK for communicating with the Capgo Watch Capacitor plugin on the phone.
 */
class CapgoWatch private constructor(private val appContext: Context) {

    private val messageClient: MessageClient = Wearable.getMessageClient(appContext)
    private val dataClient = Wearable.getDataClient(appContext)
    private val nodeClient = Wearable.getNodeClient(appContext)
    private val capabilityClient = Wearable.getCapabilityClient(appContext)

    var capability: String = CapgoWatchPaths.DEFAULT_CAPABILITY

    suspend fun sendMessage(data: Map<String, Any?>) {
        val payload = JSONObject(data).toString().toByteArray()
        val nodes = connectedPhoneNodes()
        if (nodes.isEmpty()) {
            throw IllegalStateException("No connected phone nodes found")
        }
        for (node in nodes) {
            messageClient.sendMessage(node.id, CapgoWatchPaths.PATH_MESSAGE, payload).await()
        }
    }

    suspend fun sendMessageForReply(data: Map<String, Any?>): Map<String, Any?> {
        val callbackId = UUID.randomUUID().toString()
        val envelope = JSONObject()
        envelope.put("callbackId", callbackId)
        envelope.put("data", JSONObject(data))
        val payload = envelope.toString().toByteArray()
        val nodes = connectedPhoneNodes()
        if (nodes.isEmpty()) {
            throw IllegalStateException("No connected phone nodes found")
        }

        val deferred = CapgoWatchListenerService.registerPendingReply(callbackId)
        try {
            for (node in nodes) {
                messageClient.sendMessage(node.id, CapgoWatchPaths.PATH_MESSAGE_WITH_REPLY, payload).await()
            }
            val replyBytes = withTimeoutOrNull(TimeUnit.MINUTES.toMillis(5)) { deferred.await() }
                ?: throw IllegalStateException("Timed out waiting for phone reply")
            if (replyBytes.isEmpty()) {
                return emptyMap()
            }
            return CapgoWatchJson.objectToMap(JSONObject(String(replyBytes)))
        } finally {
            CapgoWatchListenerService.cancelPendingReply(callbackId)
        }
    }

    suspend fun updateApplicationContext(context: Map<String, Any?>) {
        val request = PutDataMapRequest.create(CapgoWatchPaths.PATH_CONTEXT)
        request.dataMap.putString("payload", JSONObject(context).toString())
        request.setUrgent()
        dataClient.putDataItem(request.asPutDataRequest()).await()
    }

    suspend fun transferUserInfo(userInfo: Map<String, Any?>) {
        val path = CapgoWatchPaths.PATH_USER_INFO + UUID.randomUUID()
        val request = PutDataMapRequest.create(path)
        request.dataMap.putString("payload", JSONObject(userInfo).toString())
        request.setUrgent()
        dataClient.putDataItem(request.asPutDataRequest()).await()
    }

    suspend fun replyToMessage(callbackId: String, data: Map<String, Any?>) {
        val nodes = connectedPhoneNodes()
        if (nodes.isEmpty()) {
            throw IllegalStateException("No connected phone nodes found")
        }
        val payload = JSONObject(data).toString().toByteArray()
        val replyPath = CapgoWatchPaths.PATH_REPLY + callbackId
        for (node in nodes) {
            messageClient.sendMessage(node.id, replyPath, payload).await()
        }
    }

    private suspend fun connectedPhoneNodes(): List<Node> {
        val capabilityInfo = capabilityClient
            .getCapability(capability, CapabilityClient.FILTER_REACHABLE)
            .await()
        val capabilityNodes = capabilityInfo.nodes
        if (capabilityNodes.isNotEmpty()) {
            return capabilityNodes.toList()
        }
        return nodeClient.connectedNodes.await()
    }

    companion object {
        @Volatile
        private var instance: CapgoWatch? = null

        fun getInstance(context: Context): CapgoWatch {
            return instance ?: synchronized(this) {
                instance ?: CapgoWatch(context.applicationContext).also { instance = it }
            }
        }
    }
}
