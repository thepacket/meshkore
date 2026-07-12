package org.thepacket.meshcore.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.thepacket.meshcore.app.MeshConnection
import org.thepacket.meshcore.app.MeshSession
import org.thepacket.meshcore.app.MqttPrefs
import org.thepacket.meshcore.app.MqttStatus
import org.thepacket.meshcore.app.NotifyPrefs
import org.thepacket.meshcore.protocol.AutoAdd
import org.thepacket.meshcore.protocol.SelfInfo
import org.thepacket.meshcore.protocol.hexToBytes

private val TELEM = listOf("Deny", "Allow-listed", "Everyone")

/** Region frequency plans (mirrors the firmware's RADIO_PRESETS). */
private data class RadioPreset(val name: String, val freqMhz: Double, val bwKhz: Double, val sf: Int, val cr: Int)
private val PRESETS = listOf(
    RadioPreset("USA/Canada", 910.525, 62.5, 7, 5),
    RadioPreset("EU", 869.525, 250.0, 10, 5),
    RadioPreset("UK/CH", 869.618, 62.5, 8, 8),
    RadioPreset("AU/NZ", 915.800, 250.0, 10, 5),
)
private val PRESET_NAMES = listOf("Custom") + PRESETS.map { it.name }

/** Standard LoRa bandwidths (kHz). */
private val BW_OPTIONS = listOf(7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125.0, 250.0, 500.0)

private fun detectPreset(freqMhz: Double, bwKhz: Double, sf: Int, cr: Int): Int {
    val i = PRESETS.indexOfFirst {
        kotlin.math.abs(it.freqMhz - freqMhz) < 0.001 && it.bwKhz == bwKhz && it.sf == sf && it.cr == cr
    }
    return if (i >= 0) i + 1 else 0 // 0 = Custom
}

