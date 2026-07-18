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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
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

    /** Packet counts attributed to each broker (index aligns with the broker list). */
    private val _fromBroker = MutableStateFlow<List<Long>>(emptyList())
    val fromBroker: StateFlow<List<Long>> = _fromBroker.asStateFlow()
    /** Non-user disconnects (network drops / errors) seen this session. */
    private val _disconnections = MutableStateFlow(0L)
    val disconnections: StateFlow<Long> = _disconnections.asStateFlow()
    /** Times the feed failed over from one broker to another. */
    private val _brokerChanges = MutableStateFlow(0L)
    val brokerChanges: StateFlow<Long> = _brokerChanges.asStateFlow()

    // The received/fromBroker totals live outside their StateFlows: the MQTT callback bumps these cheap
    // atomics on every packet and only *publishes* a snapshot at most every STATS_PUBLISH_MS. A busy
    // all-regions feed was emitting (and recomposing the Stats card) once per packet — this caps it.
    private val receivedCount = AtomicLong(0)
    @Volatile private var brokerCounts = AtomicLongArray(0)
    @Volatile private var lastStatsPublishMs = 0L

    @Volatile private var client: Mqtt3AsyncClient? = null

    private data class Broker(val host: String, val port: Int, val path: String)

    private var brokers: List<Broker> = emptyList()
    @Volatile private var activeIndex = 0
    /** Consecutive CONNECT auth refusals; reset on a successful subscribe. Bounds retrying genuinely bad creds. */
    @Volatile private var authFailures = 0

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
            authFailures = 0
            // Keep accumulated per-broker counts across reconnects; (re)size if the broker list changed.
            if (_fromBroker.value.size != brokers.size) _fromBroker.value = List(brokers.size) { 0L }
            syncCountersFromFlows()
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
        publishStats(force = true) // don't leave the last packets stuck behind the throttle
        _disconnections.value++
        val msg = ctx.cause.message ?: "disconnected"
        Log.w(TAG, "disconnected (${ctx.source}): $msg")

        val authRefused = msg.contains("NOT_AUTHORIZED", ignoreCase = true) ||
            msg.contains("not authorized", ignoreCase = true) ||
            msg.contains("BAD_USER_NAME", ignoreCase = true)

        if (authRefused) {
            // In MQTT 3.1.1 an auth failure can only arrive in the CONNACK, so this is a refused *reconnect*,
            // not the broker kicking a live session. The public meshcore.ca brokers use device-signing auth
            // that fails closed: a CONNECT is refused (code 5) transiently even with valid credentials. So
            // don't treat one refusal as fatal — retry (and fail over to the other broker) with a longer
            // backoff, giving up only once refusals persist, which is what a genuinely bad/expired token
            // looks like.
            authFailures++
            if (authFailures >= MAX_AUTH_FAILURES) {
                ctx.reconnector.reconnect(false)
                _status.value = MqttStatus.Error("Not authorized — check username/token (it may have expired)")
                return
            }
            Log.w(TAG, "auth refused ($authFailures/$MAX_AUTH_FAILURES) — retrying")
            _status.value = MqttStatus.Connecting
            scheduleReconnect(ctx, AUTH_RETRY_DELAY_MS)
            return
        }

        _status.value = MqttStatus.Error(msg)
        scheduleReconnect(ctx, RECONNECT_DELAY_MS)
    }

    /**
     * Reconnect after [delayMs]; when we have more than one broker, move to the next one so a broker
     * outage (or a transient auth refusal) carries the feed to the other server instead of retrying the
     * same one forever.
     */
    private fun scheduleReconnect(ctx: MqttClientDisconnectedContext, delayMs: Long) {
        val recon = ctx.reconnector.reconnect(true).delay(delayMs, TimeUnit.MILLISECONDS)
        if (brokers.size > 1) {
            activeIndex = (activeIndex + 1) % brokers.size
            _brokerChanges.value++
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
                // Topic is meshcore/<region>/<node>/packets — the region disambiguates 1-byte hop hashes.
                val region = publish.topic.toString().split('/').getOrNull(1)?.takeIf { it.isNotBlank() && it != "+" }
                val log = parse(String(publish.payloadAsBytes, Charsets.UTF_8), region) ?: return@callback
                onPacket(log)
                val total = receivedCount.incrementAndGet()
                if (total == 1L) Log.d(TAG, "first packet injected: ${log.raw.size}B")
                // Attribute to the broker currently serving the feed.
                val idx = activeIndex
                val bc = brokerCounts
                if (idx in 0 until bc.length()) bc.incrementAndGet(idx)
                publishStats(force = false)
            }
            .send()
            .whenComplete { _: Mqtt3SubAck?, ex: Throwable? ->
                if (ex != null) { Log.w(TAG, "subscribe failed", ex); _status.value = MqttStatus.Error("subscribe failed") }
                else { authFailures = 0; _status.value = MqttStatus.Connected(topic) }
            }
    }

    fun stop() {
        client?.let { runCatching { it.disconnect() } }
        client = null
        publishStats(force = true) // flush any counts still held back by the throttle
        _status.value = MqttStatus.Disconnected
    }

    /** Seed the cheap per-packet counters from the currently-published totals (called on each (re)start). */
    private fun syncCountersFromFlows() {
        receivedCount.set(_received.value)
        val arr = AtomicLongArray(brokers.size)
        _fromBroker.value.forEachIndexed { i, v -> if (i < arr.length()) arr.set(i, v) }
        brokerCounts = arr
    }

    /**
     * Publish the per-packet counters into [received]/[fromBroker] at most once per STATS_PUBLISH_MS
     * (or immediately when [force] is set, e.g. on disconnect) so the Stats card recomposes at a
     * bounded rate instead of once per packet under a busy feed.
     */
    private fun publishStats(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastStatsPublishMs < STATS_PUBLISH_MS) return
        lastStatsPublishMs = now
        _received.value = receivedCount.get()
        val bc = brokerCounts
        _fromBroker.value = List(bc.length()) { bc.get(it) }
    }

    /**
     * Decode a meshcore.ca "/packets" JSON message into an [RxLog]. The bytes are the hex `raw`
     * field; `SNR`/`RSSI` are numeric *strings* (often empty). Observer packet-events with no
     * bytes (`raw: ""`) are skipped. Returns null for status messages and anything without bytes.
     */
    private fun parse(json: String, region: String?): RxLog? = runCatching {
        val o = JSONObject(json)
        val hex = o.optString("raw", "").filterNot { it.isWhitespace() }
        if (hex.length < 2 || hex.length % 2 != 0 || !hex.all { it in "0123456789abcdefABCDEF" }) return null
        val raw = hex.hexToBytes()
        // optDouble/optInt coerce numeric strings and fall back to 0 on empty — never throw.
        val snrQ = (o.optDouble("SNR", 0.0) * 4).roundToInt()
        RxLog(snrQ = snrQ, rssi = o.optInt("RSSI", 0), raw = raw, region = region)
    }.getOrNull()

    private companion object {
        const val TAG = "MqttSource"
        const val RECONNECT_DELAY_MS = 3000L // wait before each reconnect/failover attempt
        const val AUTH_RETRY_DELAY_MS = 5000L // device-signing auth refuses transiently; back off longer before retrying
        const val MAX_AUTH_FAILURES = 5 // consecutive CONNECT refusals before surfacing a hard bad-token error
        const val STATS_PUBLISH_MS = 400L // bound how often received/fromBroker recompose the Stats card
    }
}
