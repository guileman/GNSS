package com.topognss.app.ui.connection

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.topognss.app.gnss.BluetoothSppTransport
import com.topognss.app.gnss.ConnectionStatus
import com.topognss.app.gnss.GnssService
import com.topognss.app.gnss.TcpClientTransport
import com.topognss.app.gnss.TcpServerTransport
import com.topognss.app.gnss.UdpTransport
import com.topognss.app.gnss.findWifiNetwork
import com.topognss.app.ui.components.KeyValue
import com.topognss.app.ui.components.SectionTitle
import com.topognss.app.ui.components.f
import com.topognss.app.ui.topo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address

/** Sólo dos formas de conectar: Bluetooth o Wi-Fi del receptor (hotspot del RS2). */
enum class ConnMode(val label: String) { BLUETOOTH("Bluetooth"), WIFI("Wi-Fi") }

data class PairedDevice(val name: String, val address: String, val looksLikeReach: Boolean, val bonded: Boolean = true)

/** Dirección y puerto fijos del RS2 en su propia red Wi-Fi (Position output → TCP, rol servidor). */
private const val RS2_HOST = "192.168.42.1"
private const val RS2_PORT = 9001

class ConnectionViewModel(app: Application) : AndroidViewModel(app) {
    val gnss = topo.gnss.state
    val raw = topo.gnss.rawLines
    private val prefs = topo.prefs

    private val _paired = MutableStateFlow<List<PairedDevice>>(emptyList())
    val paired: StateFlow<List<PairedDevice>> = _paired
    private val _found = MutableStateFlow<List<PairedDevice>>(emptyList())
    /** Dispositivos cercanos sin emparejar, encontrados por el escaneo de la app. */
    val found: StateFlow<List<PairedDevice>> = _found
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _btEnabled = MutableStateFlow(true)
    /** false si el Bluetooth del teléfono está apagado (la pantalla ofrece activarlo). */
    val btEnabled: StateFlow<Boolean> = _btEnabled
    private val _wifiIp = MutableStateFlow<String?>(null)
    /** IP del teléfono en la red Wi-Fi actual, o null si no está conectado a ninguna. */
    val wifiIp: StateFlow<String?> = _wifiIp

    // Los modos viejos (TCP/UDP) se reconvierten: todo lo que no sea Bluetooth ahora es "Wi-Fi".
    var mode by mutableStateOf(
        when (prefs.lastMode) {
            "BLUETOOTH" -> ConnMode.BLUETOOTH
            "WIFI", "TCP_CLIENT", "TCP_SERVER", "UDP" -> ConnMode.WIFI
            else -> ConnMode.BLUETOOTH
        }
    )
    var selectedAddress by mutableStateOf(prefs.lastBtAddress)
    var message by mutableStateOf<String?>(null)
    var pairingAddress by mutableStateOf<String?>(null)

