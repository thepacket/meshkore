package org.thepacket.meshcore.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import org.thepacket.meshcore.app.MeshSession
import org.thepacket.meshcore.protocol.AutoAdd
import org.thepacket.meshcore.protocol.SelfInfo

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

    LaunchedEffect(Unit) {
        session.settingsResult.collect { r ->
            Toast.makeText(ctx, "${r.label}: ${if (r.ok) "saved ✓" else "failed"}", Toast.LENGTH_SHORT).show()
        }
    }

    if (self == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Reading settings…", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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

        // ---- Radio ----
        val bwList = remember(self) { (BW_OPTIONS + self.bwKhz).distinct().sorted() }
        val sfList = remember { (5..12).map { it.toString() } }
        val crList = remember { (5..8).map { it.toString() } }
        var freq by remember(self) { mutableStateOf(trimNum(self.freqMhz)) }
        var bwIdx by remember(self) { mutableIntStateOf(bwList.indexOf(self.bwKhz).coerceAtLeast(0)) }
        var sfIdx by remember(self) { mutableIntStateOf((self.radioSf - 5).coerceIn(0, sfList.lastIndex)) }
        var crIdx by remember(self) { mutableIntStateOf((self.radioCr - 5).coerceIn(0, crList.lastIndex)) }
        var repeat by remember(self) { mutableStateOf(false) }
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
            SaveRow {
                val fq = freq.toDoubleOrNull()
                if (fq != null) session.applyRadio(fq, bwList[bwIdx], sfIdx + 5, crIdx + 5, repeat)
                else toast(ctx, "Radio: invalid frequency")
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
        SectionCard("Position") {
            Field("Latitude (°)", lat) { lat = it }
            Field("Longitude (°)", lon) { lon = it }
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
        var pathHash by remember { mutableIntStateOf(0) }
        SectionCard("Experimental") {
            Text(
                "Path-hash mode sets the path-hash size used when flooding. Higher values reduce " +
                    "hash collisions in dense regions (e.g. Ottawa). The device doesn't report the " +
                    "current value, so this control is write-only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            EnumDropdown("Path-hash mode (0–2)", listOf("0", "1", "2"), pathHash) { pathHash = it }
            SaveRow { session.applyPathHashMode(pathHash) }
        }

        // ---- Time ----
        SectionCard("Time") {
            Text("Set the device clock from this phone's current time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            SaveRow(label = "Sync now") { session.syncTimeFromPhone() }
        }
    }
}

// ---- reusable bits --------------------------------------------------------

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

@Composable
private fun Field(label: String, value: String, onValue: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = onValue, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth(),
    )
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
