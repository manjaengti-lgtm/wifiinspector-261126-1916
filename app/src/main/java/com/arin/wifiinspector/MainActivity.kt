package com.arin.wifiinspector

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.arin.wifiinspector.ui.theme.WifiInspectorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WifiInspectorTheme { InspectorApp() } }
    }
}

private data class NetworkInfo(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val frequency: Int,
    val security: String
)

private data class RecoveredPassword(val value: String, val source: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InspectorApp() {
    val context = LocalContext.current
    val nav = rememberNavController()
    val prefs = remember { context.getSharedPreferences("inspector", Context.MODE_PRIVATE) }
    var dark by remember { mutableStateOf(prefs.getString("theme", "system") == "dark") }
    var rootAvailable by remember { mutableStateOf(false) }
    var permissionGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    var networks by remember { mutableStateOf(emptyList<NetworkInfo>()) }
    var connectedNetwork by remember { mutableStateOf<NetworkInfo?>(null) }
    var selected by remember { mutableStateOf<NetworkInfo?>(null) }
    var recovered by remember { mutableStateOf<RecoveredPassword?>(null) }
    var recoveryBusy by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scheme = if (dark) darkColorScheme(
        primary = Color(0xFFB9C2FF),
        secondary = Color(0xFF80CBC4),
        tertiary = Color(0xFFFFB86C),
        surface = Color(0xFF121521)
    ) else lightColorScheme(
        primary = Color(0xFF3949AB),
        secondary = Color(0xFF00897B),
        tertiary = Color(0xFF765600)
    )

    LaunchedEffect(Unit) {
        rootAvailable = withContext(Dispatchers.IO) { detectRoot() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            hasLocationPermission(context)
        if (!permissionGranted) {
            scanError = "Location permission is needed to scan nearby WiFi networks."
        }
    }

    fun requestPermissions() {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }.toTypedArray()
        permissionLauncher.launch(permissions)
    }

    fun scanNow() {
        if (!permissionGranted) {
            requestPermissions()
            return
        }
        refreshing = true
        scope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    val manager = context.applicationContext
                        .getSystemService(Context.WIFI_SERVICE) as WifiManager
                    if (!manager.isWifiEnabled) {
                        return@withContext Pair(emptyList<NetworkInfo>(), "WiFi is turned off. Turn it on and refresh.")
                    }
                    @Suppress("DEPRECATION")
                    manager.startScan()
                    @Suppress("DEPRECATION")
                    val found = manager.scanResults.mapNotNull { result ->
                        val name = result.SSID?.takeIf {
                            it.isNotBlank() && it != "<unknown ssid>"
                        } ?: return@mapNotNull null
                        NetworkInfo(
                            name,
                            result.BSSID ?: "Unknown",
                            result.level,
                            result.frequency,
                            describeSecurity(result.capabilities.orEmpty())
                        )
                    }.distinctBy { it.bssid }.sortedByDescending { it.level }
                    Pair(found, if (found.isEmpty()) "No networks found. Try refreshing near your router." else "")
                }
                networks = results.first
                scanError = results.second
                connectedNetwork = findConnectedNetwork(context, networks)
            } catch (_: SecurityException) {
                scanError = "Location permission is required to scan."
            } catch (_: Exception) {
                scanError = "The scan could not be completed. Check that WiFi is on."
            } finally {
                refreshing = false
            }
        }
    }

    LaunchedEffect(permissionGranted) {
        if (!permissionGranted) return@LaunchedEffect
        while (true) {
            scanNow()
            delay(10_000)
        }
    }

    MaterialTheme(colorScheme = scheme) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PersistentTopBanner(rootAvailable)
                NavHost(
                    navController = nav,
                    startDestination = "home",
                    modifier = Modifier.weight(1f)
                ) {
                    composable("home") {
                        HomeScreen(
                            onScan = {
                                if (!permissionGranted) requestPermissions() else nav.navigate("scan")
                            },
                            onSaved = { nav.navigate("saved") },
                            onRouter = { nav.navigate("router") },
                            onSettings = { nav.navigate("settings") },
                            onHelp = { nav.navigate("help") }
                        )
                    }
                    composable("scan") {
                        ScanScreen(
                            networks = networks,
                            connected = connectedNetwork,
                            error = scanError,
                            permissionGranted = permissionGranted,
                            refreshing = refreshing,
                            onNetwork = { selected = it; recovered = null },
                            onRefresh = { scanNow() },
                            onSettings = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = android.net.Uri.parse("package:${context.packageName}")
                                    }
                                )
                            }
                        )
                    }
                    composable("saved") {
                        SavedNetworksScreen(
                            network = connectedNetwork,
                            recovered = recovered,
                            onOpenSettings = { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                            onRecover = {
                                val network = connectedNetwork
                                if (network != null) {
                                    selected = network
                                    recovered = null
                                }
                            },
                            onCopy = { copyText(context, it); scope.launch { snackbar.showSnackbar("Password copied") } },
                            onShare = { shareText(context, it) }
                        )
                    }
                    composable("router") {
                        RouterCheckScreen(
                            context = context,
                            onCopy = { copyText(context, it); scope.launch { snackbar.showSnackbar("Copied") } }
                        )
                    }
                    composable("help") {
                        InformationScreen(
                            "Help",
                            "Scan nearby networks, inspect signal and security information, and open your router settings. Password recovery only checks configuration files that this device permits the app to read. Vendor defaults are unverified suggestions; they are not recovered or confirmed passwords."
                        )
                    }
                    composable("settings") {
                        SettingsScreen(prefs, dark) { value ->
                            prefs.edit().putString("theme", value).apply()
                            dark = value == "dark"
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        nav.navigate("home") { popUpTo("home") { inclusive = false } }
                    }) { Text("Home") }
                    TextButton(onClick = { nav.navigate("scan") }) { Text("Scan") }
                    TextButton(onClick = { nav.navigate("saved") }) { Text("Saved") }
                }
            }
        }

        selected?.let { network ->
            ModalBottomSheet(
                onDismissRequest = { selected = null; recovered = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                NetworkDetailSheet(
                    network = network,
                    recovered = recovered,
                    busy = recoveryBusy,
                    onRecover = {
                        recoveryBusy = true
                        recovered = null
                        scope.launch {
                            recovered = withContext(Dispatchers.IO) {
                                recoverPassword(context, network)
                            }
                            recoveryBusy = false
                        }
                    },
                    onCopy = { copyText(context, it); scope.launch { snackbar.showSnackbar("Password copied") } },
                    onShare = { shareText(context, it) }
                )
            }
        }
    }
}

