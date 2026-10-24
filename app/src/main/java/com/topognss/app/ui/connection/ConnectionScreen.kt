package com.topognss.app.ui.connection

import android.Manifest
import com.topognss.app.ui.components.StatusChip
import com.topognss.app.ui.components.EmptyState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.Icons
import android.net.wifi.WifiManager
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

data class PairedDevice(val name: String, val address: String, val looksLikeReach: Boolean, val bonded: Boolean = true)

/** Receptor visible por Wi-Fi: el teléfono está en la red del RS2 (puerta de enlace 192.168.42.1). */
data class WifiReceiver(val ssid: String, val ip: String)

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
    private val _wifi = MutableStateFlow<WifiReceiver?>(null)
    /** Receptor alcanzable por Wi-Fi, o null si el teléfono no está en la red de un RS2. */
    val wifi: StateFlow<WifiReceiver?> = _wifi

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
        name.contains("reach", true) || name.contains("rs2", true) || name.contains("rs3", true) ||
            name.contains("rover", true) || name.contains("base", true) || name.contains("emlid", true)

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
        if (!hasScanPermission()) return
        ensureReceiver()
        message = null
        _found.value = emptyList()
        runCatching { adapter.cancelDiscovery() }
        _scanning.value = runCatching { adapter.startDiscovery() }.getOrDefault(false)
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

    /** Detecta si el teléfono está en la red Wi-Fi de un RS2 (IP 192.168.42.x, sin Internet). */
    @Suppress("DEPRECATION")
    fun refreshWifi() {
        _wifi.value = runCatching {
            val c = cm ?: return@runCatching null
            val net = findWifiNetwork(c) ?: return@runCatching null
            val ip = c.getLinkProperties(net)?.linkAddresses?.map { it.address }
                ?.firstOrNull { it is Inet4Address }?.hostAddress ?: return@runCatching null
            if (!ip.startsWith("192.168.42.")) return@runCatching null
            val wm = topo.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ssid = wm?.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && !it.contains("unknown ssid") }
            WifiReceiver(ssid ?: "Receptor Wi-Fi", ip)
        }.getOrNull()
    }

    /** "Actualizar": vuelve a mirar Wi-Fi, emparejados y, si hay permiso, busca por Bluetooth. */
    fun refreshAll() {
        refreshWifi()
        if (hasBtPermission()) {
            refreshPaired()
            if (_btEnabled.value && hasScanPermission()) startScan()
        }
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
        prefs.lastMode = "BLUETOOTH"
        prefs.lastBtAddress = address
        topo.gnss.connect(BluetoothSppTransport(adapter, address, name))
        GnssService.start(topo)
    }

    fun connectWifi(r: WifiReceiver) {
        stopScan()
        message = null
        prefs.lastMode = "WIFI"
        topo.gnss.connect(TcpClientTransport(RS2_HOST, RS2_PORT, cm, r.ssid))
        GnssService.start(topo)
    }

    fun disconnect() {
        topo.gnss.disconnect()
        GnssService.stop(topo)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(vm: ConnectionViewModel = viewModel()) {
    val context = LocalContext.current
    val s by vm.gnss.collectAsState()
    val raw by vm.raw.collectAsState()
    val paired by vm.paired.collectAsState()
    val found by vm.found.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val btEnabled by vm.btEnabled.collectAsState()
    val wifi by vm.wifi.collectAsState()
    var details by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    val connected = s.connection == ConnectionStatus.CONNECTED || s.connection == ConnectionStatus.CONNECTING ||
        s.connection == ConnectionStatus.ERROR

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshAll()
    }
    val enableBtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshAll()
    }
    LaunchedEffect(Unit) {
        if (!vm.hasScanPermission()) permLauncher.launch(vm.btPermissions())
    }
    // Al abrir la pantalla o volver de los ajustes de Android se actualiza la lista sola;
    // mientras se mira, se revisa el Wi-Fi cada 2 s (el receptor aparece al conectarse a su red).
    LifecycleResumeEffect(Unit) {
        vm.refreshAll()
        onPauseOrDispose { vm.stopScan() }
    }
    LaunchedEffect(connected) {
        while (!connected) {
            vm.refreshWifi()
            kotlinx.coroutines.delay(2000)
        }
    }

    // Nombre y tipo del receptor conectado a partir de la etiqueta del transporte ("Wi-Fi · ROVER:3D:24")
    val typeAndName = s.connectionLabel.split(" · ", limit = 2)
    val recvType = typeAndName.firstOrNull().orEmpty().ifBlank { "Receptor" }
    val recvName = typeAndName.getOrNull(1) ?: recvType
    val isWifiConn = recvType.startsWith("Wi-Fi") || recvType.startsWith("TCP")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Receptores", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

        if (connected) {
            // ---------------- Conectado ----------------
            SectionTitle("Conectado", Modifier.padding(horizontal = 16.dp))
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(recvName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text(
                                when (s.connection) {
                                    ConnectionStatus.CONNECTING -> "$recvType · conectando…"
                                    ConnectionStatus.ERROR -> "$recvType · error, reintentando"
                                    else -> recvType
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (s.connection == ConnectionStatus.CONNECTED) {
                            StatusChip(
                                "${s.satellites ?: "—"} · ${s.solution.label}",
                                container = com.topognss.app.ui.theme.solutionColor(s.solution),
                            )
                        }
                    }
                    s.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (s.connection != ConnectionStatus.CONNECTED && isWifiConn) {
                        Text(
                            "Si no conecta: en Emlid Flow → Configuración → Salida de posición, elegí TCP (servidor), puerto 9001, formato NMEA.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Button(
                onClick = { vm.disconnect() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
            ) { Text("Desconectar") }

            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    SectionTitle("Estado")
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
            if (details) {
                SectionTitle("Consola NMEA", Modifier.padding(horizontal = 16.dp))
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
                    if (raw.isEmpty()) Text("(sin datos)", fontSize = 11.sp)
                    raw.takeLast(25).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1) }
                }
            }
        } else {
            // ---------------- Disponible ----------------
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Disponible", Modifier.weight(1f))
                if (scanning) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(18.dp), strokeWidth = 2.dp)
                TextButton(onClick = { vm.refreshAll() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                    Text(" Actualizar", maxLines = 1, softWrap = false)
                }
            }

            if (!btEnabled) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("El Bluetooth del teléfono está apagado.", Modifier.weight(1f))
                        TextButton(onClick = {
                            runCatching { enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                        }) { Text("Activar", maxLines = 1) }
                    }
                }
            }

            // Un toque conecta: Wi-Fi, Bluetooth ya emparejado, o empareja + conecta
            wifi?.let { w ->
                DeviceRow(w.ssid, "Wi-Fi", trailing = "Conectar", highlight = true, enabled = true) { vm.connectWifi(w) }
            }
            paired.forEach { d ->
                DeviceRow(d.name, "Bluetooth · emparejado", trailing = "Conectar", highlight = d.looksLikeReach, enabled = true) {
                    vm.connectBluetooth(d.address)
                }
            }
            found.forEach { d ->
                DeviceRow(
                    d.name, "Bluetooth · sin emparejar",
                    trailing = if (vm.pairingAddress == d.address) "Emparejando…" else "Emparejar",
                    highlight = d.looksLikeReach, enabled = vm.pairingAddress == null,
                ) { vm.pair(d) }
            }

            if (wifi == null && paired.isEmpty() && found.isEmpty()) {
                EmptyState(
                    title = if (scanning) "Buscando receptores…" else "No se han encontrado receptores",
                    message = "Acérquese al receptor, compruebe que esté encendido y actualice la lista.",
                )
            }
            vm.message?.let {
                Text(it, Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { showHelp = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("¿No encuentra el Reach?")
            }
        }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("¿No encuentra el Reach?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Por Wi-Fi", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Encendé el receptor, conectá el teléfono a su red Wi-Fi desde los ajustes de Android " +
                            "(si Android avisa «Sin acceso a Internet», dejá la conexión igual) y volvé a la app: el receptor aparece solo.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }) {
                        Text("Abrir ajustes de Wi-Fi", maxLines = 1)
                    }
                    Text("Por Bluetooth", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Activá el Bluetooth del receptor desde Emlid Flow, tocá Actualizar y luego el receptor para emparejarlo.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }) {
                        Text("Abrir ajustes de Bluetooth", maxLines = 1)
                    }
                    Text(
                        "En ambos casos, en Emlid Flow la salida de posición debe estar en formato NMEA " +
                            "(Wi-Fi: TCP servidor, puerto 9001; Bluetooth: Output 1 = Bluetooth).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Cerrar") } },
        )
    }
}

@Composable
private fun DeviceRow(name: String, subtitle: String, trailing: String, highlight: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(trailing, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
    }
}
