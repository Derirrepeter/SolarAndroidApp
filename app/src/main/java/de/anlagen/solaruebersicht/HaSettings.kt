package de.anlagen.solaruebersicht

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Abschnitt "Home Assistant" in den Einstellungen */
@Composable
fun HaSection(state: UiState, vm: MainViewModel) {
    var editing by remember { mutableStateOf<HaConfig?>(null) }
    var editingOld by remember { mutableStateOf<String?>(null) }
    val configs = vm.haConfigs

    Text("Home Assistant", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
    Text("Anlagen aus Home Assistant – erreichbar im Heimnetz oder unterwegs per VPN.", fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    configs.forEach { cfg ->
        val live = state.live[cfg.name]
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cfg.name, fontWeight = FontWeight.Bold)
                    Text(cfg.url, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (!vm.hasHaToken(cfg.name)) "Token fehlt" else live?.message ?: "", fontSize = 12.sp,
                        color = if (live?.status == Status.ONLINE) C.GREEN else if (!vm.hasHaToken(cfg.name)) C.RED
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { editing = cfg; editingOld = cfg.name }) { Text("Bearbeiten") }
            }
        }
    }
    OutlinedButton(onClick = {
        editing = if (configs.isEmpty()) HaConfig.template() else HaConfig("Home Assistant", "http://")
        editingOld = null
    }) { Text("+ Home-Assistant-Anlage hinzufügen") }

    editing?.let { cfg ->
        HaEditDialog(cfg, editingOld, vm, onClose = { editing = null })
    }
}

@Composable
private fun HaEditDialog(start: HaConfig, oldName: String?, vm: MainViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(start.name) }
    var url by remember { mutableStateOf(start.url) }
    var path by remember { mutableStateOf(start.openPath) }
    var token by remember { mutableStateOf("") }
    val fields = remember { mutableStateMapOf<String, String>().apply { HaConfig.FIELDS.forEach { (m, _) -> put(m, start.entities[m].orEmpty().joinToString(", ")) } } }
    var invGrid by remember { mutableStateOf(M.GRID in start.invert) }
    var invBatt by remember { mutableStateOf(M.BATT in start.invert) }
    var showExtras by remember { mutableStateOf(start.extras.isNotEmpty()) }
    var result by remember { mutableStateOf("") }
    var sensors by remember { mutableStateOf<List<Triple<String, String, String>>?>(null) }
    var filter by remember { mutableStateOf("") }
    val hasToken = oldName != null && vm.hasHaToken(oldName)

    fun build() = HaConfig(
        name.trim(), url.trim().trimEnd('/'), path.trim(),
        fields.mapValues { (_, v) -> v.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() } }.filterValues { it.isNotEmpty() },
        buildSet { if (invGrid) add(M.GRID); if (invBatt) add(M.BATT) },
        start.limits,
        if (showExtras) start.extras.ifEmpty { Extra.haus() } else emptyList(),
    )

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (oldName == null) "Home Assistant hinzufügen" else "Home Assistant bearbeiten") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name der Anlage") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("Adresse, z. B. http://10.2.10.141:8123") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(path, { path = it }, label = { Text("Dashboard-Pfad (beim Antippen)") }, singleLine = true)
                OutlinedTextField(token, { token = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    label = { Text(if (hasToken) "Token (leer = unverändert)" else "Langlebiges Zugriffstoken") })
                if (token.isNotBlank() && !looksLikeHaToken(token))
                    Text("Das sieht nicht wie ein Token aus (${token.trim().length} Zeichen). Ein Token ist ca. 180 Zeichen " +
                        "lang, enthält zwei Punkte und ist NICHT dein Passwort.", fontSize = 12.sp, color = C.RED)
                Text("Kein Benutzername/Passwort nötig – das Token ersetzt die Anmeldung. Anlegen: Home Assistant → " +
                    "unten links dein Name (Profil) → Reiter Sicherheit → ganz unten \"Langlebige Zugangstoken\" → " +
                    "Token erstellen → kopieren und hier einfügen.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Text("Sensoren (Entitäts-IDs, mehrere mit Komma: werden addiert bzw. beim Ladestand gemittelt)",
                    fontWeight = FontWeight.Bold, fontSize = 13.sp)
                HaConfig.FIELDS.forEach { (m, label) ->
                    OutlinedTextField(fields[m].orEmpty(), { fields[m] = it }, label = { Text(label) }, textStyle = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(invGrid, { invGrid = it }); Text("Netz-Vorzeichen umdrehen", fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(invBatt, { invBatt = it }); Text("Batterie-Vorzeichen umdrehen", fontSize = 13.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(showExtras, { showExtras = it })
                    Text("Verbraucher anzeigen (Wallbox, LEAF, Lüftung, Klimaanlage)", fontSize = 13.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        result = "teste …"
                        scope.launch {
                            result = try { vm.testHa(build(), token) } catch (e: Exception) { "Fehler: ${e.message ?: e.javaClass.simpleName}" }
                        }
                    }) { Text("Testen") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            sensors = try { vm.listHaSensors(build(), token) } catch (e: Exception) { result = "Fehler: ${e.message}"; null }
                        }
                    }) { Text("Sensorliste") }
                }
                if (result.isNotEmpty()) Text(result, fontSize = 13.sp)
                sensors?.let { list ->
                    OutlinedTextField(filter, { filter = it }, label = { Text("Filter, z. B. power oder soc") }, singleLine = true)
                    list.filter { filter.isBlank() || filter.lowercase() in (it.first + " " + it.second).lowercase() }.take(60).forEach { (id, fname, v) ->
                        Text("$id\n   $fname: $v", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { vm.saveHa(build(), token.trim(), oldName); onClose() },
                enabled = name.isNotBlank() && url.startsWith("http") && (hasToken || token.isNotBlank())) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (oldName != null) TextButton(onClick = { vm.deleteHa(oldName); onClose() }) { Text("Löschen", color = C.RED) }
                TextButton(onClick = onClose) { Text("Abbrechen") }
            }
        },
    )
}
