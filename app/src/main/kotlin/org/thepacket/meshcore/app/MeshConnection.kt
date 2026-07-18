package org.thepacket.meshcore.app

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.thepacket.meshcore.ble.CompanionScanner
import org.thepacket.meshcore.ble.LinkState
import org.thepacket.meshcore.ble.NordicMeshCoreLink

/**
 * Process-scoped owner of the BLE link + [MeshSession]. Living here (rather than in the
 * ViewModel) lets the connection — and the chat/session state — survive Activity recreation
 * and run inside [MeshConnectionService] while the app is backgrounded. There is exactly one
 * connection at a time, so a singleton is the right scope.
 */
object MeshConnection {
    private var initialized = false

    // Process-lifetime scope: outlives any single Activity/ViewModel.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var scanner: CompanionScanner
        private set
    lateinit var link: NordicMeshCoreLink
        private set
    lateinit var session: MeshSession
        private set

    /** Optional meshcore.ca live-packet subscription; injects into [session] with or without BLE. */
    lateinit var mqtt: MqttPacketSource
        private set

    /** Enable/disable the MQTT feed and persist the choice. */
    fun setMqttEnabled(context: Context, enabled: Boolean) {
        val prefs = MqttPrefs(context)
        prefs.enabled = enabled
        if (enabled) {
            mqtt.start(prefs.brokerUrls, prefs.topic, prefs.username, prefs.password, prefs.broker)
            // Run the foreground service so the OS doesn't suspend the process (and drop the feed) while
            // backgrounded/asleep — same as the BLE companion. The service self-stops when neither runs.
            ContextCompat.startForegroundService(context, Intent(context, MeshConnectionService::class.java))
        } else {
            mqtt.stop()
            // Drop the foreground service unless BLE is still holding it.
            if (link.state.value != LinkState.Connected) {
                context.applicationContext.stopService(Intent(context, MeshConnectionService::class.java))
            }
        }
    }

    /** Idempotent — safe to call from both the Application and the ViewModel. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        scanner = CompanionScanner(app)
        link = NordicMeshCoreLink(app)
        // Packet persistence is deactivated: pass null so every packetStore?.save/load in MeshSession
        // is a no-op. PacketStore is left intact — swap `null` back to `PacketStore(app)` to re-enable.
        session = MeshSession(link, scope, ChatStore(app), AdminPrefs(app), ContactStore(app), null, ContactPrefs(app),
            ObservedChannelStore(app),
            initialRegion = MqttPrefs(app).region, initialHomeRegion = MqttPrefs(app).homeRegion)
        mqtt = MqttPacketSource(session::injectPacket)
        MqttPrefs(app).let { if (it.enabled) mqtt.start(it.brokerUrls, it.topic, it.username, it.password, it.broker) }
        observeAppLifecycle(app)
        initialized = true
    }

    /**
     * When the "pause feed in background" MQTT setting is on, stop the feed as the whole app goes to
     * the background and restart it on return — so the all-regions firehose isn't ingested with no
     * screen watching. No-op when the feed is off or the setting is disabled.
     */
    private fun observeAppLifecycle(app: Context) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            val prefs = MqttPrefs(app)
            if (!prefs.enabled || !prefs.pauseInBackground) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_STOP -> mqtt.stop()
                Lifecycle.Event.ON_START ->
                    mqtt.start(prefs.brokerUrls, prefs.topic, prefs.username, prefs.password, prefs.broker)
                else -> Unit
            }
        })
    }
}
