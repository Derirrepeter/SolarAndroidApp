package de.anlagen.solaruebersicht

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { App(vm) } }
    }

    // Live-Werte nur abfragen, solange die App sichtbar ist (spart Akku und Daten)
    override fun onStart() {
        super.onStart()
        vm.setForeground(true)
    }

    override fun onStop() {
        vm.setForeground(false)
        super.onStop()
    }
}

// ---- Farbwelt: warmes Solar-Orange, passend zum App-Symbol ------------------ //
object Brand {
    val ORANGE = Color(0xFFF08A1C)
    val ORANGE_DARK = Color(0xFFE2560C)
    val AMBER = Color(0xFFFFB627)
    val HEADER = Brush.horizontalGradient(listOf(Color(0xFFF5A623), Color(0xFFEA580C)))
    val TAB_COLORS = listOf(Color(0xFFEA7A0C), Color(0xFF2F7FD1), Color(0xFF2E9E5B), Color(0xFF7E57C2))
}

private val LightColors = lightColorScheme(
    primary = Color(0xFFE2700C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE2C2),
    onPrimaryContainer = Color(0xFF5A2800),
    secondary = Color(0xFF2F7FD1),
    secondaryContainer = Color(0xFFDCEBFB),
    tertiary = Color(0xFF2E9E5B),
    tertiaryContainer = Color(0xFFD9F2E3),
    background = Color(0xFFFFF7EF),
    surface = Color(0xFFFFF7EF),
    surfaceVariant = Color(0xFFF6ECE2),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFFFF1E3),
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    outline = Color(0xFFB8A897),
    outlineVariant = Color(0xFFE8DCCF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFA94D),
    onPrimary = Color(0xFF3D1C00),
    primaryContainer = Color(0xFF6B3400),
    secondary = Color(0xFF8CC0F5),
    tertiary = Color(0xFF7FD6A2),
    background = Color(0xFF17130F),
    surface = Color(0xFF17130F),
    surfaceVariant = Color(0xFF2E2620),
    surfaceContainerLow = Color(0xFF231D18),
    surfaceContainer = Color(0xFF2A231D),
    surfaceContainerHigh = Color(0xFF2A231D),
    surfaceContainerHighest = Color(0xFF2A231D),
    outlineVariant = Color(0xFF4A3F35),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

/** Kopfzeile mit Orange-Verlauf, Firmen-Schild und Titel */
@Composable
fun BrandHeader(subtitle: String, action: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxWidth().background(Brand.HEADER)) {
        Row(Modifier.statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo_shield), contentDescription = "Logo",
                colorFilter = ColorFilter.tint(Color.White), modifier = Modifier.size(38.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Solar Übersicht", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text(subtitle, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            }
            action()
        }
    }
}

@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.loggedIn) {
        LoginScreen(state, vm)
        return
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        "Live" to Icons.Filled.Home,
        "Verläufe" to Icons.Filled.DateRange,
        "Summen" to Icons.Filled.List,
        "Einstellungen" to Icons.Filled.Settings,
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            BrandHeader(if (state.demo) "Demo · Elektro & IT-Service Koch" else "Elektro & IT-Service Koch") {
                if (tab == 0) IconButton(onClick = { vm.refreshNow() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Jetzt aktualisieren", tint = Color.White)
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                tabs.forEachIndexed { i, (label, icon) ->
                    val c = Brand.TAB_COLORS[i]
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = c, selectedTextColor = c,
                            indicatorColor = c.copy(alpha = 0.16f),
                            unselectedIconColor = c.copy(alpha = 0.55f),
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> LiveScreen(state, vm)
                1 -> HistoryScreen(state, vm)
                2 -> TotalsScreen(state, vm)
                else -> SettingsScreen(state, vm)
            }
        }
    }
}
