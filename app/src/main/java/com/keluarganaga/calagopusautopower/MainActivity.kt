package com.keluarganaga.calagopusautopower

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                CalagopusApp()
            }
        }
    }
}

@Composable
fun CalagopusApp() {

    var loggedIn by remember { mutableStateOf(false) }

    var baseUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    var client by remember {
        mutableStateOf<ApiClient?>(null)
    }

    if (!loggedIn || client == null) {

        LoginScreen(
            baseUrl = baseUrl,
            username = username,
            password = password,

            onBaseUrlChange = {
                baseUrl = it
            },

            onUsernameChange = {
                username = it
            },

            onPasswordChange = {
                password = it
            },

            onLoginSuccess = {
                client = it
                loggedIn = true
            }
        )

    } else {

        MainShell(
            client = client!!,
            onLogout = {
                loggedIn = false
                client = null
            }
        )
    }
}

@Composable
fun LoginScreen(
    baseUrl: String,
    username: String,
    password: String,
    onBaseUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLoginSuccess: (ApiClient) -> Unit
) {

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Text(
            text = "Calagopus AutoPower",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Server Control Panel",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text("Panel URL")
            },
            placeholder = {
                Text("https://example.com")
            },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text("Username")
            },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text("Password")
            },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (error.isNotEmpty()) {

            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))
        }

        Button(
            onClick = {

                if (baseUrl.isBlank()) {
                    error = "Enter the panel URL."
                    return@Button
                }

                if (username.isBlank()) {
                    error = "Enter the username."
                    return@Button
                }

                if (password.isBlank()) {
                    error = "Enter the password."
                    return@Button
                }

                loading = true
                error = ""

                scope.launch {

                    try {

                        val newClient = ApiClient(
                            baseUrl.trimEnd('/'),
                            username,
                            password
                        )

                        withContext(Dispatchers.IO) {
                            newClient.status()
                        }

                        onLoginSuccess(newClient)

                    } catch (e: Exception) {

                        error = e.message ?: "Unable to connect to the panel."

                    } finally {

                        loading = false
                    }
                }
            },

            modifier = Modifier.fillMaxWidth(),

            enabled = !loading
        ) {

            if (loading) {

                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp)
                )

            } else {

                Text("Connect")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    client: ApiClient,
    onLogout: () -> Unit
) {

    var selectedTab by remember { mutableStateOf(0) }

    val snackbar = remember {
        SnackbarHostState()
    }

    Scaffold(

        topBar = {

            TopAppBar(
                title = {
                    Text(
                        when (selectedTab) {
                            0 -> "Dashboard"
                            1 -> "Minecraft Console"
                            2 -> "Velocity Console"
                            else -> "Settings"
                        }
                    )
                }
            )
        },

        bottomBar = {

            NavigationBar {

                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                    },
                    icon = {},
                    label = {
                        Text("Dashboard")
                    }
                )

                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                    },
                    icon = {},
                    label = {
                        Text("Minecraft")
                    }
                )

                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = {
                        selectedTab = 2
                    },
                    icon = {},
                    label = {
                        Text("Velocity")
                    }
                )

                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = {
                        selectedTab = 3
                    },
                    icon = {},
                    label = {
                        Text("Settings")
                    }
                )
            }
        },

        snackbarHost = {
            SnackbarHost(snackbar)
        }

    ) { padding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

            when (selectedTab) {

                0 -> DashboardScreen(
                    client = client,
                    snackbar = snackbar
                )

                1 -> ConsoleScreen(
                    title = "Minecraft Console",
                    client = client,
                    minecraft = true,
                    snackbar = snackbar
                )

                2 -> ConsoleScreen(
                    title = "Velocity Console",
                    client = client,
                    minecraft = false,
                    snackbar = snackbar
                )

                3 -> SettingsScreen(
                    client = client,
                    snackbar = snackbar,
                    onLogout = onLogout
                )
            }
        }
    }
}

