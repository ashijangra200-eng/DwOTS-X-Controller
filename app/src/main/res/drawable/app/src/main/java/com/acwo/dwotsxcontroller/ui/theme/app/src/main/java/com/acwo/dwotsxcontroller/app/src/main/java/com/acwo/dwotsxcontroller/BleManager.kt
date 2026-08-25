package com.acwo.dwotsxcontroller

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        private const val TAG = "DwOTS_BleManager"
        private val DWOTS_NAME_HINTS = listOf("DwOTS", "DWOTS", "ACwO", "ACWO", "Dwots", "dwots")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter
    private val scanner = adapter?.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _services = MutableStateFlow<List<GattServiceInfo>>(emptyList())
    val services: StateFlow<List<GattServiceInfo>> = _services.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private fun log(msg: String) {
        Log.d(TAG, msg)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        _logs.value = (_logs.value + "[$timestamp] $msg").takeLast(200)
    }

    fun clearLogs() { _logs.value = emptyList() }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
            val address = device.address
            val rssi = result.rssi
            val isDwots = DWOTS_NAME_HINTS.any { name.contains(it, ignoreCase = true) }
            val existing = _scannedDevices.value.toMutableList()
            val idx = existing.indexOfFirst { it.address == address }
            val entry = ScannedDevice(device, name, address, rssi, isDwots)
            if (idx >= 0) existing[idx] = entry else {
                existing.add(entry)
                log("Found: $name ($address) RSSI=$rssi ${if (isDwots) "[DwOTS X candidate]" else ""}")
            }
            _scannedDevices.value = existing.sortedWith(compareByDescending<ScannedDevice> { it.isDwotsX }.thenByDescending { it.rssi })
        }
        override fun onScanFailed(errorCode: Int) {
            log("Scan failed: errorCode=$errorCode")
            _isScanning.value = false
        }
    }

    fun startScan() {
        if (adapter == null || !adapter.isEnabled) { log("Bluetooth adapter not available or disabled"); return }
        if (_isScanning.value) return
        _scannedDevices.value = emptyList()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner?.startScan(null, settings, scanCallback)
            _isScanning.value = true
            log("BLE scan started")
        } catch (e: Exception) { log("Failed to start scan: ${e.message}") }
    }

    fun stopScan() {
        if (!_isScanning.value) return
        try { scanner?.stopScan(scanCallback) } catch (e: Exception) { log("Error stopping scan: ${e.message}") }
        _isScanning.value = false
        log("BLE scan stopped")
    }

    fun connect(device: BluetoothDevice) {
        if (_connectionState.value != ConnectionState.DISCONNECTED && _connectionState.value != ConnectionState.DISCONNECTING) {
            log("Already connected or connecting"); return
        }
        stopScan()
        _connectionState.value = ConnectionState.CONNECTING
        _services.value = emptyList()
        _connectedDeviceName.value = device.name ?: device.address
        log("Connecting to \( {device.name ?: "Unknown"} ( \){device.address})…")
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        log("Disconnect requested")
        _connectionState.value = ConnectionState.DISCONNECTING
        gatt?.disconnect()
    }

    private fun closeGatt() {
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDeviceName.value = null
        _services.value = emptyList()
        log("GATT closed")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    log("Connected (status=$status). Requesting MTU & discovering services…")
                    _connectionState.value = ConnectionState.CONNECTED
                    gatt.requestMtu(517)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Disconnected (status=$status)")
                    closeGatt()
                }
            }
        }
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log("MTU changed to $mtu (status=$status)")
            _connectionState.value = ConnectionState.DISCOVERING
            gatt.discoverServices()
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { log("Service discovery failed: status=$status"); return }
            log("Services discovered: ${gatt.services.size} service(s)")
            val infos = gatt.services.map { service ->
                val chars = service.characteristics.map { char ->
                    GattCharacteristicInfo(char.uuid, char.instanceId, char.properties, char.permissions, service.uuid)
                }
                GattServiceInfo(service.uuid, service.instanceId, service.type, chars)
            }
            _services.value = infos
            _connectionState.value = ConnectionState.READY
            infos.forEach { s ->
                log("Service \( {s.uuidString} ( \){s.typeString}) — ${s.characteristics.size} char(s)")
                s.characteristics.forEach { c -> log("  └ Char \( {c.uuidString} [ \){c.propertiesText}]") }
            }
        }
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            handleReadResult(characteristic.uuid, value, status)
        }
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handleReadResult(characteristic.uuid, characteristic.value ?: byteArrayOf(), status)
        }
        private fun handleReadResult(uuid: UUID, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log("Read ${uuid.toString().uppercase()}: \( {value.toHex()} ( \){value.size} bytes)")
                updateCharacteristicValue(uuid, value)
            } else log("Read failed for $uuid — status=$status")
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(characteristic.uuid, value)
        }
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleNotification(characteristic.uuid, characteristic.value ?: byteArrayOf())
        }
        private fun handleNotification(uuid: UUID, value: ByteArray) {
            log("Notify ${uuid.toString().uppercase()}: ${value.toHex()}")
            updateCharacteristicValue(uuid, value)
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val enabled = descriptor.value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
            log("Descriptor write ${descriptor.characteristic.uuid} → ${if (enabled) "NOTIFY ON" else "NOTIFY OFF"} (status=$status)")
            if (status == BluetoothGatt.GATT_SUCCESS) setNotifyingFlag(descriptor.characteristic.uuid, enabled)
        }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            log("Write ${characteristic.uuid.toString().uppercase()} status=$status")
        }
    }

    private fun updateCharacteristicValue(uuid: UUID, value: ByteArray) {
        _services.value = _services.value.map { service ->
            service.copy(characteristics = service.characteristics.map { char ->
                if (char.uuid == uuid) char.copy(lastValue = value) else char
            })
        }
    }

    private fun setNotifyingFlag(uuid: UUID, enabled: Boolean) {
        _services.value = _services.value.map { service ->
            service.copy(characteristics = service.characteristics.map { char ->
                if (char.uuid == uuid) char.copy(isNotifying = enabled) else char
            })
        }
    }

    fun readCharacteristic(serviceUuid: UUID, charUuid: UUID) {
        val gatt = this.gatt ?: run { log("No active GATT connection"); return }
        val service = gatt.getService(serviceUuid) ?: run { log("Service $serviceUuid not found"); return }
        val characteristic = service.getCharacteristic(charUuid) ?: run { log("Characteristic $charUuid not found"); return }
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) { log("Characteristic does not support READ"); return }
        val success = gatt.readCharacteristic(characteristic)
        log("Read request for $charUuid → ${if (success) "queued" else "FAILED"}")
    }

    fun setNotification(serviceUuid: UUID, charUuid: UUID, enable: Boolean) {
        val gatt = this.gatt ?: run { log("No active GATT connection"); return }
        val service = gatt.getService(serviceUuid) ?: return
        val characteristic = service.getCharacteristic(charUuid) ?: return
        val supportsNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!supportsNotify) { log("Characteristic does not support NOTIFY/INDICATE"); return }
        gatt.setCharacteristicNotification(characteristic, enable)
        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) { log("CCCD descriptor not found"); setNotifyingFlag(charUuid, enable); return }
        val value = when {
            enable && characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            enable && characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) gatt.writeDescriptor(descriptor, value)
        else { descriptor.value = value; gatt.writeDescriptor(descriptor) }
        log("${if (enable) "Enabling" else "Disabling"} notification for $charUuid")
    }

    fun release() {
        stopScan()
        gatt?.close()
        gatt = null
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
}
