package com.topognss.app.ui.connection

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.topognss.app.gnss.ConnMode
import com.topognss.app.gnss.ConnectionStatus
import com.topognss.app.gnss.GnssState
import com.topognss.app.gnss.WifiRole
import com.topognss.app.gnss.connectionHint
import com.topognss.app.ui.components.KeyValue
import com.topognss.app.ui.components.SectionTitle
import com.topognss.app.ui.components.f
import com.topognss.app.ui.theme.FixGreen
import com.topognss.app.ui.theme.FloatYellow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(vm: ConnectionViewModel = viewModel()) {
    val context = LocalContext.current
    val s by vm.gnss.collectAsState()
    val raw by vm.raw.collectAsState()

    // Permiso de conexión Bluetooth (Android 12+) → refrescar la lista de emparejados.
    val connectPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.refreshPaired()
        if (!vm.hasBtConnectPermission()) vm.message = "Sin el permiso \"Dispositivos cercanos\" no se puede usar Bluetooth."
    }
    // Permisos de búsqueda → iniciar la búsqueda.
    val scanPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (vm.hasScanPermissions()) vm.startBtScan()
        else vm.message = "Sin permiso no se pueden buscar equipos. Podés emparejar desde los ajustes de Bluetooth de Android."
    }
    val enableBtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshPaired()
    }

    LaunchedEffect(vm.mode) {
        if (vm.mode == ConnMode.BLUETOOTH) {
            if (vm.hasBtConnectPermission()) vm.refreshPaired()
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                connectPermLauncher.launch(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT))
            }
        }
    }
    // Al volver de los ajustes de Android (emparejar, activar Bluetooth o Wi-Fi) se refresca la lista.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME && vm.mode == ConnMode.BLUETOOTH) vm.refreshPaired() }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            vm.pairing.stopScan()
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ConnectionStatusCard(s, onDisconnect = { vm.disconnect() }, onRetry = { vm.connect() })

        SectionTitle("Conectar por")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ConnMode.entries.forEach { m ->
                FilterChip(selected = vm.mode == m, onClick = { vm.mode = m; vm.message = null }, label = { Text(m.label) })
            }
        }

        when (vm.mode) {
            ConnMode.BLUETOOTH -> BluetoothPanel(
                vm, s,
                requestConnectPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        connectPermLauncher.launch(arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT))
                    }
                },
                startScan = {
                    if (vm.hasScanPermissions()) vm.startBtScan() else scanPermLauncher.launch(vm.scanPermissions())
                },
                enableBluetooth = {
                    runCatching { enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }
                },
                openSettings = { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } },
                openAppSettings = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }
                },
            )
            ConnMode.WIFI -> WifiPanel(vm, s, openWifiSettings = {
                val panel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI)
                else Intent(Settings.ACTION_WIFI_SETTINGS)
                runCatching { context.startActivity(panel) }
                    .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) } }
            })
        }

        vm.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        ReceiverStateCard(s)

        SectionTitle("Consola NMEA")
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
            if (raw.isEmpty()) Text("(sin datos)", fontSize = 11.sp)
            raw.takeLast(25).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1) }
        }
    }
}

/** Estado de la conexión arriba de todo: así siempre se ve la reacción al tocar un equipo. */
@Composable
private fun ConnectionStatusCard(s: GnssState, onDisconnect: () -> Unit, onRetry: () -> Unit) {
    val (title, color) = when (s.connection) {
        ConnectionStatus.CONNECTED -> "Conectado" to FixGreen
        ConnectionStatus.CONNECTING -> "Conectando…" to FloatYellow
        ConnectionStatus.ERROR -> "Sin conexión: reintentando" to MaterialTheme.colorScheme.error
        ConnectionStatus.DISCONNECTED -> "Desconectado" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.connection == ConnectionStatus.CONNECTING) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.Bold, color = color)
                    if (s.connectionLabel.isNotEmpty()) Text(s.connectionLabel, style = MaterialTheme.typography.bodySmall)
                }
                if (s.connection != ConnectionStatus.DISCONNECTED) {
                    Button(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("Desconectar") }
                }
            }
            if (s.connection == ConnectionStatus.CONNECTED && s.sentences == 0L) {
                Text(
                    "Conectado, pero todavía no llegan datos. Verificá que la salida del receptor esté en formato NMEA.",
                    style = MaterialTheme.typography.bodySmall, color = FloatYellow,
                )
            }
            s.errorMessage?.let { err ->
                Text("Error: $err", style = MaterialTheme.typography.bodySmall)
                connectionHint(s.connectionLabel, err)?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium) }
                TextButton(onClick = onRetry) { Text("Reintentar ahora") }
            }
        }
    }
}