@Composable
fun DashboardScreen(
    client: ApiClient,
    snackbar: SnackbarHostState
) {

    var status by remember {
        mutableStateOf<JSONObject?>(null)
    }

    var resources by remember {
        mutableStateOf<JSONObject?>(null)
    }

    var loading by remember {
        mutableStateOf(true)
    }

    val scope = rememberCoroutineScope()

    suspend fun refresh() {

        try {

            val newStatus = withContext(Dispatchers.IO) {
                client.status()
            }

            val newResources = withContext(Dispatchers.IO) {
                client.resources()
            }

            status = newStatus
            resources = newResources

        } catch (e: Exception) {

            snackbar.showSnackbar(
                e.message ?: "Failed to refresh status."
            )

        } finally {

            loading = false
        }
    }

    LaunchedEffect(Unit) {

        while (true) {

            refresh()

            delay(5000)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),

        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        item {

            ServerStatusCard(
                status = status,
                loading = loading
            )
        }

        item {

            PlayerCard(
                status = status
            )
        }

        item {

            ResourceCard(
                resources = resources
            )
        }

        item {

            Text(
                text = "Server Controls",
                style = MaterialTheme.typography.titleLarge
            )
        }

        item {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                ActionButton(
                    label = "Start",
                    path = "/api/start",
                    client = client,
                    snackbar = snackbar,
                    setBusy = {}
                )

                ActionButton(
                    label = "Stop",
                    path = "/api/stop",
                    client = client,
                    snackbar = snackbar,
                    setBusy = {}
                )
            }
        }

        item {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                ActionButton(
                    label = "Save",
                    path = "/api/save",
                    client = client,
                    snackbar = snackbar,
                    setBusy = {}
                )

                Button(
                    onClick = {

                        scope.launch {

                            try {

                                val result = withContext(Dispatchers.IO) {
                                    client.action("/api/config/reload")
                                }

                                snackbar.showSnackbar(
                                    result.optString(
                                        "message",
                                        "Configuration reloaded."
                                    )
                                )

                            } catch (e: Exception) {

                                snackbar.showSnackbar(
                                    e.message ?: "Reload failed."
                                )
                            }
                        }
                    },

                    modifier = Modifier.weight(1f)
                ) {

                    Text("Reload")
                }
            }
        }

        item {

            Spacer(
                modifier = Modifier.height(16.dp)
            )
        }
    }
}

@Composable
fun ServerStatusCard(
    status: JSONObject?,
    loading: Boolean
) {

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier = Modifier.padding(16.dp)
        ) {

            Text(
                text = "Paper Server",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            if (loading && status == null) {

                CircularProgressIndicator()

            } else {

                val state =
                    status?.optString("state", "UNKNOWN")
                        ?: "UNKNOWN"

                val starting =
                    status?.optBoolean("starting", false)
                        ?: false

                val stopping =
                    status?.optBoolean("stopping", false)
                        ?: false

                Text(
                    text = state.uppercase(),
                    style = MaterialTheme.typography.headlineSmall
                )

                if (starting) {

                    Text("Starting...")
                }

                if (stopping) {

                    Text("Stopping...")
                }
            }
        }
    }
}

@Composable
fun PlayerCard(
    status: JSONObject?
) {

    val players =
        status?.optInt("players", 0)
            ?: 0

    val maxPlayers =
        status?.optInt("maxPlayers", 0)
            ?: 0

    val idleLimit =
        status?.optInt("idleLimitMinutes", 0)
            ?: 0

    val idleSeconds =
        status?.optLong("idleSeconds", -1)
            ?: -1

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier = Modifier.padding(16.dp)
        ) {

            Text(
                text = "Players",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            Text(
                text = "$players / $maxPlayers",
                style = MaterialTheme.typography.headlineSmall
            )

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            Text(
                text = "Idle limit: ${idleLimit} min"
            )

            if (idleSeconds >= 0) {

                Text(
                    text = "Idle time: ${formatSeconds(idleSeconds)}"
                )

            } else {

                Text("Idle time: Not idle")
            }
        }
    }
}

