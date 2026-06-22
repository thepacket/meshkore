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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
        var freq by remember(self) { mutableStateOf(self.freqMhz.toString()) }
        var bw by remember(self) { mutableStateOf(self.bwKhz.toString()) }
        var sf by remember(self) { mutableStateOf(self.radioSf.toString()) }
        var cr by remember(self) { mutableStateOf(self.radioCr.toString()) }
        var repeat by remember(self) { mutableStateOf(false) }
        SectionCard("Radio") {
            Field("Frequency (MHz)", freq) { freq = it }
            Field("Bandwidth (kHz)", bw) { bw = it }
            Field("Spreading factor (5–12)", sf) { sf = it }
            Field("Coding rate (5–8)", cr) { cr = it }
            SwitchRow("Client-repeat mode", repeat) { repeat = it }
            SaveRow {
                val fq = freq.toDoubleOrNull(); val b = bw.toDoubleOrNull()
                val s = sf.toIntOrNull(); val c = cr.toIntOrNull()
                if (fq != null && b != null && s != null && c != null) session.applyRadio(fq, b, s, c, repeat)
                else toast(ctx, "Radio: invalid number")
            }
        }

        // ---- TX power ----
        var tx by remember(self) { mutableStateOf(self.txPower.toString()) }
        SectionCard("Transmit power") {
            Field("TX power (dBm, −9…${self.maxTxPower})", tx) { tx = it }
            SaveRow { tx.toIntOrNull()?.let { session.applyTxPower(it) } ?: toast(ctx, "TX power: invalid") }
        }

        // ---- Position ----
        var lat by remember(self) { mutableStateOf(if (self.advLat == 0 && self.advLon == 0) "" else (self.advLat / 1e6).toString()) }
        var lon by remember(self) { mutableStateOf(if (self.advLat == 0 && self.advLon == 0) "" else (self.advLon / 1e6).toString()) }
        SectionCard("Position") {
            Field("Latitude (°)", lat) { lat = it }
            Field("Longitude (°)", lon) { lon = it }
            SaveRow {
                val la = lat.toDoubleOrNull(); val lo = lon.toDoubleOrNull()
                if (la != null && lo != null) session.applyPosition((la * 1e6).toInt(), (lo * 1e6).toInt())
                else toast(ctx, "Position: invalid number")
            }
        }

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

        // ---- Advanced ----
        var pathHash by remember { mutableIntStateOf(0) }
        SectionCard("Advanced") {
            EnumDropdown("Path-hash mode", listOf("0", "1", "2"), pathHash) { pathHash = it }
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