private fun trimNum(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

@Composable
fun SettingsContent(session: MeshSession, self: SelfInfo?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val tuning by session.tuning.collectAsStateWithLifecycle()
    val autoAdd by session.autoAdd.collectAsStateWithLifecycle()
    val allowedRepeat by session.allowedRepeatFreqs.collectAsStateWithLifecycle()
    val deviceInfo by session.deviceInfo.collectAsStateWithLifecycle()
    val customVars by session.customVars.collectAsStateWithLifecycle()
    val battStorage by session.battStorage.collectAsStateWithLifecycle()
    val contacts by session.contacts.collectAsStateWithLifecycle()
    val channels by session.channels.collectAsStateWithLifecycle()
    val logs by session.logs.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        session.settingsResult.collect { r ->
            Toast.makeText(ctx, "${r.label}: ${if (r.ok) "saved ✓" else "failed"}", Toast.LENGTH_SHORT).show()
            // A new identity invalidates the device's contact secrets — re-sync our view.
            if (r.label == "Private key import" && r.ok) session.refreshAfterIdentityChange()
        }
    }

    var showExportKeyWarn by remember { mutableStateOf(false) }
    var exportedKeyHex by remember { mutableStateOf<String?>(null) }
    var showImportKey by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        session.privateKeyExport.collect { res ->
            when (res) {
                is org.thepacket.meshcore.app.PrivateKeyExport.Ready -> exportedKeyHex = res.hex
                org.thepacket.meshcore.app.PrivateKeyExport.Unsupported ->
                    toast(ctx, "Key export is disabled in this firmware")
            }
        }
    }

    val exportConfig = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { writeText(ctx, it, session.exportConfigJson()); toast(ctx, "Config exported") }
    }
    val importConfig = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val text = readText(ctx, it)
            if (text != null) runCatching { session.importConfigJson(text) }
                .onSuccess { toast(ctx, "Config import sent") }
                .onFailure { toast(ctx, "Invalid config file") }
            else toast(ctx, "Couldn't read file")
        }
    }
    val exportAppData = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { writeText(ctx, it, session.exportAppDataJson()); toast(ctx, "App data exported") }
    }

    var showReboot by remember { mutableStateOf(false) }
    var showFactory by remember { mutableStateOf(false) }
    var showPurge by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }

    if (self == null) {
        // No BLE device: device config isn't available, but the MQTT feed can still be configured
        // (it works standalone), so surface just that card here.
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("No device connected — connect one for full settings. The MQTT live-packet feed " +
                "works without a device:", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall)
            MqttCard(ctx)
        }
        return
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- Identity ----
        var name by remember(self) { mutableStateOf(self.name) }
        SectionCard("Identity") {
            Field("Node name", name) { name = it }
            SaveRow { session.applyNodeName(name) }
        }

        // ---- Notifications ----
        val notifyPrefs = remember { NotifyPrefs(ctx) }
        var notifyDirect by remember { mutableStateOf(notifyPrefs.notifyDirect) }
        var notifyChannels by remember { mutableStateOf(notifyPrefs.notifyChannels) }
        SectionCard("Notifications") {
            Text(
                "Show a notification for messages received while the app is in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            SwitchRow("Direct messages", notifyDirect) { notifyDirect = it; notifyPrefs.notifyDirect = it }
            SwitchRow("Channel messages", notifyChannels) { notifyChannels = it; notifyPrefs.notifyChannels = it }
        }

        // ---- MQTT (meshcore.ca live packets) ----
        MqttCard(ctx)

        // ---- Radio ----
        val bwList = remember(self) { (BW_OPTIONS + self.bwKhz).distinct().sorted() }
        val sfList = remember { (5..12).map { it.toString() } }
        val crList = remember { (5..8).map { it.toString() } }
        var freq by remember(self) { mutableStateOf(trimNum(self.freqMhz)) }
        var bwIdx by remember(self) { mutableIntStateOf(bwList.indexOf(self.bwKhz).coerceAtLeast(0)) }
        var sfIdx by remember(self) { mutableIntStateOf((self.radioSf - 5).coerceIn(0, sfList.lastIndex)) }
        var crIdx by remember(self) { mutableIntStateOf((self.radioCr - 5).coerceIn(0, crList.lastIndex)) }
        var repeat by remember(deviceInfo) { mutableStateOf(deviceInfo?.clientRepeat ?: false) }
        var presetIdx by remember(self) { mutableIntStateOf(detectPreset(self.freqMhz, self.bwKhz, self.radioSf, self.radioCr)) }
        SectionCard("Radio") {
            EnumDropdown("Region preset", PRESET_NAMES, presetIdx) { i ->
                presetIdx = i
                if (i > 0) {
                    val p = PRESETS[i - 1]
                    freq = trimNum(p.freqMhz)
                    bwIdx = bwList.indexOf(p.bwKhz).coerceAtLeast(0)
                    sfIdx = (p.sf - 5).coerceIn(0, sfList.lastIndex)
                    crIdx = (p.cr - 5).coerceIn(0, crList.lastIndex)
                }
            }
            Field("Frequency (MHz)", freq) { freq = it; presetIdx = 0 }
            EnumDropdown("Bandwidth (kHz)", bwList.map { trimNum(it) }, bwIdx) { bwIdx = it; presetIdx = 0 }
            EnumDropdown("Spreading factor", sfList, sfIdx) { sfIdx = it; presetIdx = 0 }
            EnumDropdown("Coding rate", crList, crIdx) { crIdx = it; presetIdx = 0 }
            SwitchRow("Client-repeat mode", repeat) { repeat = it }
            // The firmware only permits client-repeat on specific frequencies (queried from the
            // device); enabling it elsewhere makes SET_RADIO_PARAMS fail. Warn before that happens.
            val freqKhz = freq.toDoubleOrNull()?.let { (it * 1000).toLong() }
            val repeatAllowedHere = freqKhz != null && allowedRepeat.any { freqKhz in it }
            if (repeat && allowedRepeat.isNotEmpty() && !repeatAllowedHere) {
                Text(
                    "⚠ Client-repeat isn't allowed at $freq MHz. Allowed: " +
                        "${formatFreqRanges(allowedRepeat)}. Switch to an allowed frequency, or the save is rejected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text(
                    "Relays received packets — this node also acts as a repeater, on the operating " +
                        "frequency. Only permitted on certain frequencies" +
                        (if (allowedRepeat.isNotEmpty()) " (${formatFreqRanges(allowedRepeat)})." else "."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            SaveRow {
                val fq = freq.toDoubleOrNull()
                when {
                    fq == null -> toast(ctx, "Radio: invalid frequency")
                    repeat && allowedRepeat.isNotEmpty() && !repeatAllowedHere ->
                        toast(ctx, "Client-repeat needs an allowed frequency (${formatFreqRanges(allowedRepeat)})")
                    else -> session.applyRadio(fq, bwList[bwIdx], sfIdx + 5, crIdx + 5, repeat)
                }
            }
        }

        // ---- TX power (up to 30 dBm; the radio rejects above its hardware max) ----
        var tx by remember(self) { mutableFloatStateOf(self.txPower.toFloat()) }
        SectionCard("Transmit power") {
            Text("${tx.toInt()} dBm    (hardware max ${self.maxTxPower})",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Slider(value = tx, onValueChange = { tx = it }, valueRange = -9f..30f, steps = 38)
            SaveRow { session.applyTxPower(tx.toInt()) }
        }

        // ---- Position ----
        var lat by remember(self) { mutableStateOf(if (self.advLat == 0 && self.advLon == 0) "" else trimNum(self.advLat / 1e6)) }
        var lon by remember(self) { mutableStateOf(if (self.advLat == 0 && self.advLon == 0) "" else trimNum(self.advLon / 1e6)) }
        var showPicker by remember { mutableStateOf(false) }
        val locPermLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) fetchCurrentLocation(ctx, { la, lo -> lat = "%.5f".format(la); lon = "%.5f".format(lo) }, { toast(ctx, it) })
            else toast(ctx, "Location permission denied")
        }
        fun useCurrentLocation() {
            if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) fetchCurrentLocation(ctx, { la, lo -> lat = "%.5f".format(la); lon = "%.5f".format(lo) }, { toast(ctx, it) })
            else locPermLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        SectionCard("Position") {
            Field("Latitude (°)", lat) { lat = it }
            Field("Longitude (°)", lon) { lon = it }
            // The phone's GPS is never read automatically — only on this explicit tap.
            OutlinedButton(onClick = { useCurrentLocation() }, modifier = Modifier.fillMaxWidth()) {
                Text("Use current location")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = { showPicker = true }) { Text("Pick on map") }
                Button(onClick = {
                    val la = lat.toDoubleOrNull(); val lo = lon.toDoubleOrNull()
                    if (la != null && lo != null) session.applyPosition((la * 1e6).toInt(), (lo * 1e6).toInt())
                    else toast(ctx, "Position: invalid number")
                }) { Text("Save") }
            }
        }
        if (showPicker) MapPickerDialog(
            initialLat = lat.toDoubleOrNull(), initialLon = lon.toDoubleOrNull(),
            onPick = { la, lo -> lat = "%.5f".format(la); lon = "%.5f".format(lo); showPicker = false },
            onDismiss = { showPicker = false },
        )

        // ---- Network & telemetry (one CMD_SET_OTHER_PARAMS) ----
        var manualAdd by remember(self) { mutableStateOf((self.manualAddContacts and 1) == 1) }
        var shareLoc by remember(self) { mutableStateOf(self.advertLocPolicy == 1) }
        var multiAcks by remember(self) { mutableStateOf(self.multiAcks.toString()) }
        var telBase by remember(self) { mutableIntStateOf(self.telemetryModeBase.coerceIn(0, 2)) }
        var telLoc by remember(self) { mutableIntStateOf(self.telemetryModeLoc.coerceIn(0, 2)) }
        var telEnv by remember(self) { mutableIntStateOf(self.telemetryModeEnv.coerceIn(0, 2)) }
        SectionCard("Network & telemetry") {
            SwitchRow("Manual-add contacts", manualAdd) { manualAdd = it }
            SwitchRow("Share location in adverts", shareLoc) { shareLoc = it }
            Field("Multi-acks", multiAcks) { multiAcks = it }
            EnumDropdown("Telemetry — base", TELEM, telBase) { telBase = it }
            EnumDropdown("Telemetry — location", TELEM, telLoc) { telLoc = it }
            EnumDropdown("Telemetry — environment", TELEM, telEnv) { telEnv = it }
            SaveRow {
                session.applyOtherParams(manualAdd, telBase, telLoc, telEnv, shareLoc, multiAcks.toIntOrNull() ?: 0)
            }
        }

        // ---- Auto-add ----
        var aaChat by remember(autoAdd) { mutableStateOf(((autoAdd?.flags ?: 0) and AutoAdd.CHAT) != 0) }
        var aaRep by remember(autoAdd) { mutableStateOf(((autoAdd?.flags ?: 0) and AutoAdd.REPEATER) != 0) }
        var aaRoom by remember(autoAdd) { mutableStateOf(((autoAdd?.flags ?: 0) and AutoAdd.ROOM) != 0) }
        var aaSensor by remember(autoAdd) { mutableStateOf(((autoAdd?.flags ?: 0) and AutoAdd.SENSOR) != 0) }
        var aaOverwrite by remember(autoAdd) { mutableStateOf(((autoAdd?.flags ?: 0) and AutoAdd.OVERWRITE_OLDEST) != 0) }
        var maxHops by remember(autoAdd) { mutableStateOf((autoAdd?.maxHops ?: 0).toString()) }
        SectionCard("Auto-add contacts") {
            SwitchRow("Chat nodes", aaChat) { aaChat = it }
            SwitchRow("Repeaters", aaRep) { aaRep = it }
            SwitchRow("Rooms", aaRoom) { aaRoom = it }
            SwitchRow("Sensors", aaSensor) { aaSensor = it }
            SwitchRow("Overwrite oldest when full", aaOverwrite) { aaOverwrite = it }
            Field("Max hops (0 = unlimited)", maxHops) { maxHops = it }
            SaveRow {
                var flags = 0
                if (aaChat) flags = flags or AutoAdd.CHAT
                if (aaRep) flags = flags or AutoAdd.REPEATER
                if (aaRoom) flags = flags or AutoAdd.ROOM
                if (aaSensor) flags = flags or AutoAdd.SENSOR
                if (aaOverwrite) flags = flags or AutoAdd.OVERWRITE_OLDEST
                session.applyAutoAdd(flags, maxHops.toIntOrNull() ?: 0)
            }
        }

        // ---- Tuning ----
        var rxDelay by remember(tuning) { mutableStateOf((tuning?.rxDelayBase ?: 0.0).toString()) }
        var airtime by remember(tuning) { mutableStateOf((tuning?.airtimeFactor ?: 0.0).toString()) }
        SectionCard("Tuning") {
            Field("RX-delay base", rxDelay) { rxDelay = it }
            Field("Airtime factor", airtime) { airtime = it }
            SaveRow {
                val rx = rxDelay.toDoubleOrNull(); val af = airtime.toDoubleOrNull()
                if (rx != null && af != null) session.applyTuning(rx, af) else toast(ctx, "Tuning: invalid number")
            }
        }

        // ---- Experimental ----
        // The firmware path_hash_mode (0–2) selects the path-hash size: mode + 1 bytes.
        var pathHash by remember(deviceInfo) { mutableIntStateOf(deviceInfo?.pathHashMode?.coerceIn(0, 2) ?: 0) }
        SectionCard("Experimental") {
            Text(
                "Path-hash size used when flooding. Larger sizes reduce hash collisions in dense " +
                    "regions (e.g. Ottawa).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            EnumDropdown("Path-hash size", listOf("1-byte", "2-byte", "3-byte"), pathHash) { pathHash = it }
            SaveRow { session.applyPathHashMode(pathHash) }
        }

        // ---- Device variables (firmware custom vars / sensor settings) ----
        SectionCard("Device variables") {
            Text(
                "Firmware-defined variables such as sensor and GPS settings. The names are " +
                    "reported by the device; edit a value and Save to write it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            if (customVars.isEmpty()) {
                Text("This device reports no custom variables.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            } else {
                customVars.forEach { v ->
                    CustomVarRow(v.name, v.value) { nv -> session.setCustomVar(v.name, nv) }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { session.refreshCustomVars() }) { Text("Refresh") }
            }
        }

        // ---- Device info ----
        SectionCard("Device info") {
            deviceInfo?.let { di ->
                KeyVal("Firmware", "${di.firmwareVersion}  (${di.buildDate})")
                KeyVal("Manufacturer", di.manufacturer)
                KeyVal("Contacts", "${contacts.size} / ${di.maxContacts}")
                KeyVal("Channels", "${channels.size} / ${di.maxChannels}")
            } ?: Text("Reading device info…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            battStorage?.let { b ->
                KeyVal("Battery", "${b.batteryMilliVolts} mV")
                if (b.storageTotalKb > 0) {
                    val pct = (b.storageUsedKb * 100 / b.storageTotalKb)
                    KeyVal("Storage", "$pct% · ${b.storageUsedKb} / ${b.storageTotalKb} kB")
                }
            }
        }

        // ---- Extra tools ----
        SectionCard("Extra tools") {
            ToolButton("Export config") { exportConfig.launch("meshcore-config.json") }
            ToolButton("Import config") { importConfig.launch(arrayOf("application/json")) }
            ToolButton("Export app data") { exportAppData.launch("meshcore-appdata.json") }
            ToolButton("Purge local data") { showPurge = true }
            ToolButton("Debug logs") { showLogs = true }
            ToolButton("Reboot device") { showReboot = true }
            ToolButton("Factory reset", danger = true) { showFactory = true }
        }

        // ---- Identity (advanced) ----
        SectionCard("Identity (advanced)") {
            Text(
                "This node's cryptographic identity (private key). Back it up to restore or clone " +
                    "this node, or import a key to change its identity. ⚠ Anyone who has the exported " +
                    "key can impersonate this node — keep it secret.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            ToolButton("Export private key") { showExportKeyWarn = true }
            ToolButton("Import private key") { showImportKey = true }
        }

        // ---- Pairing PIN ----
        SectionCard("Pairing PIN") {
            Text(
                "BLE pairing PIN for MITM-protected pairing. Set a fixed 6-digit PIN, or clear it to " +
                    "use the device default (a random per-session PIN, or the firmware's static one). " +
                    "Takes effect on the next pairing — you may need to forget/re-pair the device on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            deviceInfo?.let {
                KeyVal("Current PIN",
                    if (it.blePin in 100000..999999) it.blePin.toString() else "none / device default")
            }
            var pin by remember(deviceInfo?.blePin) {
                mutableStateOf(if ((deviceInfo?.blePin ?: 0L) in 100000..999999) deviceInfo!!.blePin.toString() else "")
            }
            var confirmPin by remember { mutableStateOf<Long?>(null) }
            Field("New PIN (6 digits; blank = clear)", pin) { pin = it.filter(Char::isDigit).take(6) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirmPin = 0L }) { Text("Clear PIN") }
                Button(onClick = { confirmPin = pin.toLongOrNull() }, enabled = pin.length == 6) { Text("Save") }
            }
            confirmPin?.let { newPin ->
                AlertDialog(
                    onDismissRequest = { confirmPin = null },
                    title = { Text(if (newPin == 0L) "Clear pairing PIN?" else "Set pairing PIN?") },
                    text = {
                        Text(
                            if (newPin == 0L)
                                "The device reverts to its default PIN (random per session, or a build-time " +
                                    "static PIN). You may need to forget and re-pair it on this phone."
                            else "Set the pairing PIN to $newPin. It applies on the next pairing — you may need " +
                                "to forget and re-pair the device on this phone. Don't lose it."
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { session.applyDevicePin(newPin); confirmPin = null }) {
                            Text(if (newPin == 0L) "Clear" else "Set")
                        }
                    },
                    dismissButton = { TextButton(onClick = { confirmPin = null }) { Text("Cancel") } },
                )
            }
        }

        // ---- Time ----
        val timeOffset by session.deviceTimeOffsetMs.collectAsStateWithLifecycle()
        SectionCard("Time") {
            // A 1-Hz tick drives the live device clock (phone time + device offset).
            var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(1_000) } }
            val offset = timeOffset
            if (offset != null) {
                KeyVal("Device time", fmtDateTime(nowMs + offset))
                val driftSecs = offset / 1000
                val drift = when {
                    kotlin.math.abs(driftSecs) < 2 -> "In sync with this phone."
                    driftSecs > 0 -> "${driftSecs}s ahead of this phone."
                    else -> "${-driftSecs}s behind this phone."
                }
                Text(drift, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            } else {
                Text("Reading device time…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Text("Set the device clock from this phone's current time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { session.refreshDeviceTime() }) { Text("Refresh") }
                Button(onClick = { session.syncTimeFromPhone() }) { Text("Sync now") }
            }
        }
    }

    ConfirmDialog(showReboot, "Reboot device", "Reboot the connected device now?", "Reboot",
        onConfirm = { session.reboot(); toast(ctx, "Reboot sent") }, onDismiss = { showReboot = false })
    ConfirmDialog(showFactory, "Factory reset",
        "Erase ALL settings on the device and reboot? This cannot be undone.", "Erase", danger = true,
        onConfirm = { session.factoryReset(); toast(ctx, "Factory reset sent") }, onDismiss = { showFactory = false })
    ConfirmDialog(showPurge, "Purge local data",
        "Delete this app's chat history and cached data? The device is not affected.", "Purge", danger = true,
        onConfirm = { session.purgeLocalData(); toast(ctx, "Local data purged") }, onDismiss = { showPurge = false })
    if (showLogs) LogsDialog(
        logs = logs,
        onShare = {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, logs.joinToString("\n"))
            }
            ctx.startActivity(Intent.createChooser(intent, "Share debug logs"))
        },
        onDismiss = { showLogs = false },
    )

    ConfirmDialog(showExportKeyWarn, "Export private key",
        "This reveals this node's secret identity key. Anyone with it can impersonate this node — " +
            "only export to a trusted, private place. Continue?", "Export", danger = true,
        onConfirm = { session.exportPrivateKey() }, onDismiss = { showExportKeyWarn = false })

    exportedKeyHex?.let { hex ->
        PrivateKeyDialog(hex, onDismiss = { exportedKeyHex = null })
    }

    if (showImportKey) ImportPrivateKeyDialog(
        onDismiss = { showImportKey = false },
        onImport = { bytes -> session.importPrivateKey(bytes); showImportKey = false },
    )
}

