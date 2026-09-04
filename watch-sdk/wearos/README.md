# Capgo Wear OS Watch SDK

Kotlin SDK for Wear OS companion apps that communicate with `@capgo/capacitor-watch` on the phone.

## Include in your Wear module

1. Copy or reference the `watch-sdk/wearos` directory from this repository.
2. In your Wear app's `settings.gradle`, include the module:

```gradle
include ':capgo-watch-sdk'
project(':capgo-watch-sdk').projectDir = new File('../path/to/capacitor-watch/watch-sdk/wearos')
```

3. In your Wear module `build.gradle`, add the dependency:

```gradle
dependencies {
    implementation project(':capgo-watch-sdk')
}
```

4. Sync Gradle. The SDK manifest merges `CapgoWatchListenerService` automatically.

5. Register a listener in your watch app (for example in `Application.onCreate`):

```kotlin
CapgoWatchListenerService.registeredListener = object : CapgoWatchListener {
    override fun onMessageReceived(message: Map<String, Any?>) {
        // handle phone message
    }

    override fun onMessageReceivedWithReply(message: Map<String, Any?>, callbackId: String) {
        val watch = CapgoWatch.getInstance(this@MyWatchApplication)
        // process and reply
        lifecycleScope.launch {
            watch.replyToMessage(callbackId, mapOf("status" to "ok"))
        }
    }

    override fun onApplicationContextReceived(context: Map<String, Any?>) {
        // latest phone context
    }

    override fun onUserInfoReceived(userInfo: Map<String, Any?>) {
        // queued user info from phone
    }
}
```

## API

```kotlin
val watch = CapgoWatch.getInstance(context)
watch.capability = "capgo_watch" // optional, default capgo_watch

// One-way message to phone
watch.sendMessage(mapOf("action" to "ping"))

// Message expecting a reply from phone
val reply = watch.sendMessageForReply(mapOf("action" to "getState"))

// Sync latest application context to phone
watch.updateApplicationContext(mapOf("heartRate" to 72))

// Reliable queued transfer
watch.transferUserInfo(mapOf("workoutId" to "abc"))

// Reply to a phone message that used /capgo/message/withreply
watch.replyToMessage(callbackId, mapOf("status" to "ok"))
```

## Phone paths

The SDK uses the same Capgo paths as the phone plugin:

- `/capgo/message`
- `/capgo/message/withreply`
- `/capgo/reply/{callbackId}`
- `/capgo/context`
- `/capgo/userinfo/{uuid}`

## License

MPL-2.0