@Composable
fun ResourceCard(
    resources: JSONObject?
) {

    val data =
        resources?.optJSONObject("resources")

    val memory =
        data?.optLong("memory_bytes", 0)
            ?: 0

    val memoryLimit =
        data?.optLong("memory_limit_bytes", 0)
            ?: 0

    val cpu =
        data?.optDouble("cpu_absolute", 0.0)
            ?: 0.0

    val disk =
        data?.optLong("disk_bytes", 0)
            ?: 0

    val network =
        data?.optJSONObject("network")

    val rx =
        network?.optLong("rx_bytes", 0)
            ?: 0

    val tx =
        network?.optLong("tx_bytes", 0)
            ?: 0

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier = Modifier.padding(16.dp)
        ) {

            Text(
                text = "Resources",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(
                modifier = Modifier.height(8.dp)
            )

            Text(
                text = "Memory: ${formatBytes(memory)} / ${formatBytes(memoryLimit)}"
            )

            Text(
                text = "CPU: ${String.format("%.1f", cpu)}%"
            )

            Text(
                text = "Disk: ${formatBytes(disk)}"
            )

            Text(
                text = "Network RX: ${formatBytes(rx)}"
            )

            Text(
                text = "Network TX: ${formatBytes(tx)}"
            )
        }
    }
}

@Composable
fun RowScope.ActionButton(
    label: String,
    path: String,
    client: ApiClient,
    snackbar: SnackbarHostState,
    setBusy: (Boolean) -> Unit
) {

    val scope = rememberCoroutineScope()

    Button(
        onClick = {

            setBusy(true)

            scope.launch {

                try {

                    val result = withContext(Dispatchers.IO) {
                        client.action(path)
                    }

                    snackbar.showSnackbar(
                        result.optString(
                            "message",
                            "Done"
                        )
                    )

                } catch (e: Exception) {

                    snackbar.showSnackbar(
                        e.message ?: "Request failed."
                    )

                } finally {

                    setBusy(false)
                }
            }
        },

        modifier = Modifier.weight(1f)
    ) {

        Text(label)
    }
}

