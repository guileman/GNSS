package com.topognss.app.ui.connection

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.topognss.app.gnss.BluetoothPairing
import com.topognss.app.gnss.BluetoothSppTransport
import com.topognss.app.gnss.ConnMode
import com.topognss.app.gnss.FoundHost
import com.topognss.app.gnss.GnssService
import com.topognss.app.gnss.REACH_HOTSPOT_IP
import com.topognss.app.gnss.TcpClientTransport
import com.topognss.app.gnss.TcpServerTransport
import com.topognss.app.gnss.UdpTransport
import com.topognss.app.gnss.WifiRole
import com.topognss.app.gnss.WifiStatus
import com.topognss.app.gnss.looksLikeReceiver
import com.topognss.app.gnss.migrateConnMode
import com.topognss.app.gnss.scanWifiForReceivers
import com.topognss.app.gnss.wifiStatusFlow
import com.topognss.app.ui.hold
import com.topognss.app.ui.topo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PairedDevice(val name: String, val address: String, val looksLikeReceiver: Boolean)

class ConnectionViewModel(app: Application) : AndroidViewModel(app) {
    val gnss = topo.gnss.state
    val raw = topo.gnss.rawLines
    private val prefs = topo.prefs

    private val _paired = MutableStateFlow<List<PairedDevice>>(emptyList())
    val paired: StateFlow<List<PairedDevice>> = _paired

    private val initial = migrateConnMode(prefs.lastMode, prefs.wifiRole)
    var mode by mutableStateOf(initial.first)
    var wifiRole by mutableStateOf(initial.second)
    var host by mutableStateOf(prefs.lastHost)
    var port by mutableStateOf(prefs.lastPort.toString())
    var selectedAddress by mutableStateOf(prefs.lastBtAddress)
    var message by mutableStateOf<String?>(null)
    var btEnabled by mutableStateOf(true)

    private val btAdapter get() = (topo.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val cm get() = topo.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    val hasBluetooth: Boolean get() = btAdapter != null

    // --- Emparejamiento desde la app ---
    val pairing = BluetoothPairing(app, { btAdapter }) { address, name, bonded ->
        if (bonded) {
            refreshPaired()
            message = "Emparejado con $name. Conectando…"
            connectBluetooth(address)
        } else {
            message = "No se completó el emparejamiento con $name. Si el receptor pide código, " +
                "aceptalo en la ventana de Android (en los Reach suele no hacer falta)."
        }
    }

    // --- Wi-Fi ---
    val wifi: StateFlow<WifiStatus?> = hold(cm?.let { wifiStatusFlow(it) } ?: flowOf(null), null)
    private val _foundHosts = MutableStateFlow<List<FoundHost>>(emptyList())
    val foundHosts: StateFlow<List<FoundHost>> = _foundHosts
    var wifiScanning by mutableStateOf(false)
    var wifiProgress by mutableIntStateOf(0)
    var wifiTotal by mutableIntStateOf(0)
    private var scanJob: Job? = null

    init {
        pairing.register()
    }

    override fun onCleared() {
        pairing.unregister()
        super.onCleared()
    }

    fun hasBtConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.BLUETOOTH_CONNECT)