@Composable
private fun PersistentTopBanner(rootAvailable: Boolean) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (rootAvailable) Color(0xFFD7F5DD) else Color(0xFFFFF0CC)
        )
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                if (rootAvailable) "Root access detected" else "Standard device access",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                if (rootAvailable) {
                    "System configuration files may be readable; availability depends on device policy."
                } else {
                    "Android restricts access to saved WiFi credentials. Recovery checks only accessible files."
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun HomeScreen(
    onScan: () -> Unit,
    onSaved: () -> Unit,
    onRouter: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("WiFi Inspector", style = MaterialTheme.typography.headlineMedium)
        Text("Inspect nearby networks and manage your own connections.")
        Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan networks") }
        OutlinedButton(onClick = onSaved, modifier = Modifier.fillMaxWidth()) { Text("My saved network") }
        OutlinedButton(onClick = onRouter, modifier = Modifier.fillMaxWidth()) { Text("Router check") }
        OutlinedButton(onClick = onHelp, modifier = Modifier.fillMaxWidth()) { Text("Help") }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Settings") }
    }
}

@Composable
private fun ScanScreen(
    networks: List<NetworkInfo>,
    connected: NetworkInfo?,
    error: String,
    permissionGranted: Boolean,
    refreshing: Boolean,
    onNetwork: (NetworkInfo) -> Unit,
    onRefresh: () -> Unit,
    onSettings: () -> Unit
) {
    var drag by remember { mutableStateOf(0f) }
    Column(
        Modifier.fillMaxSize().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Nearby networks", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onRefresh, enabled = !refreshing) {
                Text(if (refreshing) "Refreshing…" else "Refresh")
            }
        }
        if (refreshing) Text("Updating network list…", style = MaterialTheme.typography.labelMedium)
        if (!permissionGranted) {
            Card {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Location permission needed", style = MaterialTheme.typography.titleMedium)
                    Text("Android requires location permission to show nearby WiFi networks.")
                    Button(onClick = onSettings) { Text("Open app settings") }
                }
            }
        }
        connected?.let { network ->
            Card(
                Modifier.fillMaxWidth().clickable { onNetwork(network) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("CONNECTED NETWORK", style = MaterialTheme.typography.labelMedium)
                    Text(network.ssid, style = MaterialTheme.typography.titleMedium)
                    Text("${network.level} dBm • ${network.security} • Tap for details")
                }
            }
        }
        if (error.isNotBlank()) Card { Text(error, Modifier.padding(14.dp)) }
        if (networks.isEmpty() && error.isBlank()) Text("Scanning for nearby networks…")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(networks.filterNot { it.bssid == connected?.bssid }, key = { it.bssid }) { network ->
                Card(Modifier.fillMaxWidth().clickable { onNetwork(network) }) {
                    Column(Modifier.padding(14.dp)) {
                        Text(network.ssid, style = MaterialTheme.typography.titleMedium)
                        Text("${signalBars(network.level)}  ${network.level} dBm • ${network.frequency} MHz")
                        Text("${network.security} • ${vendorFor(network.bssid)}")
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedNetworksScreen(
    network: NetworkInfo?,
    recovered: RecoveredPassword?,
    onOpenSettings: () -> Unit,
    onRecover: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("My saved network", style = MaterialTheme.typography.titleLarge)
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    network?.ssid ?: "Android controls access to saved WiFi connections.",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    if (network == null) "Connect to a WiFi network and scan to see it here. Android’s WiFi settings can manage saved networks."
                    else "Connected network • ${network.security} • ${network.bssid}"
                )
                if (network != null) Button(onClick = onRecover) { Text("Check accessible files for password") }
                Button(onClick = onOpenSettings) { Text("Open WiFi settings") }
            }
        }
        recovered?.let { PasswordDisplay(it, onCopy, onShare) }
        Text(
            "Android does not expose a complete saved-network list to ordinary apps. Password recovery depends on device access and may not be available."
        )
    }
}

