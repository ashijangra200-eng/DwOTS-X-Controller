package com.acwo.dwotsxcontroller

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.acwo.dwotsxcontroller.ui.theme.*

class MainActivity : ComponentActivity() {
    private lateinit var bleManager: BleManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = BleManager(applicationContext)
        setContent {
            DwOTSXControllerTheme {
                Surface(Modifier.fillMaxSize(), color = BgDark) {
                    AppScreen(bleManager)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.release()
    }
}

@Composable
fun AppScreen(ble: BleManager) {
    val context = LocalContext.current
    val connectionState by ble.connectionState.collectAsState()
    val devices by ble.scannedDevices.collectAsState()
    val services by ble.services.collectAsState()
    val isScanning by ble.isScanning.collectAsState()
    val logs by ble.logs.collectAsState()
    val connectedName by ble.connectedDeviceName.collectAsState()

    var hasPermissions by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }

    val perms = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { hasPermissions = it.values.all { g -> g } }

    LaunchedEffect(Unit) {
        hasPermissions = perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            Modifier.fillMaxWidth().background(BgCard).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("DwOTS X", color = Cyan, fontWeight = FontWeight.Black, fontSize = 22.sp)
                Text("CONTROLLER", color = TextSecondary, fontSize = 11.sp, letterSpacing = 2.sp)
            }
            if (connectionState == ConnectionState.READY || connectionState == ConnectionState.CONNECTED) {
                IconButton(onClick = { ble.disconnect() }) {
                    Icon(Icons.Default.LinkOff, null, tint = Error)
                }
            }
        }

        if (connectedName != null && connectionState != ConnectionState.DISCONNECTED) {
            Text(
                "● $connectedName",
                color = Success,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                fontSize = 13.sp
            )
        }

        if (!hasPermissions) {
            Button(
                onClick = { launcher.launch(perms) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan)
            ) {
                Text("GRANT PERMISSIONS", color = BgDark, fontWeight = FontWeight.Bold)
            }
        } else {
            TabRow(selectedTabIndex = tab, containerColor = BgCard, contentColor = Cyan) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("DEVICES") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("GATT") },
                    enabled = connectionState == ConnectionState.READY)
            }

            when (tab) {
                0 -> {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Button(
                            onClick = {
                                if (isScanning) ble.stopScan() else {
                                    val mgr = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as BluetoothManager
                                    if (mgr.adapter?.isEnabled == true) ble.startScan()
                                    else context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isScanning) Error else Cyan,
                                contentColor = BgDark
                            )
                        ) {
                            Text(if (isScanning) "STOP SCAN" else "SCAN FOR DwOTS X", fontWeight = FontWeight.Bold)
                        }

                        Spacer(Modifier.height(12.dp))

                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(devices) { d ->
                                Card(
                                    Modifier.fillMaxWidth().border(1.dp, if (d.isDwotsX) Cyan else TextSecondary.copy(0.3f), RoundedCornerShape(12.dp)),
                                    colors = CardDefaults.cardColors(BgCard),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(d.name, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                                            Text(d.address, color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                            Text("${d.rssi} dBm", color = TextSecondary, fontSize = 11.sp)
                                        }
                                        if (d.isDwotsX) {
                                            Button(
                                                onClick = { ble.connect(d.device) },
                                                colors = ButtonDefaults.buttonColors(Magenta),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Text("CONNECT", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(services) { s ->
                            Card(
                                Modifier.fillMaxWidth().border(1.dp, Purple.copy(0.4f), RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(BgCard),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("SERVICE", color = Purple, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text(s.uuidString, color = TextPrimary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    s.characteristics.forEach { c ->
                                        Spacer(Modifier.height(8.dp))
                                        Text(c.uuidString, color = Cyan, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        Text(c.propertiesText, color = TextSecondary, fontSize = 11.sp)
                                        if (c.lastValue != null) {
                                            Text(c.valueAsHex(), color = Success, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (c.canRead) {
                                                TextButton(onClick = { ble.readCharacteristic(c.serviceUuid, c.uuid) }) {
                                                    Text("READ", color = Cyan, fontSize = 12.sp)
                                                }
                                            }
                                            if (c.canNotify || c.canIndicate) {
                                                TextButton(onClick = { ble.setNotification(c.serviceUuid, c.uuid, !c.isNotifying) }) {
                                                    Text(if (c.isNotifying) "UNSUB" else "NOTIFY", color = Magenta, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Status bar
        val (statusText, statusColor) = when {
            isScanning -> "SCANNING" to Cyan
            connectionState == ConnectionState.CONNECTING -> "CONNECTING…" to Warning
            connectionState == ConnectionState.DISCOVERING -> "DISCOVERING…" to Warning
            connectionState == ConnectionState.READY -> "READY" to Success
            else -> "DISCONNECTED" to TextSecondary
        }
        Text(
            statusText,
            color = statusColor,
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth().background(BgCard).padding(12.dp)
        )
    }
}
