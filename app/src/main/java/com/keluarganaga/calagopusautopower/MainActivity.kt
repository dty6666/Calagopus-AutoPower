package com.keluarganaga.calagopusautopower

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CalagopusApp()
        }
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

                onBaseUrl = {
                    baseUrl = it
                },

                onUsername = {
                    username = it
                },

                onPassword = {
                    password = it
                },

                onLoginSuccess = { newClient ->
                    client = newClient
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
}


@Composable
fun LoginScreen(
    baseUrl: String,
    username: String,
    password: String,
    onBaseUrl: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onLoginSuccess: (ApiClient) -> Unit
) {

    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {

        Text(
            text = "Calagopus AutoPower",
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "Native server control",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrl,
            label = {
                Text("Panel URL")
            },
            placeholder = {
                Text("https://your-panel.example.com")
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = username,
            onValueChange = onUsername,
            label = {
                Text("Username")
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = {
                Text("Password")
            },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        if (error.isNotBlank()) {

            Text(
                text = error,
                color = MaterialTheme.colorScheme.error
            )

            Spacer(Modifier.height(8.dp))
        }

        Button(
            onClick = {

                loading = true
                error = ""

                scope.launch {

                    try {

                        val cleanUrl = baseUrl.trim().trimEnd('/')

                        val newClient = ApiClient(
                            cleanUrl,
                            username,
                            password
                        )

                        withContext(Dispatchers.IO) {
                            newClient.status()
                        }

                        onLoginSuccess(newClient)

                    } catch (e: Exception) {

                        error =
                            e.message
                                ?: "Login failed. Check URL, username and password."

                    } finally {

                        loading = false
                    }
                }
            },

            enabled =
                !loading &&
                baseUrl.isNotBlank() &&
                username.isNotBlank() &&
                password.isNotBlank(),

            modifier = Modifier.fillMaxWidth()
        ) {

            if (loading) {

                CircularProgressIndicator()

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

    var tab by remember { mutableStateOf(0) }

    val snackbar = remember {
        SnackbarHostState()
    }

    val tabs = listOf(
        "Dashboard",
        "Minecraft",
        "Velocity",
        "Settings"
    )

    Scaffold(

        topBar = {

            TopAppBar(

                title = {
                    Text(tabs[tab])
                },

                actions = {

                    TextButton(
                        onClick = onLogout
                    ) {
                        Text("Logout")
                    }
                }
            )
        },

        bottomBar = {

            NavigationBar {

                tabs.forEachIndexed { index, title ->

                    NavigationBarItem(

                        selected = tab == index,

                        onClick = {
                            tab = index
                        },

                        icon = {
                            Text(title.first().toString())
                        },

                        label = {
                            Text(title)
                        }
                    )
                }
            }
        },

        snackbarHost = {
            SnackbarHost(snackbar)
        }

    ) { padding ->

        Box(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {

            when (tab) {

                0 -> DashboardScreen(
                    client,
                    snackbar
                )

                1 -> ConsoleScreen(
                    client,
                    false,
                    snackbar
                )

                2 -> ConsoleScreen(
                    client,
                    true,
                    snackbar
                )

                3 -> SettingsScreen(
                    client,
                    snackbar
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

    val scope = rememberCoroutineScope()

    var status by remember {
        mutableStateOf<JSONObject?>(null)
    }

    var resources by remember {
        mutableStateOf<JSONObject?>(null)
    }

    var busy by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(Unit) {

        while (true) {

            try {

                val result = withContext(Dispatchers.IO) {

                    val newStatus = client.status()
                    val newResources = client.resources()

                    Pair(
                        newStatus,
                        newResources
                    )
                }

                status = result.first
                resources = result.second

            } catch (e: Exception) {

                snackbar.showSnackbar(
                    e.message ?: "Connection failed"
                )
            }

            delay(5000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {

        StatusCard(status)

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            ActionButton(
                label = "Start",
                path = "/api/start",
                client = client,
                snackbar = snackbar,
                setBusy = {
                    busy = it
                }
            )

            ActionButton(
                label = "Save",
                path = "/api/save",
                client = client,
                snackbar = snackbar,
                setBusy = {
                    busy = it
                }
            )

            ActionButton(
                label = "Stop",
                path = "/api/stop",
                client = client,
                snackbar = snackbar,
                setBusy = {
                    busy = it
                }
            )
        }

        Spacer(Modifier.height(12.dp))

        ResourceCard(resources)

        Spacer(Modifier.height(12.dp))

        if (busy) {

            CircularProgressIndicator()
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

                    val result =
                        withContext(Dispatchers.IO) {
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
                        e.message ?: "Request failed"
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
fun StatusCard(
    status: JSONObject?
) {

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier = Modifier.padding(16.dp)
        ) {

            Text(
                "Paper Server",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Status: ${
                    status?.optString(
                        "state",
                        "Loading..."
                    ) ?: "Loading..."
                }"
            )

            Text(
                "Players: ${
                    status?.optInt(
                        "players",
                        0
                    ) ?: 0
                } / ${
                    status?.optInt(
                        "maxPlayers",
                        0
                    ) ?: 0
                }"
            )

            Text(
                "Idle limit: ${
                    status?.optInt(
                        "idleLimitMinutes",
                        0
                    ) ?: 0
                } min"
            )

            Text(
                "Idle time: ${
                    formatSeconds(
                        status?.optLong(
                            "idleSeconds",
                            0
                        ) ?: 0
                    )
                }"
            )

            Text(
                "Uptime: ${
                    formatSeconds(
                        status?.optLong(
                            "serverUptimeSeconds",
                            0
                        ) ?: 0
                    )
                }"
            )
        }
    }
}


@Composable
fun ResourceCard(
    resources: JSONObject?
) {

    val network =
        resources?.optJSONObject("network")

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {

        Column(
            modifier = Modifier.padding(16.dp)
        ) {

            Text(
                "Resources",
                style = MaterialTheme.typography.titleLarge
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Memory: ${
                    bytes(
                        resources?.optLong(
                            "memory_bytes",
                            0
                        ) ?: 0
                    )
                } / ${
                    bytes(
                        resources?.optLong(
                            "memory_limit_bytes",
                            0
                        ) ?: 0
                    )
                }"
            )

            Text(
                "CPU: ${
                    "%.1f".format(
                        resources?.optDouble(
                            "cpu_absolute",
                            0.0
                        ) ?: 0.0
                    )
                }%"
            )

            Text(
                "Disk: ${
                    bytes(
                        resources?.optLong(
                            "disk_bytes",
                            0
                        ) ?: 0
                    )
                }"
            )

            Text(
                "Network RX: ${
                    bytes(
                        network?.optLong(
                            "rx_bytes",
                            0
                        ) ?: 0
                    )
                }"
            )

            Text(
                "Network TX: ${
                    bytes(
                        network?.optLong(
                            "tx_bytes",
                            0
                        ) ?: 0
                    )
                }"
            )
        }
    }
}


@Composable
fun ConsoleScreen(
    client: ApiClient,
    velocity: Boolean,
    snackbar: SnackbarHostState
) {

    val scope = rememberCoroutineScope()

    var logs by remember {

        mutableStateOf(
            if (velocity)
                "Loading Velocity logs..."
            else
                "Loading Minecraft logs..."
        )
    }

    var command by remember {
        mutableStateOf("")
    }

    LaunchedEffect(velocity) {

        while (true) {

            try {

                logs =
                    withContext(Dispatchers.IO) {

                        if (velocity)
                            client.velocityLogs()
                        else
                            client.minecraftLogs()
                    }

            } catch (e: Exception) {

                logs =
                    "Error: ${e.message}"
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

            Text(
                text = logs,
                fontFamily = FontFamily.Monospace,

                modifier = Modifier
                    .padding(10.dp)
                    .verticalScroll(
                        rememberScrollState()
                    )
            )
        }

        if (!velocity) {

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {

                OutlinedTextField(

                    value = command,

                    onValueChange = {
                        command = it
                    },

                    label = {
                        Text("Minecraft command")
                    },

                    placeholder = {
                        Text("say Hello")
                    },

                    modifier = Modifier.weight(1f)
                )

                Button(

                    onClick = {

                        if (command.isBlank()) {
                            return@Button
                        }

                        val commandToSend =
                            command

                        command = ""

                        scope.launch {

                            try {

                                val result =
                                    withContext(Dispatchers.IO) {
                                        client.command(
                                            commandToSend
                                        )
                                    }

                                snackbar.showSnackbar(
                                    result.optString(
                                        "message",
                                        "Command sent"
                                    )
                                )

                            } catch (e: Exception) {

                                snackbar.showSnackbar(
                                    e.message
                                        ?: "Command failed"
                                )
                            }
                        }
                    }
                ) {

                    Text("Send")
                }
            }
        }
    }
}


@Composable
fun SettingsScreen(
    client: ApiClient,
    snackbar: SnackbarHostState
) {

    val scope = rememberCoroutineScope()

    var config by remember {
        mutableStateOf<JSONObject?>(null)
    }

    var idle by remember {
        mutableStateOf("")
    }

    var timeout by remember {
        mutableStateOf("")
    }

    LaunchedEffect(Unit) {

        try {

            val newConfig =
                withContext(Dispatchers.IO) {
                    client.config()
                }

            config = newConfig

            idle =
                newConfig.optString(
                    "idleMinutes"
                )

            timeout =
                newConfig.optString(
                    "startupTimeoutSeconds"
                )

        } catch (e: Exception) {

            snackbar.showSnackbar(
                e.message
                    ?: "Unable to load settings"
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(
                rememberScrollState()
            )
            .padding(16.dp)
    ) {

        Text(
            "Server configuration",
            style = MaterialTheme.typography.headlineSmall
        )

        Spacer(Modifier.height(12.dp))

        SettingReadOnly(
            "Calagopus URL",
            config?.optString(
                "calagopusUrl",
                ""
            ) ?: ""
        )

        SettingReadOnly(
            "Backend server",
            config?.optString(
                "backendServer",
                ""
            ) ?: ""
        )

        SettingReadOnly(
            "Server UUID",
            config?.optString(
                "serverUuid",
                ""
            ) ?: ""
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = idle,
            onValueChange = {
                idle = it
            },
            label = {
                Text("Idle minutes")
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = timeout,
            onValueChange = {
                timeout = it
            },
            label = {
                Text("Startup timeout seconds")
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        Button(

            onClick = {

                val currentConfig =
                    config ?: return@Button

                val values =
                    mutableMapOf<String, String>()

                val keys = listOf(
                    "calagopusUrl",
                    "serverUuid",
                    "apiKeyEnv",
                    "backendServer",
                    "statusHost",
                    "statusPort",
                    "statusTimeoutMillis",
                    "checkIntervalSeconds",
                    "startupTimeoutSeconds",
                    "startupPollSeconds",
                    "idleMinutes",
                    "saveWaitSeconds",
                    "saveBeforeStop",
                    "startupMessage"
                )

                keys.forEach { key ->

                    values[key] =
                        currentConfig.optString(
                            key,
                            ""
                        )
                }

                values["idleMinutes"] = idle

                values["startupTimeoutSeconds"] =
                    timeout

                values["saveBeforeStop"] =
                    currentConfig.optString(
                        "saveBeforeStop",
                        "false"
                    )

                scope.launch {

                    try {

                        val result =
                            withContext(Dispatchers.IO) {
                                client.saveConfig(
                                    values
                                )
                            }

                        snackbar.showSnackbar(
                            result.optString(
                                "message",
                                "Configuration saved"
                            )
                        )

                    } catch (e: Exception) {

                        snackbar.showSnackbar(
                            e.message
                                ?: "Save failed"
                        )
                    }
                }
            },

            modifier = Modifier.fillMaxWidth()
        ) {

            Text("Save configuration")
        }
    }
}


@Composable
fun SettingReadOnly(
    label: String,
    value: String
) {

    OutlinedTextField(
        value = value,
        onValueChange = {},
        label = {
            Text(label)
        },
        enabled = false,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(Modifier.height(8.dp))
}


fun formatSeconds(
    value: Long
): String {

    val hours = value / 3600

    val minutes =
        (value % 3600) / 60

    val seconds =
        value % 60

    return if (hours > 0) {

        "%dh %02dm %02ds".format(
            hours,
            minutes,
            seconds
        )

    } else {

        "%dm %02ds".format(
            minutes,
            seconds
        )
    }
}


fun bytes(
    value: Long
): String {

    if (value < 1024) {
        return "$value B"
    }

    val units =
        listOf(
            "KB",
            "MB",
            "GB",
            "TB"
        )

    var current =
        value.toDouble()

    var index = -1

    while (
        current >= 1024 &&
        index < units.lastIndex
    ) {

        current /= 1024
        index++
    }

    return "%.1f %s".format(
        current,
        units[index]
    )
}
