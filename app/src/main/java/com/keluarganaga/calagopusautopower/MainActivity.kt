package com.keluarganaga.calagopusautopower

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CalagopusApp() }
    }
}

@Composable
fun CalagopusApp() {
    var loggedIn by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var client by remember { mutableStateOf<ApiClient?>(null) }

    MaterialTheme {
        if (!loggedIn) {
            LoginScreen(
                baseUrl = baseUrl,
                username = username,
                password = password,
                onBaseUrl = { baseUrl = it },
                onUsername = { username = it },
                onPassword = { password = it },
                onLogin = {
                    val c = ApiClient(baseUrl.trimEnd('/'), username, password)
                    try {
                        withContext(Dispatchers.IO) { c.status() }
                        client = c
                        loggedIn = true
                    } catch (_: Exception) {
                        throw LoginFailure()
                    }
                }
            )
        } else {
            MainShell(
                client = client!!,
                onLogout = { loggedIn = false; client = null }
            )
        }
    }
}

private class LoginFailure : Exception()

@Composable
fun LoginScreen(
    baseUrl: String,
    username: String,
    password: String,
    onBaseUrl: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onLogin: suspend () -> Unit
) {
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Calagopus AutoPower", style = MaterialTheme.typography.headlineMedium)
        Text("Native server control", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(baseUrl, onBaseUrl, label = { Text("Panel URL") }, placeholder = { Text("https://your-panel.example.com") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(username, onUsername, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(password, onPassword, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                loading = true
                error = ""
                // The UI coroutine performs the network call off the main thread inside MainShell's client.
                kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                    try { onLogin() } catch (_: Exception) { error = "Login failed. Check URL, username, password and HTTPS." }
                    loading = false
                }
            },
            enabled = !loading && baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { if (loading) CircularProgressIndicator() else Text("Connect") }
    }
}

@Composable
fun MainShell(client: ApiClient, onLogout: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val tabs = listOf("Dashboard", "Minecraft", "Velocity", "Settings")

    Scaffold(
        topBar = { TopAppBar(title = { Text(tabs[tab]) }, actions = { TextButton(onClick = onLogout) { Text("Logout") } }) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(title.first().toString()) }, label = { Text(title) })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (tab) {
                0 -> DashboardScreen(client, snackbar)
                1 -> ConsoleScreen(client, false, snackbar)
                2 -> ConsoleScreen(client, true, snackbar)
                3 -> SettingsScreen(client, snackbar)
            }
        }
    }
}

@Composable
fun DashboardScreen(client: ApiClient, snackbar: SnackbarHostState) {
    var status by remember { mutableStateOf<JSONObject?>(null) }
    var resources by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }

    suspend fun refresh() {
        try {
            val pair = withContext(Dispatchers.IO) { client.status() to client.resources() }
            status = pair.first
            resources = pair.second
        } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Connection failed") }
    }

    LaunchedEffect(Unit) { while (true) { refresh(); delay(5000) } }

    val s = status
    val r = resources
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        StatusCard(s)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton("Start", "/api/start", client, snackbar) { busy = it }
            ActionButton("Save", "/api/save", client, snackbar) { busy = it }
            ActionButton("Stop", "/api/stop", client, snackbar) { busy = it }
        }
        Spacer(Modifier.height(12.dp))
        ResourceCard(r)
        Spacer(Modifier.height(12.dp))
        if (busy) CircularProgressIndicator()
    }
}

@Composable
fun ActionButton(label: String, path: String, client: ApiClient, snackbar: SnackbarHostState, setBusy: (Boolean) -> Unit) {
    Button(onClick = {
        setBusy(true)
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
            try {
                val result = withContext(Dispatchers.IO) { client.action(path) }
                snackbar.showSnackbar(result.optString("message", "Done"))
            } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Request failed") }
            setBusy(false)
        }
    }, modifier = Modifier.weight(1f)) { Text(label) }
}

@Composable
fun StatusCard(s: JSONObject?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Paper Server", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Status: ${s?.optString("state", "Loading...") ?: "Loading..."}")
            Text("Players: ${s?.optInt("players", 0) ?: 0} / ${s?.optInt("maxPlayers", 0) ?: 0}")
            Text("Idle limit: ${s?.optInt("idleLimitMinutes", 0) ?: 0} min")
            Text("Idle time: ${formatSeconds(s?.optLong("idleSeconds", 0) ?: 0)}")
            Text("Uptime: ${formatSeconds(s?.optLong("serverUptimeSeconds", 0) ?: 0)}")
        }
    }
}

