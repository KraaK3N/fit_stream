package app.fitstream

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { FitStreamScreen() }
            }
        }
    }
}

@Composable
private fun FitStreamScreen() {
    val context = LocalContext.current
    val settings = remember { Settings(context) }
    val sdkStatus = remember { HealthConnectClient.getSdkStatus(context) }

    var url by rememberSaveable { mutableStateOf(settings.serverUrl) }
    var apiKey by rememberSaveable { mutableStateOf(settings.apiKey) }
    var hours by rememberSaveable { mutableStateOf(settings.intervalHours.toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf(settings.lastStatus) }
    var granted by remember { mutableStateOf<Set<String>>(emptySet()) }
    var requested by remember { mutableStateOf<Set<String>>(emptySet()) }

    val workManager = remember { WorkManager.getInstance(context) }
    val nowInfo by remember { workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.ONE_TIME) }.collectAsState(emptyList())
    val periodicInfo by remember { workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.PERIODIC) }.collectAsState(emptyList())
    val running = nowInfo.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    val periodicOn = periodicInfo.any { !it.state.isFinished }

    // The worker writes its result to Settings; refresh the status line whenever work state changes.
    LaunchedEffect(nowInfo, periodicInfo) { status = settings.lastStatus }

    val client = remember { if (sdkStatus == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(context) else null }
    val permissionLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        granted = it
    }
    LaunchedEffect(client) {
        if (client != null) {
            requested = HealthReader.permissionsToRequest(client)
            granted = client.permissionController.getGrantedPermissions()
        }
    }

    val scope = rememberCoroutineScope()
    val scaleReader = remember { ScaleReader(context, settings, client, scope) }
    val scaleState by scaleReader.state.collectAsState()

    val blePermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val allGranted = perms.values.all { it }
        if (allGranted) {
            scaleReader.startScan()
        } else {
            message = "Bluetooth permission is required to scan for the scale"
        }
    }

    fun startScaleScan() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        if (bluetoothManager?.adapter?.isEnabled != true) {
            message = "Please turn on Bluetooth first"
            return
        }
        val hasPerms = blePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (hasPerms) {
            scaleReader.startScan()
        } else {
            blePermissionLauncher.launch(blePermissions)
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("FitStream", style = MaterialTheme.typography.headlineMedium)
        Text("Galaxy Fit3 → Samsung Health → Health Connect → your server", style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("Health Connect", style = MaterialTheme.typography.titleMedium)
        when (sdkStatus) {
            HealthConnectClient.SDK_AVAILABLE -> {
                val readGranted = HealthReader.READ_PERMISSIONS.count { it in granted }
                Text("$readGranted of ${HealthReader.READ_PERMISSIONS.size} data types granted")
                val bgPerm = androidx.health.connect.client.permission.HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND
                if (bgPerm in requested) {
                    Text(if (bgPerm in granted) "Background reads: allowed" else "Background reads: not allowed (periodic sync will fail)")
                }
                Button(onClick = { permissionLauncher.launch(requested) }) { Text("Grant permissions") }
                Text(
                    "In Samsung Health, enable Settings → Health Connect so the Fit3 data shows up here.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                Text("Health Connect needs to be installed or updated.")
                Button(onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Open Play Store") }
            }
            else -> Text("Health Connect is not supported on this device.")
        }

        HorizontalDivider()
        Text("Smart Scale", style = MaterialTheme.typography.titleMedium)
        Text("Dr Trust 526 · Tap to scan, then stand on scale to record weight", style = MaterialTheme.typography.bodySmall)

        when (val st = scaleState) {
            is ScaleState.Idle -> {
                Button(onClick = { startScaleScan() }) {
                    Text("Scan scale")
                }
                if (settings.lastWeight > 0f) {
                    val timeStr = settings.lastWeightTime?.atZone(ZoneId.systemDefault())
                        ?.format(DateTimeFormatter.ofPattern("MMM d, h:mm a")).orEmpty()
                    Text(
                        "Last weight: ${settings.lastWeight} kg" + (if (timeStr.isNotEmpty()) " · $timeStr" else ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            is ScaleState.Scanning -> {
                Text(st.message, color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { scaleReader.cancel() }) {
                    Text("Cancel scan")
                }
            }
            is ScaleState.Connecting -> {
                Text("Connecting to ${st.deviceName}… Stand on the scale barefoot", color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = { scaleReader.cancel() }) {
                    Text("Cancel")
                }
            }
            is ScaleState.Measuring -> {
                Text("⚖️ Measuring: ${st.currentWeightKg} kg… Stand still", style = MaterialTheme.typography.titleMedium)
            }
            is ScaleState.Locked -> {
                Text("🎯 Weight locked: ${st.weightKg} kg! Saving…", style = MaterialTheme.typography.titleMedium)
            }
            is ScaleState.Logged -> {
                Text("✅ ${st.weightKg} kg logged to ${st.details}!", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Button(onClick = { startScaleScan() }) {
                    Text("Scan again")
                }
            }
            is ScaleState.Error -> {
                Text("⚠️ ${st.message}", color = MaterialTheme.colorScheme.error)
                Button(onClick = { startScaleScan() }) {
                    Text("Try again")
                }
            }
        }

        HorizontalDivider()
        Text("Server", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = url, onValueChange = { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Server URL (https://…)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("API key") }, visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            value = hours, onValueChange = { hours = it.filter(Char::isDigit).take(2) }, singleLine = true,
            label = { Text("Sync every (hours)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Button(onClick = {
            val h = hours.toLongOrNull()
            message = when {
                !Settings.isValidUrl(url) -> "URL must start with https://"
                apiKey.trim().length < 32 -> "API key must be at least 32 characters"
                h == null || h !in 1..24 -> "Interval must be 1–24 hours"
                else -> {
                    settings.serverUrl = url
                    settings.apiKey = apiKey
                    settings.intervalHours = h
                    if (periodicOn) SyncWorker.schedulePeriodic(context, h)
                    "Saved"
                }
            }
        }) { Text("Save") }

        HorizontalDivider()
        Text("Sync", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Switch(checked = periodicOn, onCheckedChange = { on ->
                if (on && !settings.isConfigured) message = "Save the server settings first"
                else if (on) SyncWorker.schedulePeriodic(context, settings.intervalHours)
                else SyncWorker.cancelPeriodic(context)
            })
            Text(if (periodicOn) "Automatic sync every ${settings.intervalHours} h" else "Automatic sync off")
        }
        OutlinedButton(
            enabled = !running && client != null,
            onClick = {
                if (!settings.isConfigured) message = "Save the server settings first" else SyncWorker.syncNow(context)
            },
        ) { Text(if (running) "Syncing…" else "Sync now") }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        HorizontalDivider()
        OutlinedButton(onClick = { context.startActivity(Intent(context, PrivacyActivity::class.java)) }) {
            Text("What data is shared")
        }
    }
}
