package com.acwo.dwotsxcontroller

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import java.util.UUID

data class ScannedDevice(
    val device: BluetoothDevice,
    val name: String,
    val address: String,
    val rssi: Int,
    val isDwotsX: Boolean
)

data class GattServiceInfo(
    val uuid: UUID,
    val instanceId: Int,
    val type: Int,
    val characteristics: List<GattCharacteristicInfo>
) {
    val uuidString: String get() = uuid.toString().uppercase()
    val typeString: String get() = if (type == BluetoothGattService.SERVICE_TYPE_PRIMARY) "PRIMARY" else "SECONDARY"
}

data class GattCharacteristicInfo(
    val uuid: UUID,
    val instanceId: Int,
    val properties: Int,
    val permissions: Int,
    val serviceUuid: UUID,
    var lastValue: ByteArray? = null,
    var isNotifying: Boolean = false
) {
    val uuidString: String get() = uuid.toString().uppercase()

    val canRead: Boolean get() = properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
    val canWrite: Boolean get() = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
    val canWriteNoResponse: Boolean get() = properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    val canNotify: Boolean get() = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
    val canIndicate: Boolean get() = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

    val propertiesText: String
        get() {
            val flags = mutableListOf<String>()
            if (canRead) flags.add("READ")
            if (canWrite) flags.add("WRITE")
            if (canWriteNoResponse) flags.add("WRITE_NR")
            if (canNotify) flags.add("NOTIFY")
            if (canIndicate) flags.add("INDICATE")
            return if (flags.isEmpty()) "NONE" else flags.joinToString(" | ")
        }

    fun valueAsHex(): String {
        val bytes = lastValue ?: return "—"
        return bytes.joinToString(" ") { "%02X".format(it) }
    }

    fun valueAsUtf8(): String {
        val bytes = lastValue ?: return "—"
        return try {
            String(bytes, Charsets.UTF_8).filter { it.isLetterOrDigit() || it.isWhitespace() || it in ".,-_/:" }
                .ifBlank { "—" }
        } catch (e: Exception) {
            "—"
        }
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCOVERING,
    READY,
    DISCONNECTING
}
