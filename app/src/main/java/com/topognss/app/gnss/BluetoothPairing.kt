package com.topognss.app.gnss

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FoundDevice(val name: String, val address: String, val looksLikeReceiver: Boolean, val rssi: Short?)

data class BtDiscoveryState(
    val scanning: Boolean = false,
    /** Equipos cercanos NO emparejados encontrados en la búsqueda. */
    val found: List<FoundDevice> = emptyList(),
    /** Dirección del equipo que se está emparejando. */
    val bonding: String? = null,
)

/**
 * Búsqueda de equipos Bluetooth cercanos y emparejamiento desde la app (sin pasar por los
 * ajustes de Android). El código/PIN, si el receptor lo pide, lo muestra el diálogo del sistema.
 *
 * Los permisos (BLUETOOTH_SCAN en Android 12+, ubicación en ≤ 11) los pide la pantalla antes.
 */
@SuppressLint("MissingPermission")
class BluetoothPairing(
    private val context: Context,
    private val adapter: () -> BluetoothAdapter?,
    /** Se invoca cuando un equipo terminó de emparejarse (true) o falló (false). */
    private val onBondResult: (address: String, name: String, bonded: Boolean) -> Unit,
) {
    private val _state = MutableStateFlow(BtDiscoveryState())
    val state: StateFlow<BtDiscoveryState> = _state.asStateFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> {
                    device ?: return
                    if (runCatching { device.bondState }.getOrNull() == BluetoothDevice.BOND_BONDED) return
                    val name = runCatching { device.name }.getOrNull()
                        ?: intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                    val rssi = if (intent.hasExtra(BluetoothDevice.EXTRA_RSSI))
                        intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE) else null
                    upsert(FoundDevice(name ?: device.address, device.address, looksLikeReceiver(name), rssi))
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> _state.value = _state.value.copy(scanning = true)
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _state.value = _state.value.copy(scanning = false)
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    device ?: return
                    val newState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val prev = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                    val name = runCatching { device.name }.getOrNull() ?: device.address
                    when {
                        newState == BluetoothDevice.BOND_BONDED -> {
                            _state.value = _state.value.copy(
                                bonding = null,
                                found = _state.value.found.filter { it.address != device.address },
                            )
                            onBondResult(device.address, name, true)
                        }
                        newState == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING -> {
                            _state.value = _state.value.copy(bonding = null)
                            onBondResult(device.address, name, false)
                        }
                        newState == BluetoothDevice.BOND_BONDING ->
                            _state.value = _state.value.copy(bonding = device.address)
                    }
                }
            }
        }
    }

    private fun upsert(d: FoundDevice) {
        val list = _state.value.found.filter { it.address != d.address } + d
        _state.value = _state.value.copy(
            found = list.sortedWith(compareByDescending<FoundDevice> { it.looksLikeReceiver }
                .thenByDescending { it.name != it.address }
                .thenByDescending { it.rssi ?: Short.MIN_VALUE }),
        )
    }

    /** Registra el receptor de eventos (búsqueda y emparejamiento). Idempotente. */
    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun unregister() {
        stopScan()
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    /** Inicia la búsqueda (~12 s). Devuelve un mensaje de error o null si arrancó. */
    fun startScan(): String? {
        val a = adapter() ?: return "Este equipo no tiene Bluetooth"
        if (!a.isEnabled) return "Activá el Bluetooth"
        register()
        _state.value = _state.value.copy(found = emptyList())
        return try {
            if (a.isDiscovering) a.cancelDiscovery()
            if (a.startDiscovery()) {
                _state.value = _state.value.copy(scanning = true)
                null
            } else {
                "No se pudo iniciar la búsqueda Bluetooth"
            }
        } catch (e: SecurityException) {
            "Falta el permiso \"Dispositivos cercanos\" para buscar equipos"
        }
    }

    fun stopScan() {
        runCatching { adapter()?.takeIf { it.isDiscovering }?.cancelDiscovery() }
        _state.value = _state.value.copy(scanning = false)
    }

    /** Pide emparejar. Devuelve un mensaje de error o null si el sistema inició el proceso. */
    fun pair(address: String): String? {
        val a = adapter() ?: return "Este equipo no tiene Bluetooth"
        register()
        stopScan() // la búsqueda activa hace fallar o demora el emparejamiento
        return try {
            val device = a.getRemoteDevice(address)
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                onBondResult(address, device.name ?: address, true)
                null
            } else if (device.createBond()) {
                _state.value = _state.value.copy(bonding = address)
                null
            } else {
                "Android no pudo iniciar el emparejamiento. Probá de nuevo o desde los ajustes de Bluetooth."
            }
        } catch (e: SecurityException) {
            "Falta el permiso \"Dispositivos cercanos\""
        } catch (e: IllegalArgumentException) {
            "Dirección Bluetooth inválida"
        }
    }
}