    private val btAdapter get() = (topo.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val cm get() = topo.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(topo, p) == PackageManager.PERMISSION_GRANTED
    fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPerm(Manifest.permission.BLUETOOTH_CONNECT)
    /** Para buscar dispositivos: BLUETOOTH_SCAN en Android 12+, ubicación en versiones anteriores. */
    fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) hasPerm(Manifest.permission.BLUETOOTH_SCAN) && hasBtPermission()
        else hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)

    fun btPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun isReach(name: String) =
        name.contains("reach", true) || name.contains("rs2", true) || name.contains("rs3", true) || name.contains("emlid", true)

    @SuppressLint("MissingPermission")
    fun refreshPaired() {
        val adapter = btAdapter
        if (adapter == null) { message = "Este equipo no tiene Bluetooth"; return }
        if (!hasBtPermission()) { message = "Falta el permiso 'Dispositivos cercanos' (Bluetooth)"; return }
        _btEnabled.value = adapter.isEnabled
        if (!adapter.isEnabled) { _paired.value = emptyList(); return }
        _paired.value = runCatching {
            adapter.bondedDevices.orEmpty().map { d ->
                val name = d.name ?: d.address
                PairedDevice(name, d.address, isReach(name))
            }.sortedWith(compareByDescending<PairedDevice> { it.looksLikeReach }.thenBy { it.name })
        }.getOrElse { message = it.message; emptyList() }
        // Lo que ya quedó emparejado deja de aparecer como "encontrado"
        val bonded = _paired.value.map { it.address }.toSet()
        _found.value = _found.value.filter { it.address !in bonded }
    }

    // ---------------- Escaneo y emparejamiento dentro de la app ----------------

    private var receiverRegistered = false
    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val d = btDevice(intent) ?: return
                    if (d.bondState == BluetoothDevice.BOND_BONDED) return
                    val name = runCatching { d.name }.getOrNull() ?: return // sin nombre = ruido
                    val dev = PairedDevice(name, d.address, isReach(name), bonded = false)
                    _found.value = (_found.value.filter { it.address != dev.address } + dev)
                        .sortedWith(compareByDescending<PairedDevice> { it.looksLikeReach }.thenBy { it.name })
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _scanning.value = false
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val d = btDevice(intent) ?: return
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    if (d.address != pairingAddress) return
                    when (state) {
                        BluetoothDevice.BOND_BONDED -> {
                            pairingAddress = null
                            message = null
                            refreshPaired()
                            selectedAddress = d.address
                            connectBluetooth(d.address) // emparejó: conecta solo
                        }
                        BluetoothDevice.BOND_NONE -> {
                            pairingAddress = null
                            message = "No se pudo emparejar. Revisá que el Bluetooth del receptor esté activo en Emlid Flow, " +
                                "que no esté conectado a otro teléfono, y volvé a intentar."
                        }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun btDevice(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun ensureReceiver() {
        if (receiverRegistered) return
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(topo, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = btAdapter ?: return
        if (!adapter.isEnabled) { _btEnabled.value = false; return }
        if (!hasScanPermission()) { message = "Falta el permiso para buscar dispositivos Bluetooth"; return }
        ensureReceiver()
        message = null
        _found.value = emptyList()
        runCatching { adapter.cancelDiscovery() }
        _scanning.value = runCatching { adapter.startDiscovery() }.getOrDefault(false)
        if (!_scanning.value) message = "No se pudo iniciar la búsqueda. Activá la ubicación del teléfono e intentá de nuevo."
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        runCatching { btAdapter?.cancelDiscovery() }
        _scanning.value = false
    }

    /** Toca un dispositivo no emparejado: lo empareja desde la app (Android pide confirmar el código). */
    @SuppressLint("MissingPermission")
    fun pair(d: PairedDevice) {
        val adapter = btAdapter ?: return
        if (!hasBtPermission()) { message = "Falta el permiso Bluetooth"; return }
        ensureReceiver()
        stopScan()
        message = null
        pairingAddress = d.address
        val ok = runCatching { adapter.getRemoteDevice(d.address).createBond() }.getOrDefault(false)
        if (!ok) { pairingAddress = null; message = "No se pudo iniciar el emparejamiento con ${d.name}." }
    }

    override fun onCleared() {
        stopScan()
        if (receiverRegistered) runCatching { topo.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    // ---------------- Wi-Fi ----------------

    fun refreshWifi() {
        _wifiIp.value = runCatching {
            val c = cm ?: return@runCatching null
            val net = findWifiNetwork(c) ?: return@runCatching null
            c.getLinkProperties(net)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
        }.getOrNull()
    }

    // ---------------- Conexión ----------------

    @SuppressLint("MissingPermission")
    fun connectBluetooth(address: String) {
        val adapter = btAdapter ?: run { message = "Sin Bluetooth"; return }
        if (!hasBtPermission()) { message = "Falta permiso Bluetooth"; return }
        stopScan()
        message = null
        val name = _paired.value.firstOrNull { it.address == address }?.name ?: address
        selectedAddress = address
        prefs.lastMode = ConnMode.BLUETOOTH.name
        prefs.lastBtAddress = address
        topo.gnss.connect(BluetoothSppTransport(adapter, address, name))
        GnssService.start(topo)
    }

    fun connectWifi() {
        message = null
        prefs.lastMode = ConnMode.WIFI.name
        topo.gnss.connect(TcpClientTransport(RS2_HOST, RS2_PORT, cm))
        GnssService.start(topo)
    }

    fun disconnect() {
        topo.gnss.disconnect()
        GnssService.stop(topo)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectionScreen(vm: ConnectionViewModel = viewModel()) {
    val context = LocalContext.current
    val s by vm.gnss.collectAsState()
    val raw by vm.raw.collectAsState()
    val paired by vm.paired.collectAsState()
    val found by vm.found.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val btEnabled by vm.btEnabled.collectAsState()
    val wifiIp by vm.wifiIp.collectAsState()
    var details by rememberSaveable { mutableStateOf(false) }
    val connected = s.connection == ConnectionStatus.CONNECTED || s.connection == ConnectionStatus.CONNECTING ||
        s.connection == ConnectionStatus.ERROR

    var wantScan by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshPaired()
        if (wantScan && vm.hasScanPermission()) vm.startScan()
        wantScan = false
    }
    val enableBtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshPaired()
    }
    LaunchedEffect(vm.mode) {
        if (vm.mode == ConnMode.BLUETOOTH && !vm.hasBtPermission()) permLauncher.launch(vm.btPermissions())
    }
    // Al volver de los ajustes de Android (Wi-Fi / Bluetooth) se actualiza todo solo
    LifecycleResumeEffect(vm.mode) {
        if (vm.mode == ConnMode.BLUETOOTH && vm.hasBtPermission()) vm.refreshPaired()
        if (vm.mode == ConnMode.WIFI) vm.refreshWifi()
        onPauseOrDispose { vm.stopScan() }
    }

    val connLabel = when (s.connection) {
        ConnectionStatus.DISCONNECTED -> "Desconectado"
        ConnectionStatus.CONNECTING -> "Conectando…"
        ConnectionStatus.CONNECTED -> "Conectado"
        ConnectionStatus.ERROR -> "Error"
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Receptor", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConnMode.entries.forEach { m ->
                FilterChip(
                    selected = vm.mode == m,
                    onClick = { if (!connected) vm.mode = m },
                    label = { Text(m.label, maxLines = 1, softWrap = false) },
                    enabled = !connected,
                )
            }
        }

        when (vm.mode) {
            ConnMode.WIFI -> {
                // Flujo único: conectarse a la red del receptor desde Android, volver y tocar Conectar.
                Text(
                    "1. Conectá el teléfono a la red Wi-Fi del receptor (ajustes de Android).\n" +
                        "2. Volvé a la app y tocá Conectar.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } },
                    enabled = !connected,
                ) { Text("Abrir ajustes de Wi-Fi", maxLines = 1, softWrap = false) }
                Text(
                    if (wifiIp != null) "Wi-Fi conectado ($wifiIp)" else "El teléfono no está conectado a ninguna red Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (wifiIp != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            }
            ConnMode.BLUETOOTH -> {
                if (!btEnabled) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("El Bluetooth está apagado.")
                            Button(onClick = {
                                runCatching {
                                    enableBtLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                }
                            }) { Text("Activar Bluetooth") }
                        }
                    }
                } else {
                    SectionTitle("Emparejados")
                    if (paired.isEmpty()) {
                        Text(
                            "Ningún dispositivo emparejado. Buscá el receptor y tocalo para emparejarlo.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Tocar un dispositivo emparejado conecta directamente
                    paired.forEach { d ->
                        DeviceRow(
                            d, selected = vm.selectedAddress == d.address && connected,
                            trailing = if (vm.selectedAddress == d.address && connected) "Conectado" else null,
                            enabled = !connected,
                        ) { vm.connectBluetooth(d.address) }
                    }

                    SectionTitle("Buscar receptor")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (scanning) vm.stopScan()
                                else if (vm.hasScanPermission()) vm.startScan()
                                else { wantScan = true; permLauncher.launch(vm.btPermissions()) }
                            },
                            enabled = !connected,
                        ) { Text(if (scanning) "Detener búsqueda" else "Buscar dispositivos", maxLines = 1, softWrap = false) }
                        if (scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    found.forEach { d ->
                        DeviceRow(
                            d, selected = false,
                            trailing = if (vm.pairingAddress == d.address) "Emparejando…" else "Emparejar",
                            enabled = !connected && vm.pairingAddress == null,
                        ) { vm.pair(d) }
                    }
                    if (!scanning && found.isEmpty()) {
                        Text(
                            "En el receptor activá el Bluetooth desde Emlid Flow y tocá Buscar dispositivos.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                    }) { Text("Abrir ajustes de Bluetooth de Android", maxLines = 1) }
                }
            }
        }

        vm.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (vm.mode == ConnMode.WIFI && !connected) {
            Button(onClick = { vm.connectWifi() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Conectar") }
        }
        if (connected) {
            Button(
                onClick = { vm.disconnect() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Desconectar") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                SectionTitle("Estado del receptor")
                KeyValue("Conexión", connLabel + if (s.connectionLabel.isNotEmpty()) " · ${s.connectionLabel}" else "", mono = false)
                s.errorMessage?.let { KeyValue("Último error", it, mono = false) }
                KeyValue("Solución", s.solution.label)
                KeyValue("Latitud", s.lat.f(9))
                KeyValue("Longitud", s.lon.f(9))
                KeyValue("Altura (MSL)", s.altitudeMsl.f(3) + " m")
                KeyValue("Satélites", s.satellites?.toString() ?: "—")
                KeyValue("HRMS / VRMS", "${s.hrms.f(3)} / ${s.vrms.f(3)} m")
                KeyValue("Edad corrección", s.diffAge.f(1) + " s")
                if (details) {
                    KeyValue("Ondulación geoidal N", s.geoidSeparation.f(3) + " m")
                    KeyValue("Altura elipsoidal", s.ellipsoidalHeight.f(3) + " m")
                    KeyValue("HDOP / PDOP / VDOP", "${s.hdop.f(1)} / ${s.pdop.f(1)} / ${s.vdop.f(1)}")
                    KeyValue("Velocidad / rumbo", "${s.speedMs.f(2)} m/s · ${s.courseDeg.f(1)}°")
                    KeyValue("Tramas OK / erróneas", "${s.sentences} / ${s.badSentences}")
                }
                if (s.hrms == null && s.connection == ConnectionStatus.CONNECTED) {
                    Text(
                        "Sin HRMS/VRMS: habilitá la sentencia GST en la salida NMEA del receptor.",
                        color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = { details = !details }) { Text(if (details) "Menos detalles" else "Más detalles") }
            }
        }

        // La consola NMEA sólo sirve para diagnosticar: queda oculta salvo que se pidan los detalles
        if (details) {
            SectionTitle("Consola NMEA")
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)
            ) {
                if (raw.isEmpty()) Text("(sin datos)", fontSize = 11.sp)
                raw.takeLast(25).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun DeviceRow(d: PairedDevice, selected: Boolean, trailing: String?, enabled: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name, fontWeight = if (d.looksLikeReach) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                Text(d.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
        }
    }
}