/** Shows an exported identity key (64-byte private+public blob as hex) with a copy action. */
@Composable
private fun PrivateKeyDialog(hex: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Private key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Keep this secret. It's the 64-byte identity (private + public key).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                SelectionContainer {
                    Text(hex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("MeshCore private key", hex))
                toast(ctx, "Copied — keep it safe")
            }) { Text("Copy") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** Paste a 64-byte identity (128 hex chars) to replace this node's identity. */
@Composable
private fun ImportPrivateKeyDialog(onDismiss: () -> Unit, onImport: (ByteArray) -> Unit) {
    var text by remember { mutableStateOf("") }
    val clean = text.trim().replace(" ", "").lowercase()
    val valid = clean.length == 128 && clean.all { it in "0123456789abcdef" }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import private key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("⚠ Replaces this node's identity with the pasted key (128 hex chars = 64 bytes, " +
                    "as produced by Export). The device re-derives its contact secrets. This changes " +
                    "the node's public address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Identity key (hex)") }, singleLine = false, maxLines = 4,
                    isError = text.isNotBlank() && !valid,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(clean.hexToBytes()) }, enabled = valid) {
                Text("Import", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Configure + monitor the optional meshcore.ca MQTT live-packet subscription. */
@Composable
private fun MqttCard(ctx: Context) {
    val prefs = remember { MqttPrefs(ctx) }
    var enabled by remember { mutableStateOf(prefs.enabled) }
    var url by remember { mutableStateOf(prefs.brokerUrl) }
    var region by remember { mutableStateOf(prefs.region) }
    var user by remember { mutableStateOf(prefs.username) }
    var pass by remember { mutableStateOf(prefs.password) }
    val status by MeshConnection.mqtt.status.collectAsStateWithLifecycle()
    val received by MeshConnection.mqtt.received.collectAsStateWithLifecycle()
    fun save() { prefs.brokerUrl = url; prefs.region = region; prefs.username = user; prefs.password = pass }
    val regions = MqttPrefs.REGIONS
    SectionCard("MQTT — meshcore.ca live packets") {
        Text(
            "Subscribe to live packets from meshcore.ca and merge them into the packet monitor, " +
                "Traffic and topology. Works without a connected device. The broker requires auth — " +
                "put your token in Password (get it from meshcore.ca's setup tools).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        SwitchRow("Enabled", enabled) { on -> enabled = on; save(); MeshConnection.setMqttEnabled(ctx, on) }
        Field("Broker URL", url) { url = it }
        EnumDropdown("Region", regions.map { "${it.first} (${it.second})" },
            regions.indexOfFirst { it.second == region }.coerceAtLeast(0)) { i ->
            region = regions[i].second
            prefs.region = region
            if (enabled) MeshConnection.setMqttEnabled(ctx, true) // reconnect to the new topic
        }
        Field("Username", user) { user = it }
        OutlinedTextField(
            value = pass, onValueChange = { pass = it },
            label = { Text("Password / token") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        val statusText = when (val s = status) {
            MqttStatus.Disconnected -> "Disconnected"
            MqttStatus.Connecting -> "Connecting…"
            is MqttStatus.Connected -> "Connected · $received packets"
            is MqttStatus.Error -> "Error: ${s.message}"
        }
        KeyVal("Status", statusText)
        if (enabled) {
            SaveRow(label = "Apply / reconnect") { save(); MeshConnection.setMqttEnabled(ctx, true) }
        }
    }
}

// ---- reusable bits --------------------------------------------------------

@Composable
private fun KeyVal(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Text(value, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun ToolButton(label: String, danger: Boolean = false, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            modifier = Modifier.fillMaxWidth(),
            color = if (danger) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
        )
    }
}

@Composable
private fun ConfirmDialog(
    show: Boolean,
    title: String,
    text: String,
    confirm: String,
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (danger) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LogsDialog(logs: List<String>, onShare: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Debug logs") },
        text = {
            SelectionContainer {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (logs.isEmpty()) Text("No logs yet.")
                    logs.takeLast(300).forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onShare) { Text("Share") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun writeText(ctx: android.content.Context, uri: android.net.Uri, text: String) {
    runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }
}

private fun readText(ctx: android.content.Context, uri: android.net.Uri): String? =
    runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** One editable custom variable: a labelled value field + a Save enabled only when it changed. */
@Composable
private fun CustomVarRow(name: String, value: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, label = { Text(name) },
            singleLine = true, modifier = Modifier.weight(1f),
        )
        Button(onClick = { onSave(text) }, enabled = text != value) { Text("Save") }
    }
}

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onValue, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
}

/** Render allowed client-repeat frequencies (kHz ranges) as a compact MHz string, e.g. "918 MHz". */
private fun formatFreqRanges(ranges: List<LongRange>): String = ranges.joinToString(", ") { r ->
    if (r.first == r.last) "${trimNum(r.first / 1000.0)} MHz"
    else "${trimNum(r.first / 1000.0)}–${trimNum(r.last / 1000.0)} MHz"
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumDropdown(label: String, options: List<String>, index: Int, onIndex: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = options[index.coerceIn(options.indices)], onValueChange = {}, readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { onIndex(i); expanded = false })
            }
        }
    }
}

@Composable
private fun SaveRow(label: String = "Save", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Button(onClick = onClick) { Text(label) }
    }
}

private fun toast(ctx: android.content.Context, msg: String) =
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

/** Device clock as a local date-time string, e.g. "2026-07-08 14:03:27". */
private fun fmtDateTime(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

/**
 * Read a one-shot fix from the phone's GPS/network provider. Caller must hold ACCESS_FINE_LOCATION.
 * Prefers a fresh fix (API 30+), falling back to the best recent last-known location.
 */
@SuppressLint("MissingPermission")
private fun fetchCurrentLocation(ctx: Context, onResult: (Double, Double) -> Unit, onError: (String) -> Unit) {
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return onError("Location service unavailable")
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    if (providers.isEmpty()) return onError("Location is off — enable it and retry")

    fun bestLastKnown(): android.location.Location? =
        providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        lm.getCurrentLocation(providers.first(), null, ctx.mainExecutor) { loc ->
            val l = loc ?: bestLastKnown()
            if (l != null) onResult(l.latitude, l.longitude)
            else onError("Couldn't get a fix — try again outdoors")
        }
    } else {
        val l = bestLastKnown()
        if (l != null) onResult(l.latitude, l.longitude) else onError("No recent location — enable GPS and retry")
    }
}
