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
                log("Found: $name ($address) RSSI=$rssi")
            }
            _scannedDevices.value = existing.sortedWith(
                compareByDescending<ScannedDevice> { it.isDwotsX }.thenByDescending { it.rssi }
            )
        }
        override fun onScanFailed(errorCode: Int) {
            log("Scan failed: $errorCode")
            _isScanning.value = false
        }
    }

    fun startScan() {
        if (adapter == null || !adapter.isEnabled) { log("Bluetooth off"); return }
        if (_isScanning.value) return
        _scannedDevices.value = emptyList()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner?.startScan(null, settings, scanCallback)
            _isScanning.value = true
            log("Scan started")
        } catch (e: Exception) { log("Scan error: ${e.message}") }
    }

    fun stopScan() {
        if (!_isScanning.value) return
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        _isScanning.value = false
        log("Scan stopped")
    }

    fun connect(device: BluetoothDevice) {
        stopScan()
        _connectionState.value = ConnectionState.CONNECTING
        _services.value = emptyList()
        _connectedDeviceName.value = device.name ?: device.address
        log("Connecting to ${device.address}")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTING
        gatt?.disconnect()
    }

    private fun closeGatt() {
        gatt?.close()
        gatt = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _connectedDeviceName.value = null
        _services.value = emptyList()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                _connectionState.value = ConnectionState.CONNECTED
                g.requestMtu(517)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeGatt()
            }
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            _connectionState.value = ConnectionState.DISCOVERING
            g.discoverServices()
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            _services.value = g.services.map { service ->
                GattServiceInfo(
                    service.uuid, service.instanceId, service.type,
                    service.characteristics.map { c ->
                        GattCharacteristicInfo(c.uuid, c.instanceId, c.properties, c.permissions, service.uuid)
                    }
                )
            }
            _connectionState.value = ConnectionState.READY
            log("Services: ${g.services.size}")
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) updateValue(c.uuid, value)
        }
        @Deprecated("Deprecated")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) updateValue(c.uuid, c.value ?: byteArrayOf())
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            updateValue(c.uuid, value)
        }
        @Deprecated("Deprecated")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            updateValue(c.uuid, c.value ?: byteArrayOf())
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val on = d.value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
            if (status == BluetoothGatt.GATT_SUCCESS) setNotifyFlag(d.characteristic.uuid, on)
        }
    }

    private fun updateValue(uuid: UUID, value: ByteArray) {
        _services.value = _services.value.map { s ->
            s.copy(characteristics = s.characteristics.map { c ->
                if (c.uuid == uuid) c.copy(lastValue = value) else c
            })
        }
    }

    private fun setNotifyFlag(uuid: UUID, enabled: Boolean) {
        _services.value = _services.value.map { s ->
            s.copy(characteristics = s.characteristics.map { c ->
                if (c.uuid == uuid) c.copy(isNotifying = enabled) else c
            })
        }
    }

    fun readCharacteristic(serviceUuid: UUID, charUuid: UUID) {
        val g = gatt ?: return
        val c = g.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return
        g.readCharacteristic(c)
    }

    fun setNotification(serviceUuid: UUID, charUuid: UUID, enable: Boolean) {
        val g = gatt ?: return
        val c = g.getService(serviceUuid)?.getCharacteristic(charUuid) ?: return
        g.setCharacteristicNotification(c, enable)
        val d = c.getDescriptor(CCCD_UUID) ?: return
        val v = if (enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) g.writeDescriptor(d, v)
        else { d.value = v; g.writeDescriptor(d) }
    }

    fun release() {
        stopScan()
        gatt?.close()
        gatt = null
    }
}