@Composable
fun ConsoleScreen(
    title: String,
    client: ApiClient,
    minecraft: Boolean,
    snackbar: SnackbarHostState
) {

    var logs by remember {
        mutableStateOf("")
    }

    var command by remember {
        mutableStateOf("")
    }

    var sending by remember {
        mutableStateOf(false)
    }

    val scope = rememberCoroutineScope()

    LaunchedEffect(minecraft) {

        while (true) {

            try {

                logs = withContext(Dispatchers.IO) {

                    if (minecraft) {
                        client.minecraftLogs()
                    } else {
                        client.velocityLogs()
                    }
                }

            } catch (e: Exception) {

                logs = "Error loading logs:\n${e.message}"
            }

            delay(3000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {

                item {

                    Text(
                        text = logs,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(
            modifier = Modifier.height(8.dp)
        )

        if (minecraft) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {

                OutlinedTextField(
                    value = command,
                    onValueChange = {
                        command = it
                    },
                    modifier = Modifier.weight(1f),
                    label = {
                        Text("Command")
                    },
                    singleLine = true
                )

                Button(
                    onClick = {

                        if (command.isBlank()) {
                            return@Button
                        }

                        sending = true

                        scope.launch {

                            try {

                                val result =
                                    withContext(Dispatchers.IO) {
                                        client.command(command)
                                    }

                                snackbar.showSnackbar(
                                    result.optString(
                                        "message",
                                        "Command sent."
                                    )
                                )

                                command = ""

                            } catch (e: Exception) {

                                snackbar.showSnackbar(
                                    e.message ?: "Command failed."
                                )

                            } finally {

                                sending = false
                            }
                        }
                    },

                    enabled = !sending
                ) {

                    Text("Send")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    client: ApiClient,
    snackbar: SnackbarHostState,
    onLogout: () -> Unit
) {

    var loaded by remember {
        mutableStateOf(false)
    }

    var calagopusUrl by remember {
        mutableStateOf("")
    }

    var serverUuid by remember {
        mutableStateOf("")
    }

    var apiKeyEnv by remember {
        mutableStateOf("")
    }

    var backendServer by remember {
        mutableStateOf("")
    }

    var statusHost by remember {
        mutableStateOf("")
    }

    var statusPort by remember {
        mutableStateOf("")
    }

    var statusTimeoutMillis by remember {
        mutableStateOf("")
    }

    var checkIntervalSeconds by remember {
        mutableStateOf("")
    }

    var startupTimeoutSeconds by remember {
        mutableStateOf("")
    }

    var startupPollSeconds by remember {
        mutableStateOf("")
    }

    var idleMinutes by remember {
        mutableStateOf("")
    }

    var saveWaitSeconds by remember {
        mutableStateOf("")
    }

    var saveBeforeStop by remember {
        mutableStateOf("")
    }

    var startupMessage by remember {
        mutableStateOf("")
    }

    var saving by remember {
        mutableStateOf(false)
    }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {

        try {

            val config = withContext(Dispatchers.IO) {
                client.config()
            }

            calagopusUrl =
                config.optString("calagopusUrl", "")

            serverUuid =
                config.optString("serverUuid", "")

            apiKeyEnv =
                config.optString("apiKeyEnv", "")

            backendServer =
                config.optString("backendServer", "")

            statusHost =
                config.optString("statusHost", "")

            statusPort =
                config.optString("statusPort", "")

            statusTimeoutMillis =
                config.optString("statusTimeoutMillis", "")

            checkIntervalSeconds =
                config.optString("checkIntervalSeconds", "")

            startupTimeoutSeconds =
                config.optString("startupTimeoutSeconds", "")

            startupPollSeconds =
                config.optString("startupPollSeconds", "")

            idleMinutes =
                config.optString("idleMinutes", "")

            saveWaitSeconds =
                config.optString("saveWaitSeconds", "")

            saveBeforeStop =
                config.optString("saveBeforeStop", "")

            startupMessage =
                config.optString("startupMessage", "")

            loaded = true

        } catch (e: Exception) {

            snackbar.showSnackbar(
                e.message ?: "Failed to load configuration."
            )
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),

        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        item {

            Text(
                text = "Configuration",
                style = MaterialTheme.typography.headlineSmall
            )
        }

        item {

            ConfigField(
                label = "Calagopus URL",
                value = calagopusUrl,
                onValueChange = {
                    calagopusUrl = it
                }
            )
        }

        item {

            ConfigField(
                label = "Server UUID",
                value = serverUuid,
                onValueChange = {
                    serverUuid = it
                }
            )
        }

        item {

            ConfigField(
                label = "API Key Environment",
                value = apiKeyEnv,
                onValueChange = {
                    apiKeyEnv = it
                }
            )
        }

        item {

            ConfigField(
                label = "Backend Server",
                value = backendServer,
                onValueChange = {
                    backendServer = it
                }
            )
        }

        item {

            ConfigField(
                label = "Status Host",
                value = statusHost,
                onValueChange = {
                    statusHost = it
                }
            )
        }

        item {

            ConfigField(
                label = "Status Port",
                value = statusPort,
                onValueChange = {
                    statusPort = it
                }
            )
        }

        item {

            ConfigField(
                label = "Status Timeout Millis",
                value = statusTimeoutMillis,
                onValueChange = {
                    statusTimeoutMillis = it
                }
            )
        }

        item {

            ConfigField(
                label = "Check Interval Seconds",
                value = checkIntervalSeconds,
                onValueChange = {
                    checkIntervalSeconds = it
                }
            )
        }

        item {

            ConfigField(
                label = "Startup Timeout Seconds",
                value = startupTimeoutSeconds,
                onValueChange = {
                    startupTimeoutSeconds = it
                }
            )
        }

        item {

            ConfigField(
                label = "Startup Poll Seconds",
                value = startupPollSeconds,
                onValueChange = {
                    startupPollSeconds = it
                }
            )
        }

        item {

            ConfigField(
                label = "Idle Minutes",
                value = idleMinutes,
                onValueChange = {
                    idleMinutes = it
                }
            )
        }

        item {

            ConfigField(
                label = "Save Wait Seconds",
                value = saveWaitSeconds,
                onValueChange = {
                    saveWaitSeconds = it
                }
            )
        }

        item {

            ConfigField(
                label = "Save Before Stop",
                value = saveBeforeStop,
                onValueChange = {
                    saveBeforeStop = it
                }
            )
        }

        item {

            ConfigField(
                label = "Startup Message",
                value = startupMessage,
                onValueChange = {
                    startupMessage = it
                },
                singleLine = false
            )
        }

        item {

            Button(
                onClick = {

                    saving = true

                    scope.launch {

                        try {

                            val values =
                                mapOf(
                                    "calagopusUrl" to calagopusUrl,
                                    "serverUuid" to serverUuid,
                                    "apiKeyEnv" to apiKeyEnv,
                                    "backendServer" to backendServer,
                                    "statusHost" to statusHost,
                                    "statusPort" to statusPort,
                                    "statusTimeoutMillis" to statusTimeoutMillis,
                                    "checkIntervalSeconds" to checkIntervalSeconds,
                                    "startupTimeoutSeconds" to startupTimeoutSeconds,
                                    "startupPollSeconds" to startupPollSeconds,
                                    "idleMinutes" to idleMinutes,
                                    "saveWaitSeconds" to saveWaitSeconds,
                                    "saveBeforeStop" to saveBeforeStop,
                                    "startupMessage" to startupMessage
                                )

                            val result =
                                withContext(Dispatchers.IO) {
                                    client.saveConfig(values)
                                }

                            snackbar.showSnackbar(
                                result.optString(
                                    "message",
                                    "Configuration saved."
                                )
                            )

                        } catch (e: Exception) {

                            snackbar.showSnackbar(
                                e.message
                                    ?: "Failed to save configuration."
                            )

                        } finally {

                            saving = false
                        }
                    }
                },

                enabled = loaded && !saving,

                modifier = Modifier.fillMaxWidth()
            ) {

                if (saving) {

                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp)
                    )

                } else {

                    Text("Save Configuration")
                }
            }
        }

        item {

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {

            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth()
            ) {

                Text("Logout")
            }
        }

        item {

            Spacer(
                modifier = Modifier.height(32.dp)
            )
        }
    }
}

@Composable
fun ConfigField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true
) {

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = {
            Text(label)
        },
        singleLine = singleLine
    )
}

fun formatBytes(bytes: Long): String {

    if (bytes <= 0) {
        return "0 B"
    }

    val units = arrayOf(
        "B",
        "KB",
        "MB",
        "GB",
        "TB"
    )

    var value = bytes.toDouble()
    var index = 0

    while (value >= 1024 && index < units.size - 1) {

        value /= 1024
        index++
    }

    return String.format(
        "%.1f %s",
        value,
        units[index]
    )
}

fun formatSeconds(seconds: Long): String {

    if (seconds < 0) {
        return "Not idle"
    }

    val minutes = seconds / 60
    val remainingSeconds = seconds % 60

    return if (minutes > 0) {

        "${minutes}m ${remainingSeconds}s"

    } else {

        "${remainingSeconds}s"
    }
}
