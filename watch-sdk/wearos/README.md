# Capgo Wear OS Watch SDK

Kotlin SDK for Wear OS companion apps that communicate with `@capgo/capacitor-watch` on the phone.

## Include in your Wear module

1. Copy or reference the `watch-sdk/wearos` directory from this repository.
2. In your Wear app's `settings.gradle`, include the module:

```gradle
include ':capgo-watch-sdk'
project(':capgo-watch-sdk').projectDir = file('../path/to/capacitor-watch/watch-sdk/wearos')
```

Your Wear project root must resolve both the Android Gradle Plugin and the Kotlin Android plugin without versions in this library module. For example in the root `plugins` / `pluginManagement` block:

```gradle
plugins {
    id 'com.android.application' version '8.13.0' apply false
    id 'org.jetbrains.kotlin.android' version '2.1.10' apply false
}
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
class MyWatchApplication : Application(), CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) {
    override fun onCreate() {
        super.onCreate()
        CapgoWatchListenerService.registeredListener = object : CapgoWatchListener {
            override fun onMessageReceivedWithReply(message: Map<String, Any?>, callbackId: String) {
                val watch = CapgoWatch.getInstance(this@MyWatchApplication)
                launch {
                    watch.replyToMessage(callbackId, mapOf("status" to "ok"))
                }
            }
        }
    }
}
```

Register `MyWatchApplication` in your Wear module `AndroidManifest.xml` with `android:name`.

## API

```kotlin
suspend fun syncWithPhone(watch: CapgoWatch, callbackId: String) {
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
}

// Example usage from a lifecycle-aware component:
// lifecycleScope.launch { syncWithPhone(CapgoWatch.getInstance(context), callbackId) }
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