@Composable
private fun NetworkDetailSheet(
    network: NetworkInfo,
    recovered: RecoveredPassword?,
    busy: Boolean,
    onRecover: () -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(network.ssid, style = MaterialTheme.typography.headlineSmall)
        Text("BSSID: ${network.bssid}")
        Text("Signal: ${network.level} dBm • Channel ${channelFor(network.frequency)} (${network.frequency} MHz)")
        Text("Security: ${network.security}")
        Text("Router vendor: ${vendorFor(network.bssid)}")
        Button(onClick = onRecover, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Checking accessible files…" else "Recover password")
        }
        recovered?.let { PasswordDisplay(it, onCopy, onShare) }
        Text(
            "Default-password suggestions are not verified. Only use credentials for networks or routers you are authorized to manage.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun PasswordDisplay(
    result: RecoveredPassword,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit
) {
    var visible by remember(result.value) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Password / suggestion", style = MaterialTheme.typography.titleSmall)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    if (visible) result.value else "•".repeat(result.value.length.coerceAtMost(48)),
                    Modifier.padding(14.dp),
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("Source: ${result.source}", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") }
                OutlinedButton(onClick = { onCopy(result.value) }) { Text("Copy") }
                OutlinedButton(onClick = { onShare(result.value) }) { Text("Share") }
            }
        }
    }
}