@Composable
fun ResourceCard(r: JSONObject?) {
    val net = r?.optJSONObject("network")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Resources", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("Memory: ${bytes(r?.optLong("memory_bytes", 0) ?: 0)} / ${bytes(r?.optLong("memory_limit_bytes", 0) ?: 0)}")
            Text("CPU: ${"%.1f".format(r?.optDouble("cpu_absolute", 0.0) ?: 0.0)}%")
            Text("Disk: ${bytes(r?.optLong("disk_bytes", 0) ?: 0)}")
            Text("Network RX: ${bytes(net?.optLong("rx_bytes", 0) ?: 0)}")
            Text("Network TX: ${bytes(net?.optLong("tx_bytes", 0) ?: 0)}")
        }
    }
}

@Composable
fun ConsoleScreen(client: ApiClient, velocity: Boolean, snackbar: SnackbarHostState) {
    var logs by remember { mutableStateOf(if (velocity) "Loading Velocity logs..." else "Loading Minecraft logs...") }
    var command by remember { mutableStateOf("") }

    LaunchedEffect(velocity) {
        while (true) {
            try { logs = withContext(Dispatchers.IO) { if (velocity) client.velocityLogs() else client.minecraftLogs() } }
            catch (e: Exception) { logs = "Error: ${e.message}" }
            delay(3000)
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Card(Modifier.fillMaxWidth().weight(1f)) {
            Text(logs, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState()))
        }
        if (!velocity) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(command, { command = it }, label = { Text("Minecraft command") }, placeholder = { Text("say Hello") }, modifier = Modifier.weight(1f))
                Button(onClick = {
                    if (command.isBlank()) return@Button
                    val c = command
                    command = ""
                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                        try { snackbar.showSnackbar(withContext(Dispatchers.IO) { client.command(c) }.optString("message", "Command sent")) }
                        catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Command failed") }
                    }
                }) { Text("Send") }
            }
        }
    }
}

@Composable
fun SettingsScreen(client: ApiClient, snackbar: SnackbarHostState) {
    var config by remember { mutableStateOf<JSONObject?>(null) }
    var idle by remember { mutableStateOf("") }
    var timeout by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            val c = withContext(Dispatchers.IO) { client.config() }
            config = c
            idle = c.optString("idleMinutes")
            timeout = c.optString("startupTimeoutSeconds")
        } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Unable to load settings") }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Server configuration", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        SettingReadOnly("Calagopus URL", config?.optString("calagopusUrl", "") ?: "")
        SettingReadOnly("Backend server", config?.optString("backendServer", "") ?: "")
        SettingReadOnly("Server UUID", config?.optString("serverUuid", "") ?: "")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(idle, { idle = it }, label = { Text("Idle minutes") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(timeout, { timeout = it }, label = { Text("Startup timeout seconds") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Button(onClick = {
            val c = config ?: return@Button
            val values = mutableMapOf<String, String>()
            val keys = listOf("calagopusUrl", "serverUuid", "apiKeyEnv", "backendServer", "statusHost", "statusPort", "statusTimeoutMillis", "checkIntervalSeconds", "startupTimeoutSeconds", "startupPollSeconds", "idleMinutes", "saveWaitSeconds", "saveBeforeStop", "startupMessage")
            keys.forEach { values[it] = c.optString(it, "") }
            values["idleMinutes"] = idle
            values["startupTimeoutSeconds"] = timeout
            values["saveBeforeStop"] = c.optString("saveBeforeStop", "false")
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                try { snackbar.showSnackbar(withContext(Dispatchers.IO) { client.saveConfig(values) }.optString("message", "Configuration saved")) }
                catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Save failed") }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Save configuration") }
    }
}

@Composable
fun SettingReadOnly(label: String, value: String) {
    OutlinedTextField(value, {}, label = { Text(label) }, enabled = false, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
}

fun formatSeconds(value: Long): String {
    val h = value / 3600
    val m = (value % 3600) / 60
    val s = value % 60
    return if (h > 0) "%dh %02dm %02ds".format(h, m, s) else "%dm %02ds".format(m, s)
}

fun bytes(value: Long): String {
    if (value < 1024) return "$value B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = value.toDouble()
    var i = -1
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return "%.1f %s".format(v, units[i])
}
