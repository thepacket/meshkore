package org.thepacket.meshcore.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.thepacket.meshcore.ble.CompanionScanner
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
        if (enabled) mqtt.start(prefs.brokerUrl, prefs.topic, prefs.username, prefs.password) else mqtt.stop()
    }

    /** Idempotent — safe to call from both the Application and the ViewModel. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        scanner = CompanionScanner(app)
        link = NordicMeshCoreLink(app)
        session = MeshSession(link, scope, ChatStore(app), AdminPrefs(app), ContactStore(app), PacketStore(app))
        mqtt = MqttPacketSource(session::injectPacket)
        MqttPrefs(app).let { if (it.enabled) mqtt.start(it.brokerUrl, it.topic, it.username, it.password) }
        initialized = true
    }
}