    /** Permisos que necesita la búsqueda de equipos cercanos según la versión de Android. */
    fun scanPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasScanPermissions(): Boolean = scanPermissions().all(::granted)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(topo, p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun refreshPaired() {
        val adapter = btAdapter
        if (adapter == null) { message = "Este equipo no tiene Bluetooth"; return }
        btEnabled = adapter.isEnabled
        if (!hasBtConnectPermission()) { _paired.value = emptyList(); return }
        _paired.value = runCatching {
            adapter.bondedDevices.orEmpty().map { d ->
                val name = d.name ?: d.address
                PairedDevice(name, d.address, looksLikeReceiver(name))
            }.sortedWith(compareByDescending<PairedDevice> { it.looksLikeReceiver }.thenBy { it.name.lowercase() })
        }.getOrElse { message = it.message; emptyList() }
    }

    fun startBtScan() {
        message = null
        // En Android ≤ 11 la búsqueda no devuelve nada si la ubicación del sistema está apagada.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val lm = topo.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm != null && !LocationManagerCompat.isLocationEnabled(lm)) {
                message = "Activá la Ubicación del teléfono: en Android 11 o anterior es necesaria para buscar equipos Bluetooth."
                return
            }
        }
        pairing.startScan()?.let { message = it }
    }

    fun pair(address: String) {
        message = null
        pairing.pair(address)?.let { message = it }
    }

    /** Tocar un equipo emparejado conecta directamente (aunque haya otra conexión en curso). */
    fun connectBluetooth(address: String) {
        selectedAddress = address
        mode = ConnMode.BLUETOOTH
        connect()
    }

    /** Tocar un receptor encontrado en la red conecta como cliente TCP. */
    fun connectWifiHost(h: FoundHost) {
        host = h.ip
        port = h.port.toString()
        wifiRole = WifiRole.RECEIVER_SERVER
        mode = ConnMode.WIFI
        connect()
    }

    fun connect() {
        message = null
        val p = port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
        val transport = when (mode) {
            ConnMode.BLUETOOTH -> {
                val adapter = btAdapter ?: run { message = "Este equipo no tiene Bluetooth"; return }
                if (!hasBtConnectPermission()) { message = "Falta el permiso \"Dispositivos cercanos\" (Bluetooth)"; return }
                if (!adapter.isEnabled) { btEnabled = false; message = "Activá el Bluetooth"; return }
                val addr = selectedAddress ?: run { message = "Tocá el receptor en la lista para conectar"; return }
                pairing.stopScan() // una búsqueda activa hace fallar el connect() RFCOMM
                val name = _paired.value.firstOrNull { it.address == addr }?.name ?: addr
                BluetoothSppTransport(adapter, addr, name)
            }
            ConnMode.WIFI -> when (wifiRole) {
                WifiRole.RECEIVER_SERVER -> {
                    if (host.isBlank()) { message = "Ingresá la IP del receptor o usá \"Buscar receptor\""; return }
                    if (p == null) { message = "Puerto inválido"; return }
                    TcpClientTransport(host.trim(), p, cm)
                }
                WifiRole.PHONE_SERVER -> TcpServerTransport(p ?: run { message = "Puerto inválido"; return })
                WifiRole.UDP -> UdpTransport(p ?: run { message = "Puerto inválido"; return }, cm)
            }
        }
        prefs.lastMode = mode.name
        prefs.wifiRole = wifiRole.name
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

    fun useReachHotspot() {
        host = REACH_HOTSPOT_IP
    }

    fun scanWifi() {
        val st = wifi.value ?: run { message = "Conectá el teléfono a una red Wi-Fi o al hotspot del receptor"; return }
        val p = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: run { message = "Puerto inválido"; return }
        message = null
        scanJob?.cancel()
        _foundHosts.value = emptyList()
        wifiScanning = true
        wifiProgress = 0
        wifiTotal = 0
        scanJob = viewModelScope.launch {
            var completed = false
            try {
                scanWifiForReceivers(
                    st, p,
                    onProgress = { done, total ->
                        viewModelScope.launch { wifiProgress = done; wifiTotal = total }
                    },
                    onFound = { h ->
                        _foundHosts.update { list ->
                            (list.filter { it.ip != h.ip } + h).sortedWith(compareByDescending<FoundHost> { it.nmea }.thenBy { it.ip })
                        }
                    },
                )
                completed = true
            } finally {
                // viewModelScope corre en el hilo principal: se puede tocar el estado de Compose.
                wifiScanning = false
                if (completed && _foundHosts.value.isEmpty()) {
                    message = "No se encontró ningún equipo con el puerto $p abierto en esta red. " +
                        "Revisá en Emlid Flow que Position output esté en TCP servidor con ese puerto."
                }
            }
        }
    }

    fun stopWifiScan() {
        scanJob?.cancel()
        wifiScanning = false
    }
}
