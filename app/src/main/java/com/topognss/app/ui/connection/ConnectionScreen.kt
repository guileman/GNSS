package com.topognss.app.ui.connection

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
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

enum class ConnMode(val label: String) {
    BLUETOOTH("Bluetooth"), TCP_CLIENT("TCP cliente"), TCP_SERVER("TCP servidor"), UDP("UDP")
}

data class PairedDevice(val name: String, val address: String, val looksLikeReach: Boolean)

class ConnectionViewModel(app: Application) : AndroidViewModel(app) {
    val gnss = topo.gnss.state
    val raw = topo.gnss.rawLines
    private val prefs = topo.prefs

    private val _paired = MutableStateFlow<List<PairedDevice>>(emptyList())
    val paired: StateFlow<List<PairedDevice>> = _paired

    var mode by mutableStateOf(runCatching { ConnMode.valueOf(prefs.lastMode) }.getOrDefault(ConnMode.BLUETOOTH))
    var host by mutableStateOf(prefs.lastHost)
    var port by mutableStateOf(prefs.lastPort.toString())
    var selectedAddress by mutableStateOf(prefs.lastBtAddress)
    var message by mutableStateOf<String?>(null)

    private val btAdapter get() = (topo.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val cm get() = topo.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun hasBtPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(topo, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun refreshPaired() {
        val adapter = btAdapter
        if (adapter == null) { message = "Este equipo no tiene Bluetooth"; return }
        if (!hasBtPermission()) { message = "Falta el permiso 'Dispositivos cercanos' (Bluetooth)"; return }
        if (!adapter.isEnabled) message = "Activá el Bluetooth"
        _paired.value = runCatching {
            adapter.bondedDevices.orEmpty().map { d ->
                val name = d.name ?: d.address
                PairedDevice(name, d.address, name.contains("reach", true) || name.contains("rs2", true) || name.contains("emlid", true))
            }.sortedWith(compareByDescending<PairedDevice> { it.looksLikeReach }.thenBy { it.name })
        }.getOrElse { message = it.message; emptyList() }
    }

    /** IP del teléfono en la red Wi-Fi (para configurar el RS2 como cliente TCP / UDP). */
    fun phoneWifiIp(): String? = runCatching {
        val c = cm ?: return null
        val net = findWifiNetwork(c) ?: return null
        c.getLinkProperties(net)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
    }.getOrNull()

    fun connect() {
        message = null
        val p = port.trim().toIntOrNull()
        val transport = when (mode) {
            ConnMode.BLUETOOTH -> {
                val adapter = btAdapter ?: run { message = "Sin Bluetooth"; return }
                if (!hasBtPermission()) { message = "Falta permiso Bluetooth"; return }
                val addr = selectedAddress ?: run { message = "Elegí un dispositivo emparejado"; return }
                val name = _paired.value.firstOrNull { it.address == addr }?.name ?: addr
                BluetoothSppTransport(adapter, addr, name)
            }
            ConnMode.TCP_CLIENT -> {
                if (host.isBlank() || p == null) { message = "IP o puerto inválido"; return }
                TcpClientTransport(host.trim(), p, cm)
            }
            ConnMode.TCP_SERVER -> TcpServerTransport(p ?: run { message = "Puerto inválido"; return })
            ConnMode.UDP -> UdpTransport(p ?: run { message = "Puerto inválido"; return }, cm)
        }
        prefs.lastMode = mode.name
        prefs.lastHost = host.trim()
        p?.let { prefs.lastPort = it }
        selectedAddress?.let { prefs.lastBtAddress = it }
        topo.gnss.connect(transport)
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
    val connected = s.connection == ConnectionStatus.CONNECTED || s.connection == ConnectionStatus.CONNECTING ||
        s.connection == ConnectionStatus.ERROR

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshPaired()
    }
    LaunchedEffect(vm.mode) {
        if (vm.mode == ConnMode.BLUETOOTH) {
            if (vm.hasBtPermission()) vm.refreshPaired()
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionTitle("Tipo de conexión")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConnMode.entries.forEach { m ->
                FilterChip(
                    selected = vm.mode == m,
                    onClick = { if (!connected) vm.mode = m },
                    label = { Text(m.label) },
                    enabled = !connected,
                )
            }
        }

        when (vm.mode) {
            ConnMode.BLUETOOTH -> {
                Text(
                    "En el RS2 (Emlid Flow → Settings → Position output) configurá Output 1 = Bluetooth, formato NMEA. " +
                        "Emparejá el teléfono con el receptor desde los ajustes de Android.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.refreshPaired() }) { Text("Actualizar lista") }
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                    }) { Text("Emparejar…") }
                }
                if (paired.isEmpty()) Text("No hay dispositivos emparejados.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                paired.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !connected) { vm.selectedAddress = d.address },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = vm.selectedAddress == d.address, onClick = { vm.selectedAddress = d.address }, enabled = !connected)
                        Column {
                            Text(d.name, fontWeight = if (d.looksLikeReach) FontWeight.Bold else FontWeight.Normal)
                            Text(d.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            ConnMode.TCP_CLIENT -> {
                Text(
                    "El RS2 actúa como servidor TCP (Position output → TCP, rol servidor). " +
                        "Con el hotspot del receptor la IP es 192.168.42.1; el puerto es el configurado en Flow.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(vm.host, { vm.host = it }, label = { Text("IP del receptor") }, singleLine = true,
                        enabled = !connected, modifier = Modifier.weight(2f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(vm.port, { vm.port = it }, label = { Text("Puerto") }, singleLine = true,
                        enabled = !connected, modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                TextButton(onClick = { vm.host = "192.168.42.1" }, enabled = !connected) { Text("Usar hotspot del RS2 (192.168.42.1)") }
            }
            ConnMode.TCP_SERVER, ConnMode.UDP -> {
                val ip = remember(vm.mode) { vm.phoneWifiIp() }
                Text(
                    if (vm.mode == ConnMode.TCP_SERVER)
                        "El teléfono escucha y el RS2 se conecta como cliente TCP. En Flow cargá la IP del teléfono y este puerto."
                    else "El teléfono escucha datagramas UDP con NMEA en este puerto.",
                    style = MaterialTheme.typography.bodySmall,
                )
                KeyValue("IP de este teléfono (Wi-Fi)", ip ?: "sin Wi-Fi")
                OutlinedTextField(vm.port, { vm.port = it }, label = { Text("Puerto local") }, singleLine = true,
                    enabled = !connected, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        }

        vm.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (!connected) {
            Button(onClick = { vm.connect() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Conectar") }
        } else {
            Button(
                onClick = { vm.disconnect() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Desconectar") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                SectionTitle("Estado del receptor")
                KeyValue("Conexión", s.connection.name + if (s.connectionLabel.isNotEmpty()) " · ${s.connectionLabel}" else "")
                s.errorMessage?.let { KeyValue("Último error", it, mono = false) }
                KeyValue("Solución", s.solution.label)
                KeyValue("Latitud", s.lat.f(9))
                KeyValue("Longitud", s.lon.f(9))
                KeyValue("Altura ortométrica (MSL)", s.altitudeMsl.f(3) + " m")
                KeyValue("Ondulación geoidal N", s.geoidSeparation.f(3) + " m")
                KeyValue("Altura elipsoidal", s.ellipsoidalHeight.f(3) + " m")
                KeyValue("Satélites", s.satellites?.toString() ?: "—")
                KeyValue("HDOP / PDOP / VDOP", "${s.hdop.f(1)} / ${s.pdop.f(1)} / ${s.vdop.f(1)}")
                KeyValue("HRMS / VRMS", "${s.hrms.f(3)} / ${s.vrms.f(3)} m")
                KeyValue("Edad corrección", s.diffAge.f(1) + " s")
                KeyValue("Velocidad / rumbo", "${s.speedMs.f(2)} m/s · ${s.courseDeg.f(1)}°")
                KeyValue("Tramas OK / erróneas", "${s.sentences} / ${s.badSentences}")
                if (s.hrms == null && s.connection == ConnectionStatus.CONNECTED) {
                    Text(
                        "Sin HRMS/VRMS: habilitá la sentencia GST en la salida NMEA del receptor.",
                        color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        SectionTitle("Consola NMEA")
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)
        ) {
            if (raw.isEmpty()) Text("(sin datos)", fontSize = 11.sp)
            raw.takeLast(25).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1) }
        }
    }
}