@Composable
private fun BluetoothPanel(
    vm: ConnectionViewModel,
    s: GnssState,
    requestConnectPermission: () -> Unit,
    startScan: () -> Unit,
    enableBluetooth: () -> Unit,
    openSettings: () -> Unit,
    openAppSettings: () -> Unit,
) {
    val paired by vm.paired.collectAsState()
    val disc by vm.pairing.state.collectAsState()

    Text(
        "En Emlid Flow → Settings → Position output configurá una salida en Bluetooth con formato NMEA. " +
            "Después tocá el receptor en la lista para conectar.",
        style = MaterialTheme.typography.bodySmall,
    )

    if (!vm.hasBluetooth) {
        Text("Este teléfono no tiene Bluetooth. Usá la conexión Wi-Fi.", color = MaterialTheme.colorScheme.error)
        return
    }
    if (!vm.hasBtConnectPermission()) {
        Text("Falta el permiso \"Dispositivos cercanos\" para usar Bluetooth.", color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = requestConnectPermission) { Text("Dar permiso") }
            TextButton(onClick = openAppSettings) { Text("Abrir permisos de la app") }
        }
        return
    }
    if (!vm.btEnabled) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("El Bluetooth está apagado.", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
            Button(onClick = enableBluetooth) { Text("Activar") }
        }
    }

    SectionTitle("Equipos emparejados")
    if (paired.isEmpty()) {
        Text(
            "No hay equipos emparejados. Tocá \"Buscar dispositivos\" con el receptor encendido y cerca.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
        )
    }
    paired.forEach { d ->
        val isTarget = vm.selectedAddress == d.address && s.connectionLabel.startsWith("Bluetooth")
        val status = when {
            !isTarget -> null
            s.connection == ConnectionStatus.CONNECTED -> "Conectado ✓" to FixGreen
            s.connection == ConnectionStatus.CONNECTING -> "Conectando…" to FloatYellow
            s.connection == ConnectionStatus.ERROR -> "Error, reintentando" to MaterialTheme.colorScheme.error
            else -> null
        }
        DeviceRow(
            name = d.name, address = d.address, highlight = d.looksLikeReceiver,
            status = status, action = if (status == null) "Conectar" else null,
            onClick = { vm.connectBluetooth(d.address) },
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (disc.scanning) {
            OutlinedButton(onClick = { vm.pairing.stopScan() }) { Text("Detener búsqueda") }
        } else {
            OutlinedButton(onClick = startScan) { Text("Buscar dispositivos") }
        }
        TextButton(onClick = openSettings) { Text("Ajustes de Bluetooth") }
    }
    if (disc.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())

    if (disc.found.isNotEmpty() || disc.scanning) {
        SectionTitle("Disponibles para emparejar")
        if (disc.found.isEmpty()) {
            Text("Buscando…", style = MaterialTheme.typography.bodySmall)
        }
        disc.found.forEach { d ->
            val bonding = disc.bonding == d.address
            DeviceRow(
                name = d.name, address = d.address, highlight = d.looksLikeReceiver,
                status = if (bonding) "Emparejando…" to FloatYellow else null,
                action = if (bonding) null else "Emparejar",
                onClick = { if (!bonding) vm.pair(d.address) },
            )
        }
        if (!disc.scanning && disc.found.isEmpty()) {
            Text(
                "No apareció ningún equipo. Verificá que el Bluetooth del receptor esté activado y visible " +
                    "(en Emlid Flow → Settings → Bluetooth).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DeviceRow(
    name: String,
    address: String,
    highlight: Boolean,
    status: Pair<String, Color>?,
    action: String?,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal)
                Text(address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            status?.let { (t, c) -> Text(t, color = c, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
            action?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
        }
        HorizontalDivider()
    }
}

@Composable
private fun WifiPanel(vm: ConnectionViewModel, s: GnssState, openWifiSettings: () -> Unit) {
    val wifi by vm.wifi.collectAsState()
    val found by vm.foundHosts.collectAsState()

    // --- Red Wi-Fi del teléfono ---
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val w = wifi
            if (w == null) {
                Text("El teléfono no está conectado a ninguna red Wi-Fi.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Text(
                    "Conectalo al hotspot del receptor (red \"Reach…\", la contraseña por defecto de Emlid es emlidreach) " +
                        "o a la misma red Wi-Fi que el receptor.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    if (w.isReachHotspot) "Conectado al hotspot del receptor ✓" else "Conectado a una red Wi-Fi ✓",
                    fontWeight = FontWeight.Bold, color = FixGreen,
                )
                KeyValue("IP de este teléfono", w.phoneIp ?: "—")
                KeyValue("Puerta de enlace", w.gateway ?: "—")
                if (w.isReachHotspot) {
                    Text("El hotspot no tiene Internet: si Android pregunta, elegí mantener la conexión.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = openWifiSettings) { Text(if (w == null) "Elegir red Wi-Fi" else "Cambiar de red") }
        }
    }

    // --- Modo ---
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WifiRole.entries.forEach { r ->
            FilterChip(selected = vm.wifiRole == r, onClick = { vm.wifiRole = r }, label = { Text(r.label) })
        }
    }
    Text(vm.wifiRole.description, style = MaterialTheme.typography.bodySmall)

    when (vm.wifiRole) {
        WifiRole.RECEIVER_SERVER -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(vm.host, { vm.host = it }, label = { Text("IP del receptor") }, singleLine = true,
                    modifier = Modifier.weight(2f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(vm.port, { vm.port = it }, label = { Text("Puerto") }, singleLine = true,
                    modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (vm.wifiScanning) {
                    OutlinedButton(onClick = { vm.stopWifiScan() }) { Text("Detener") }
                } else {
                    OutlinedButton(onClick = { vm.scanWifi() }, enabled = wifi != null) { Text("Buscar receptor") }
                }
                TextButton(onClick = { vm.useReachHotspot() }) { Text("Hotspot (192.168.42.1)") }
            }
            if (vm.wifiScanning) {
                LinearProgressIndicator(
                    progress = { if (vm.wifiTotal > 0) vm.wifiProgress.toFloat() / vm.wifiTotal else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Buscando en la red (${vm.wifiProgress}/${vm.wifiTotal})…", style = MaterialTheme.typography.bodySmall)
            }
            if (found.isNotEmpty()) {
                SectionTitle("Encontrados en la red")
                found.forEach { h ->
                    val isTarget = s.connectionLabel.contains("${h.ip}:${h.port}")
                    val status = when {
                        isTarget && s.connection == ConnectionStatus.CONNECTED -> "Conectado ✓" to FixGreen
                        isTarget && s.connection == ConnectionStatus.CONNECTING -> "Conectando…" to FloatYellow
                        else -> null
                    }
                    DeviceRow(
                        name = h.ip + if (h.nmea) "  · emite NMEA" else "  · puerto abierto",
                        address = "Puerto ${h.port}", highlight = h.nmea,
                        status = status, action = if (status == null) "Conectar" else null,
                        onClick = { vm.connectWifiHost(h) },
                    )
                }
            }
        }
        WifiRole.PHONE_SERVER, WifiRole.UDP -> {
            KeyValue("IP de este teléfono", wifi?.phoneIp ?: "sin Wi-Fi")
            OutlinedTextField(vm.port, { vm.port = it }, label = { Text("Puerto local") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
    }

    Button(onClick = { vm.connect() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
        Text(if (vm.wifiRole == WifiRole.RECEIVER_SERVER) "Conectar" else "Escuchar")
    }
}

@Composable
private fun ReceiverStateCard(s: GnssState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            SectionTitle("Estado del receptor")
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
            if (s.hrms == null && s.connection == ConnectionStatus.CONNECTED && s.sentences > 0) {
                Text(
                    "Sin HRMS/VRMS: habilitá la sentencia GST en la salida NMEA del receptor.",
                    color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
