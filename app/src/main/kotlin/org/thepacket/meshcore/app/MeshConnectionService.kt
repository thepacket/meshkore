package org.thepacket.meshcore.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.thepacket.meshcore.ble.LinkState

/**
 * Foreground service that keeps the BLE link alive while the app is backgrounded and posts
 * a notification for each incoming message the user can't currently see. The connection
 * itself lives in [MeshConnection]; this service just hosts it in the foreground (required
 * for the OS not to suspend the process) and bridges messages to the notification shade.
 */
class MeshConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session get() = MeshConnection.session
    private lateinit var prefs: NotifyPrefs

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MeshConnection.init(this)
        prefs = NotifyPrefs(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundConnection()

        // Stop only when neither the BLE link nor the MQTT feed needs us running.
        scope.launch {
            MeshConnection.link.state.collectLatest { if (!shouldRun()) stopSelf() else startForegroundConnection() }
        }
        // Keep the "Connected to <node>" line current as self-info arrives.
        scope.launch {
            session.self.collectLatest { startForegroundConnection() }
        }
        // Bridge incoming messages to notifications when the user isn't looking at the app.
        scope.launch {
            session.incomingMessages.collect { msg -> maybeNotify(msg) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- notifications ---------------------------------------------------

    /** Keep running while the BLE link is up/coming up, or while the MQTT feed is enabled. */
    private fun shouldRun(): Boolean {
        val s = MeshConnection.link.state.value
        val bleActive = s == LinkState.Connected || s == LinkState.Connecting || s == LinkState.Bonding
        return bleActive || MqttPrefs(this).enabled
    }

    private fun startForegroundConnection() {
        val bleUp = MeshConnection.link.state.value == LinkState.Connected
        val name = session.self.value?.name?.takeIf { it.isNotBlank() } ?: "MeshCore device"
        val mqttOn = MqttPrefs(this).enabled
        val text = when {
            bleUp && mqttOn -> "Listening to $name + MQTT feed"
            bleUp -> "Listening for messages from $name"
            mqttOn -> "Collecting live packets over MQTT"
            else -> "Running"
        }
        val n = NotificationCompat.Builder(this, CH_CONNECTION)
            .setContentTitle(if (bleUp) "MeshKore connected" else "MeshKore running")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setOngoing(true)
            .setContentIntent(openAppIntent(null, null))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // BLE active → connectedDevice; MQTT-only → specialUse (its prerequisites don't need BLE perms,
        // and it isn't time-limited like dataSync). Pre-34, the type isn't strictly enforced.
        val bleActive = MeshConnection.link.state.value.let {
            it == LinkState.Connected || it == LinkState.Connecting || it == LinkState.Bonding
        }
        when {
            Build.VERSION.SDK_INT >= 34 -> startForeground(
                ID_CONNECTION, n,
                if (bleActive) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                startForeground(ID_CONNECTION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else -> startForeground(ID_CONNECTION, n)
        }
    }

    private fun maybeNotify(msg: ChatMessage) {
        // Suppress while the app is in the foreground — the user is already looking.
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return

        val isChannel = msg.conversationId.startsWith("ch:")
        if (isChannel && !prefs.notifyChannels) return
        if (!isChannel && !prefs.notifyDirect) return
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return

        val title = conversationTitle(msg.conversationId, isChannel)
        val n = NotificationCompat.Builder(this, CH_MESSAGES)
            .setContentTitle(title)
            .setContentText(msg.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(msg.text))
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppIntent(msg.conversationId, title))
            .build()
        // One notification per conversation: a newer message replaces the older line.
        NotificationManagerCompat.from(this).notify(msg.conversationId.hashCode(), n)
    }

    /** Friendly name for the notification title: contact name, channel name, else the id. */
    private fun conversationTitle(conversationId: String, isChannel: Boolean): String {
        if (isChannel) {
            val idx = conversationId.removePrefix("ch:").toIntOrNull()
            return session.channels.value.firstOrNull { it.index == idx }?.displayName
                ?: "Channel ${idx ?: ""}".trim()
        }
        return session.contacts.value.firstOrNull { it.keyPrefixHex == conversationId }
            ?.let { it.name.ifBlank { it.keyPrefixHex } }
            ?: conversationId
    }

    private fun openAppIntent(conversationId: String?, title: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (conversationId != null) {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
                putExtra(EXTRA_CONVERSATION_TITLE, title)
            }
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        // Distinct request code per conversation so each pending intent carries its own extras.
        return PendingIntent.getActivity(this, conversationId?.hashCode() ?: 0, intent, flags)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CH_CONNECTION, "Connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing notification while connected to a MeshCore device"
            }
        )
        mgr.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Incoming mesh messages while the app is in the background"
                enableVibration(true)
            }
        )
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_CONVERSATION_TITLE = "conversation_title"
        private const val CH_CONNECTION = "connection"
        private const val CH_MESSAGES = "messages"
        private const val ID_CONNECTION = 1
    }
}
