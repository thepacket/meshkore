package org.thepacket.meshcore.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.thepacket.meshcore.app.RegionChannel
import org.thepacket.meshcore.app.RegionChannels
import java.util.Locale

/**
 * Browse the community region catalog and add channels. Two steps: pick a country, then tick the
 * channels to add. Catalog is fetched on open.
 *
 * [freeSlots] caps the selection when the destination is the companion's slots; pass null when adding
 * observed channels, which have no slots and no limit. [onAdd] decides where the picks actually go.
 */
@Composable
fun RegionChannelsDialog(
    freeSlots: Int?,
    onAdd: (List<RegionChannel>) -> Unit,
    onDismiss: () -> Unit,
) {
    // null = loading; success/failure once fetched.
    var catalog by remember { mutableStateOf<Result<Map<String, List<RegionChannel>>>?>(null) }
    var country by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<RegionChannel>() }

    LaunchedEffect(Unit) { catalog = runCatching { RegionChannels.fetch() } }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Header with contextual back (to country list) when inside a country.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (country != null) {
                        IconButton(onClick = { country = null; query = ""; selected.clear() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                    Text(
                        when {
                            country != null -> countryName(country!!)
                            else -> "Add region channels"
                        },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    )
                }

                val result = catalog
                when {
                    result == null -> CenterBox { CircularProgressIndicator() }
                    result.isFailure -> CenterBox {
                        Text("Couldn't load the channel catalog. Check your connection and retry.",
                            color = MaterialTheme.colorScheme.error)
                    }
                    else -> {
                        val data = result.getOrThrow()
                        if (country == null) CountryList(data) { country = it }
                        else ChannelChecklist(
                            channels = data[country].orEmpty(),
                            query = query, onQuery = { query = it },
                            selected = selected, freeSlots = freeSlots,
                        )
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    if (country != null) {
                        TextButton(
                            onClick = { onAdd(selected.toList()) },
                            enabled = selected.isNotEmpty(),
                        ) { Text("Add ${selected.size}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CountryList(data: Map<String, List<RegionChannel>>, onPick: (String) -> Unit) {
    val countries = remember(data) { data.keys.sortedBy { countryName(it) } }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(countries, key = { it }) { code ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(code) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(countryName(code), style = MaterialTheme.typography.bodyLarge)
                Text("${data[code]?.size ?: 0}", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun ChannelChecklist(
    channels: List<RegionChannel>,
    query: String,
    onQuery: (String) -> Unit,
    selected: MutableList<RegionChannel>,
    freeSlots: Int?,
) {
    val filtered = remember(channels, query) {
        if (query.isBlank()) channels
        else channels.filter {
            it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true)
        }
    }
    OutlinedTextField(
        value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth(),
        label = { Text("Search") }, singleLine = true,
    )
    val atLimit = freeSlots != null && selected.size >= freeSlots
    Text(
        if (freeSlots == null) "Selected ${selected.size}" else "Selected ${selected.size} / $freeSlots free slot(s)",
        style = MaterialTheme.typography.labelMedium,
        color = if (atLimit) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(filtered, key = { it.name }) { ch ->
            val checked = ch in selected
            val canCheck = checked || !atLimit
            Row(
                Modifier.fillMaxWidth()
                    .clickable(enabled = canCheck) { if (checked) selected.remove(ch) else selected.add(ch) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = checked, enabled = canCheck,
                    onCheckedChange = { if (checked) selected.remove(ch) else selected.add(ch) },
                )
                Column(Modifier.weight(1f)) {
                    Text(ch.name, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                    if (ch.description.isNotBlank()) {
                        Text(ch.description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) { content() }

/** ISO-2 code → localized country name, falling back to the upper-cased code. */
private fun countryName(code: String): String =
    Locale("", code.uppercase()).displayCountry.ifBlank { code.uppercase() }
