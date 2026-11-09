package app.fitstream

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Mass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.roundToLong

sealed class ScaleState {
    object Idle : ScaleState()
    data class Scanning(val message: String = "Scanning for scale… Step on scale to turn it on") : ScaleState()
    data class Connecting(val deviceName: String) : ScaleState()
    data class Measuring(val currentWeightKg: Double) : ScaleState()
    data class Locked(val weightKg: Double) : ScaleState()
    data class Logged(val weightKg: Double, val time: Instant, val details: String) : ScaleState()
    data class Error(val message: String) : ScaleState()
}

/**
 * Handles Bluetooth Low Energy (BLE) scanning, connection, and data extraction
 * for the Dr Trust USA Legend 526 Smart Scale (and compatible AC 27 framing scales).
 */
class ScaleReader(
    private val context: Context,
    private val settings: Settings,
    private val healthClient: HealthConnectClient?,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "ScaleReader"

        val SERVICE_UUID: UUID = UUID.fromString("0000ffb0-0000-1000-8000-00805f9b34fb")
        val CHAR_WRITE_UUID: UUID = UUID.fromString("0000ffb1-0000-1000-8000-00805f9b34fb")
        val CHAR_NOTIFY_UUID: UUID = UUID.fromString("0000ffb2-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private val SCALE_KEYWORDS = listOf("scale", "trust", "dr", "icomon", "senssun", "yolanda", "fg", "sw", "body", "fat", "weight")
        private const val SCAN_TIMEOUT_MS = 30_000L
    }

    private val _state = MutableStateFlow<ScaleState>(ScaleState.Idle)
    val state: StateFlow<ScaleState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var isScanning = false
    private var isFinalLocked = false
    private val recentWeights = mutableListOf<Double>()

    private val scanTimeoutRunnable = Runnable {
        if (isScanning) {
            stopScan()
            _state.value = ScaleState.Error("Scan timed out. Step on the scale to wake it up and try again.")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = (device.name ?: result.scanRecord?.deviceName).orEmpty()
            val address = device.address.orEmpty()
            val serviceUuids = result.scanRecord?.serviceUuids.orEmpty()

            if (isMatchingScale(name, address, serviceUuids)) {
                Log.d(TAG, "Matched scale: $name ($address)")
                stopScan()
                settings.scaleMac = address
                connectGatt(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error code: $errorCode")
            stopScan()
            _state.value = ScaleState.Error("Bluetooth scan failed (code $errorCode)")
        }
    }

    private fun isMatchingScale(name: String, address: String, serviceUuids: List<ParcelUuid>): Boolean {
        val targetMac = settings.scaleMac
        if (targetMac.isNotEmpty() && address.equals(targetMac, ignoreCase = true)) {
            return true
        }
        if (serviceUuids.any { it.uuid.toString().startsWith("0000ffb0", ignoreCase = true) }) {
            return true
        }
        val lower = name.lowercase()
        return SCALE_KEYWORDS.any { lower.contains(it) }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = ScaleState.Error("Bluetooth is turned off. Please enable Bluetooth.")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _state.value = ScaleState.Error("Bluetooth LE scanner is unavailable on this device.")
            return
        }

        disconnectAndCleanup()
        isFinalLocked = false
        recentWeights.clear()
        isScanning = true
        _state.value = ScaleState.Scanning()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val filters = mutableListOf<ScanFilter>()
        val targetMac = settings.scaleMac
        if (BluetoothAdapter.checkBluetoothAddress(targetMac)) {
            filters.add(ScanFilter.Builder().setDeviceAddress(targetMac).build())
        }
        // Also add broad filter so discovery works if MAC changes or isn't saved yet
        filters.add(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build())

        try {
            scanner.startScan(null, scanSettings, scanCallback)
            mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting BLE scan", e)
            isScanning = false
            _state.value = ScaleState.Error("Failed to start scan: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        mainHandler.removeCallbacks(scanTimeoutRunnable)
        if (isScanning) {
            isScanning = false
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            runCatching { bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        }
    }

    fun cancel() {
        stopScan()
        disconnectAndCleanup()
        _state.value = ScaleState.Idle
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(device: BluetoothDevice) {
        val name = device.name ?: "Dr Trust Scale"
        _state.value = ScaleState.Connecting(name)
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectAndCleanup() {
        runCatching {
            gatt?.disconnect()
            gatt?.close()
        }
        gatt = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connected to GATT server. Discovering services…")
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Disconnected from scale")
                if (!isFinalLocked && _state.value !is ScaleState.Logged && _state.value !is ScaleState.Error) {
                    _state.value = ScaleState.Error("Disconnected from scale before weight was locked.")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed with status: $status")
                _state.value = ScaleState.Error("Failed to discover scale services.")
                return
            }

            // Find notify characteristic (0xFFB2)
            var notifyChar: BluetoothGattCharacteristic? = null
            var writeChar: BluetoothGattCharacteristic? = null

            for (service in gatt.services) {
                for (char in service.characteristics) {
                    if (char.uuid == CHAR_NOTIFY_UUID) notifyChar = char
                    if (char.uuid == CHAR_WRITE_UUID) writeChar = char
                }
            }

            if (notifyChar == null) {
                Log.e(TAG, "Could not find notify characteristic 0xFFB2")
                _state.value = ScaleState.Error("Scale characteristic 0xFFB2 not found.")
                return
            }

            gatt.setCharacteristicNotification(notifyChar, true)
            val cccd = notifyChar.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
            } else {
                Log.w(TAG, "CCCD descriptor not found on notify characteristic; sending D0 frame directly")
                writeChar?.let { sendD0Packet(gatt, it) }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "CCCD notification enabled. Sending D0 configuration frame…")
                var writeChar: BluetoothGattCharacteristic? = null
                for (service in gatt.services) {
                    for (char in service.characteristics) {
                        if (char.uuid == CHAR_WRITE_UUID) writeChar = char
                    }
                }
                writeChar?.let { sendD0Packet(gatt, it) }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotificationPacket(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            characteristic.value?.let { handleNotificationPacket(it) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendD0Packet(gatt: BluetoothGatt, writeChar: BluetoothGattCharacteristic) {
        val pkt = buildD0ConfigurationPacket()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(writeChar, pkt, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            writeChar.value = pkt
            @Suppress("DEPRECATION")
            writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(writeChar)
        }
    }

    /**
     * Builds the 20-byte configuration packet required by Dr Trust 526 scales
     * to start the live weight and impedance cycle.
     */
    private fun buildD0ConfigurationPacket(weightKg: Double = 77.0, heightCm: Int = 173, age: Int = 24, isMale: Boolean = true): ByteArray {
        val nowSec = (System.currentTimeMillis() / 1000).toInt()
        val timeBe = ByteBuffer.allocate(4).putInt(nowSec).array()
        val wUnits = ByteBuffer.allocate(2).putShort((weightKg * 100).toInt().toShort()).array()
        val genderCode = if (isMale) 1 else 2

        val pkt = ByteArray(20)
        pkt[0] = 0xAC.toByte()
        pkt[1] = 0x27.toByte()
        System.arraycopy(timeBe, 0, pkt, 2, 4)
        pkt[6] = 0x04.toByte()
        pkt[7] = 0x00.toByte() // 0 = kg
        pkt[8] = 0x01.toByte() // user id
        pkt[9] = (heightCm and 0xFF).toByte()
        pkt[10] = wUnits[0]
        pkt[11] = wUnits[1]
        pkt[12] = (age and 0xFF).toByte()
        pkt[13] = genderCode.toByte()
        pkt[14] = wUnits[0]
        pkt[15] = wUnits[1]
        pkt[16] = 0x03.toByte()
        pkt[17] = 0x00.toByte()
        pkt[18] = 0xD0.toByte()
        var sum = 0
        for (i in 2..16) {
            sum += (pkt[i].toInt() and 0xFF)
        }
        pkt[19] = (sum and 0xFF).toByte()
        return pkt
    }

    private fun handleNotificationPacket(data: ByteArray) {
        if (data.size != 20 || (data[0].toInt() and 0xFF) != 0xAC) return

        val pktType = data[18].toInt() and 0xFF

        // 0xD5: Live/Stabilized Weight packet
        if (pktType == 0xD5) {
            val flags = data[2].toInt() and 0xFF
            val byte17 = data[17].toInt() and 0xFF
            val isStabilized = (flags and 0x01 != 0) || (byte17 == 0x03)
            val b3 = data[3].toInt() and 0xFF
            val b4 = data[4].toInt() and 0xFF
            val b5 = data[5].toInt() and 0xFF
            val rawU24 = (b3 shl 16) or (b4 shl 8) or b5
            val weightGrams = rawU24 and 0x03FFFF
            val weightKg = round2(weightGrams / 1000.0)

            if (weightKg < 5.0) return

            if (!isFinalLocked) {
                recentWeights.add(weightKg)
                if (recentWeights.size > 6) recentWeights.removeAt(0)

                _state.value = ScaleState.Measuring(weightKg)

                val weightStable = recentWeights.size >= 4 && (recentWeights.maxOrNull() ?: 0.0) - (recentWeights.minOrNull() ?: 0.0) <= 0.05
                if (isStabilized || weightStable) {
                    isFinalLocked = true
                    _state.value = ScaleState.Locked(weightKg)
                    disconnectAndCleanup()
                    scope.launch { logWeight(weightKg) }
                }
            }
        }
    }

    private suspend fun logWeight(weightKg: Double) {
        val now = Instant.now()
        val targets = mutableListOf<String>()

        // 1. Log to Health Connect (as a WeightRecord)
        if (healthClient != null) {
            try {
                val record = WeightRecord(
                    time = now,
                    zoneOffset = ZoneId.systemDefault().rules.getOffset(now),
                    weight = Mass.kilograms(weightKg),
                    metadata = Metadata.manualEntry()
                )
                healthClient.insertRecords(listOf(record))
                targets.add("Health Connect")
                Log.d(TAG, "Weight $weightKg kg successfully written to Health Connect")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write WeightRecord to Health Connect", e)
            }
        }

        // 2. Upload directly to FitStream server
        if (settings.isConfigured) {
            try {
                withContext(Dispatchers.IO) {
                    val payload = JSONObject().apply {
                        put("readings", JSONArray().put(
                            JSONObject().apply {
                                put("metric", "weight")
                                put("time", now.truncatedTo(ChronoUnit.SECONDS).toString())
                                put("values", JSONObject().put("kg", weightKg))
                            }
                        ))
                    }
                    Uploader.post(settings.serverUrl, settings.apiKey, payload)
                }
                targets.add("Server")
                Log.d(TAG, "Weight $weightKg kg successfully posted to FitStream server")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to post weight to FitStream server", e)
            }
        }

        settings.lastWeight = weightKg.toFloat()
        settings.lastWeightTime = now

        val destinationStr = if (targets.isNotEmpty()) targets.joinToString(" & ") else "Device"
        _state.value = ScaleState.Logged(weightKg, now, destinationStr)
    }

    private fun round2(v: Double) = (v * 100).roundToLong() / 100.0
}
