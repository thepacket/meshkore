package org.thepacket.meshcore.app

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.lifecycle.MqttClientDisconnectedContext
import com.hivemq.client.mqtt.lifecycle.MqttDisconnectSource
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.subscribe.suback.Mqtt3SubAck
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.thepacket.meshcore.protocol.RxLog
import org.thepacket.meshcore.protocol.hexToBytes
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

sealed interface MqttStatus {
    data object Disconnected : MqttStatus
    data object Connecting : MqttStatus
    data class Connected(val topic: String) : MqttStatus
    data class Error(val message: String) : MqttStatus
}

/**
 * Subscribes to a meshcore.ca packet topic (MQTT over WebSocket+TLS, anonymous) and turns each
 * JSON message's hex `raw` field into an [RxLog] handed to [onPacket]. Uses the HiveMQ client
 * (Paho's WebSocket transport is broken on Android). Auto-reconnects; runs with or without BLE.
 */
class MqttPacketSource(private val onPacket: (RxLog) -> Unit) {
    private val _status = MutableStateFlow<MqttStatus>(MqttStatus.Disconnected)
    val status: StateFlow<MqttStatus> = _status.asStateFlow()
    private val _received = MutableStateFlow(0L)
    val received: StateFlow<Long> = _received.asStateFlow()

    @Volatile private var client: Mqtt3AsyncClient? = null

    private data class Broker(val host: String, val port: Int, val path: String)

    private var brokers: List<Broker> = emptyList()
    @Volatile private var activeIndex = 0

    private fun parseBroker(url: String): Broker {
        val uri = URI(url.trim())
        val host = uri.host ?: error("no host in $url")
        val port = if (uri.port > 0) uri.port else 443
        val path = uri.path.trim('/').ifEmpty { "mqtt" }
        return Broker(host, port, path)
    }

    /**
     * Connect to [urls]\[startIndex]. Reconnection is driven by us (not HiveMQ's automatic reconnect,
     * which can't be steered to a different host): on every network drop we reconnect against the
     * *next* broker in [urls], so a broker going down moves the feed to the other one.
     */
    fun start(urls: List<String>, topic: String, username: String = "", password: String = "", startIndex: Int = 0) {
        stop()
        _status.value = MqttStatus.Connecting
        try {
            brokers = urls.map(::parseBroker)
            if (brokers.isEmpty()) error("no broker")
            activeIndex = startIndex.coerceIn(brokers.indices)
            val b = brokers[activeIndex]
            Log.d(TAG, "connecting host=${b.host} port=${b.port} path=/${b.path} topic=$topic auth=${username.isNotBlank()}")

            val c = MqttClient.builder()
                .useMqttVersion3()
                .identifier("meshkore-" + System.currentTimeMillis().toString(36))
                .serverHost(b.host)
                .serverPort(b.port)
                .webSocketConfig().serverPath(b.path).applyWebSocketConfig()
                .sslWithDefaultConfig()
                .addConnectedListener { onConnected(topic) }
                .addDisconnectedListener { ctx -> onDisconnected(ctx) }
                .buildAsync()
            client = c
            val connect = c.connectWith().cleanSession(true)
            // MQTT 3 requires a username when a password is present; supply auth when given.
            if (username.isNotBlank()) {
                val auth = connect.simpleAuth().username(username)
                if (password.isNotEmpty()) auth.password(password.toByteArray())
                auth.applySimpleAuth()
            }
            connect.send().whenComplete { _, ex ->
                if (ex != null) {
                    Log.w(TAG, "connect failed", ex)
                    _status.value = MqttStatus.Error(ex.message ?: "connect failed")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "start error", e)
            _status.value = MqttStatus.Error(e.message ?: "error")
        }
    }

    private fun onDisconnected(ctx: MqttClientDisconnectedContext) {
        // We disconnected on purpose (stop()) — stay down, don't reconnect.
        if (ctx.source == MqttDisconnectSource.USER) return
        val msg = ctx.cause.message ?: "disconnected"
        Log.w(TAG, "disconnected (${ctx.source}): $msg")
        if (msg.contains("NOT_AUTHORIZED", ignoreCase = true) ||
            msg.contains("not authorized", ignoreCase = true) ||
            msg.contains("BAD_USER_NAME", ignoreCase = true)
        ) {
            // Bad/expired credentials won't fix themselves — stop retrying, surface clearly.
            ctx.reconnector.reconnect(false)
            _status.value = MqttStatus.Error("Not authorized — check username/token (it may have expired)")
            return
        }
        _status.value = MqttStatus.Error(msg)
        // Reconnect after a short delay; when we have more than one broker, move to the next one so a
        // broker outage carries the feed to a healthy server instead of retrying the dead one forever.
        val recon = ctx.reconnector.reconnect(true).delay(RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS)
        if (brokers.size > 1) {
            activeIndex = (activeIndex + 1) % brokers.size
            val next = brokers[activeIndex]
            Log.w(TAG, "failing over to ${next.host}:${next.port}")
            // Reuse the current transport (TLS + WebSocket path), only moving host/port.
            recon.transportConfig(
                ctx.clientConfig.transportConfig.extend()
                    .serverHost(next.host).serverPort(next.port).build()
            )
        }
    }

    private fun onConnected(topic: String) {
        val c = client ?: return
        Log.d(TAG, "connected, subscribing $topic")
        c.subscribeWith()
            .topicFilter(topic)
            .qos(MqttQos.AT_MOST_ONCE)
            .callback { publish ->
                val log = parse(String(publish.payloadAsBytes, Charsets.UTF_8)) ?: return@callback
                if (_received.value == 0L) Log.d(TAG, "first packet injected: ${log.raw.size}B")
                onPacket(log)
                _received.value++
            }
            .send()
            .whenComplete { _: Mqtt3SubAck?, ex: Throwable? ->
                if (ex != null) { Log.w(TAG, "subscribe failed", ex); _status.value = MqttStatus.Error("subscribe failed") }
                else _status.value = MqttStatus.Connected(topic)
            }
    }

    fun stop() {
        client?.let { runCatching { it.disconnect() } }
        client = null
        _status.value = MqttStatus.Disconnected
    }

    /**
     * Decode a meshcore.ca "/packets" JSON message into an [RxLog]. The bytes are the hex `raw`
     * field; `SNR`/`RSSI` are numeric *strings* (often empty). Observer packet-events with no
     * bytes (`raw: ""`) are skipped. Returns null for status messages and anything without bytes.
     */
    private fun parse(json: String): RxLog? = runCatching {
        val o = JSONObject(json)
        val hex = o.optString("raw", "").filterNot { it.isWhitespace() }
        if (hex.length < 2 || hex.length % 2 != 0 || !hex.all { it in "0123456789abcdefABCDEF" }) return null
        val raw = hex.hexToBytes()
        // optDouble/optInt coerce numeric strings and fall back to 0 on empty — never throw.
        val snrQ = (o.optDouble("SNR", 0.0) * 4).roundToInt()
        RxLog(snrQ = snrQ, rssi = o.optInt("RSSI", 0), raw = raw)
    }.getOrNull()

    private companion object {
        const val TAG = "MqttSource"
        const val RECONNECT_DELAY_MS = 3000L // wait before each reconnect/failover attempt
    }
}