@Composable
private fun RouterCheckScreen(context: Context, onCopy: (String) -> Unit) {
    val gateway = remember {
        try {
            val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = manager.dhcpInfo.gateway
            if (ip == 0) "" else listOf(0, 8, 16, 24)
                .joinToString(".") { ((ip shr it) and 255).toString() }
        } catch (_: Exception) {
            ""
        }
    }
    val pairs = remember { vendorDefaultPairs() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Router check", style = MaterialTheme.typography.titleLarge)
        Text("Gateway address: ${gateway.ifBlank { "Not available; connect to WiFi first" }}")
        if (gateway.isNotBlank()) {
            Card(Modifier.fillMaxWidth()) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().height(420.dp),
                    factory = { viewContext ->
                        WebView(viewContext).apply {
                            webViewClient = WebViewClient()
                            settings.javaScriptEnabled = false
                            loadUrl("http://$gateway")
                        }
                    },
                    update = { webView ->
                        val requested = "http://$gateway"
                        if (webView.url != requested) webView.loadUrl(requested)
                    }
                )
            }
            OutlinedButton(onClick = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://$gateway")))
                } catch (_: Exception) {
                    Unit
                }
            }) { Text("Open admin page in browser") }
        }
        Text("Common vendor defaults (unverified)", style = MaterialTheme.typography.titleMedium)
        Text("Try these only on a router you own or are authorized to administer. Actual credentials vary by model and are often printed on its label.")
        pairs.forEach { pair ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(pair.first, style = MaterialTheme.typography.titleSmall)
                    Text("${pair.second.first} / ${pair.second.second}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onCopy(pair.second.first) }) { Text("Copy username") }
                        TextButton(onClick = { onCopy(pair.second.second) }) { Text("Copy password") }
                    }
                }
            }
        }
    }
}

private fun vendorDefaultPairs(): List<Pair<String, Pair<String, String>>> = listOf(
    "TP-Link" to ("admin" to "admin"),
    "D-Link" to ("admin" to "admin"),
    "Linksys" to ("admin" to "admin"),
    "Netgear" to ("admin" to "password"),
    "ASUS" to ("admin" to "admin"),
    "Huawei" to ("admin" to "admin"),
    "ZTE" to ("admin" to "admin")
)

@Composable
private fun InformationScreen(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Card { Text(body, Modifier.padding(16.dp)) }
    }
}

@Composable
private fun SettingsScreen(
    prefs: android.content.SharedPreferences,
    dark: Boolean,
    onTheme: (String) -> Unit
) {
    var choice by remember { mutableStateOf(prefs.getString("theme", "system") ?: "system") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)
        Text("Appearance")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system", "light", "dark").forEach { option ->
                OutlinedButton(onClick = { choice = option; onTheme(option) }) {
                    Text(
                        if (choice == option) "✓ ${option.replaceFirstChar { it.uppercase() }}"
                        else option.replaceFirstChar { it.uppercase() }
                    )
                }
            }
        }
        Text("Theme changes apply immediately. Your selection is saved on this device.")
    }
}

private fun recoverPassword(context: Context, network: NetworkInfo): RecoveredPassword {
    val candidatePaths = listOf(
        "/data/misc/apexdata/com.android.wifi/WifiConfigStore.xml",
        "/data/misc/wifi/WifiConfigStore.xml",
        "/data/misc/wifi/wpa_supplicant.conf",
        "/data/misc/wifi/WifiConfigStore.xml.bak",
        "/data/misc/wifi/WifiConfigStore.xml.cfg",
        "/data/misc/wifi/wpa_supplicant.conf.bak",
        "/data/misc/wifi/wpa_supplicant.conf.cfg"
    )
    val rootRead = readAsRoot(candidatePaths, network.ssid)
    if (rootRead != null) return rootRead

    for (path in candidatePaths) {
        val file = File(path)
        if (!file.isFile || !file.canRead()) continue
        try {
            extractPassword(file.readText(), network.ssid)?.let {
                return RecoveredPassword(it, "Readable configuration file: ${file.name}")
            }
        } catch (_: Exception) {
            // Skip unavailable or malformed files.
        }
    }

    val vendor = vendorFor(network.bssid)
    val pair = vendorDefaultPairs().firstOrNull { it.first == vendor }?.second
    if (pair != null) {
        return RecoveredPassword(
            "${pair.first} / ${pair.second}",
            "Unverified $vendor vendor-default suggestion; not recovered from this network"
        )
    }
    val common = listOf("admin / admin", "admin / password", "admin / (router label)")
    return RecoveredPassword(
        common.joinToString("  •  "),
        "Unverified common combinations; not recovered from this network"
    )
}

private fun readAsRoot(paths: List<String>, ssid: String): RecoveredPassword? {
    val escaped = ssid.replace("'", "'\\''")
    for (path in paths) {
        try {
            val process = ProcessBuilder("su", "-c", "cat '${path.replace("'", "'\\''")}'")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                continue
            }
            val content = process.inputStream.bufferedReader().use { it.readText() }
            if (content.contains(escaped, ignoreCase = true)) {
                extractPassword(content, ssid)?.let {
                    return RecoveredPassword(it, "Root-readable configuration file: ${File(path).name}")
                }
            }
        } catch (_: Exception) {
            // Root may be unavailable or the file may be inaccessible.
        }
    }
    return null
}

private fun extractPassword(content: String, ssid: String): String? {
    val decoded = content
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
    val escapedSsid = Regex.escape(ssid)
    val blocks = decoded.split(Regex("(?i)(?=\\b(?:Network|NetworkList|network)\\b)"))
    val relevant = blocks.firstOrNull { block ->
        Regex(escapedSsid, RegexOption.IGNORE_CASE).containsMatchIn(block)
    } ?: return null

    val patterns = listOf(
        Regex("""(?is)<string\s+name=["'](?:PreSharedKey|Password|psk)["']\s*>(.*?)</string>"""),
        Regex("""(?i)["']?(?:PreSharedKey|psk|password)["']?\s*[:=]\s*["']([^"'\r\n}]+)["']"""),
        Regex("""(?i)\bpsk\s*=\s*"?([^";\s}]+)"?\s*;?""")
    )
    return patterns.firstNotNullOfOrNull { regex ->
        regex.find(relevant)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.lowercase(Locale.ROOT) !in setOf("null", "none") }
    }
}

private fun findConnectedNetwork(context: Context, networks: List<NetworkInfo>): NetworkInfo? {
    return try {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val current = manager.connectionInfo
        @Suppress("DEPRECATION")
        val ssid = current.ssid?.trim('"') ?: return null
        networks.firstOrNull { it.ssid == ssid }
    } catch (_: Exception) {
        null
    }
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("WiFi information", text))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share"))
}

private fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

private fun detectRoot(): Boolean {
    val paths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
        "/data/adb/magisk", "/data/adb/ksu", "/data/adb/ap",
        "/system/app/Superuser.apk", "/debug_ramdisk/.magisk"
    )
    if (paths.any { File(it).exists() }) return true
    return try {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val finished = process.waitFor(2, TimeUnit.SECONDS)
        val output = if (finished) process.inputStream.bufferedReader().use { it.readText() } else ""
        if (!finished) process.destroyForcibly()
        finished && output.contains("uid=0")
    } catch (_: Exception) {
        false
    }
}

private fun describeSecurity(capabilities: String): String = when {
    capabilities.contains("SAE", true) -> "WPA3"
    capabilities.contains("WPA", true) -> "WPA/WPA2"
    capabilities.contains("WEP", true) -> "WEP"
    else -> "Open"
}

private fun channelFor(frequency: Int): Int = when (frequency) {
    in 2412..2472 -> (frequency - 2407) / 5
    2484 -> 14
    in 5000..5895 -> (frequency - 5000) / 5
    in 5955..7115 -> (frequency - 5950) / 5
    else -> 0
}

private fun signalBars(dbm: Int): String = when {
    dbm >= -55 -> "▂▄▆█"
    dbm >= -67 -> "▂▄▆"
    dbm >= -78 -> "▂▄"
    else -> "▂"
}

private fun vendorFor(bssid: String): String {
    val prefix = bssid.uppercase(Locale.ROOT).split(":").take(3).joinToString(":")
    return when (prefix) {
        "00:1D:0F", "14:CC:20", "50:C7:BF" -> "TP-Link"
        "00:09:5B", "20:4E:7F" -> "Netgear"
        "00:1B:11", "00:26:5A" -> "D-Link"
        "00:1E:E5", "00:23:69" -> "Linksys"
        "00:1E:10", "00:25:9E" -> "Huawei"
        "00:19:C6" -> "ZTE"
        "00:0C:6E", "AC:22:0B" -> "ASUS"
        else -> "Unknown"
    }
}
