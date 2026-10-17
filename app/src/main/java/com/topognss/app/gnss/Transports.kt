package com.topognss.app.gnss

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * Un transporte entrega líneas NMEA. [run] BLOQUEA leyendo hasta que la conexión se corta
 * (lanza IOException) o hasta que otro hilo llama a [close].
 * Siempre se ejecuta en Dispatchers.IO desde [GnssManager].
 */
interface GnssTransport {
    val label: String
    @Throws(IOException::class)
    fun run(onConnected: () -> Unit, onLine: (String) -> Unit)
    fun close()
}

/**
 * Lector de líneas sobre bytes crudos. No usa BufferedReader para:
 *  - tolerar bytes no ASCII / binarios intercalados (RTCM, UBX) sin romper el flujo,
 *  - acotar el largo de línea (una trama NMEA válida tiene ≤ 82 caracteres; damos margen).
 */
internal class NmeaLineSplitter(private val onLine: (String) -> Unit) {
    private val sb = StringBuilder(128)

    fun feed(buf: ByteArray, len: Int) {
        for (i in 0 until len) {
            val b = buf[i].toInt() and 0xFF
            when {
                b == '\n'.code || b == '\r'.code -> flush()
                b == '$'.code -> { flush(); sb.append('$') } // inicio de trama: descarta resto previo
                b in 0x20..0x7E -> if (sb.length < 300) sb.append(b.toChar()) else sb.setLength(0)
                else -> sb.setLength(0) // byte binario: la línea en curso está corrupta
            }
        }
    }

    private fun flush() {
        if (sb.length > 6 && sb[0] == '$') onLine(sb.toString())
        sb.setLength(0)
    }
}

internal fun pump(input: InputStream, onLine: (String) -> Unit) {
    val splitter = NmeaLineSplitter(onLine)
    val buf = ByteArray(4096)
    while (true) {
        val n = input.read(buf)
        if (n < 0) throw IOException("El receptor cerró la conexión")
        if (n > 0) splitter.feed(buf, n)
    }
}

// ---------------------------------------------------------------------------------------------
// Bluetooth Classic SPP (RFCOMM)
// ---------------------------------------------------------------------------------------------

/**
 * Conexión Bluetooth SPP al Reach. En Emlid Flow / ReachView: Settings → Position output →
 * Output 1 = Bluetooth, formato NMEA. El teléfono debe estar emparejado previamente con el RS2.
 */
class BluetoothSppTransport(
    private val adapter: BluetoothAdapter,
    private val address: String,
    private val name: String,
) : GnssTransport {

    override val label = "Bluetooth · $name"

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var closed = false

    @SuppressLint("MissingPermission") // BLUETOOTH_CONNECT se verifica en la UI antes de conectar
    override fun run(onConnected: () -> Unit, onLine: (String) -> Unit) {
        val device = adapter.getRemoteDevice(address)
        // cancelDiscovery() acelera mucho el connect(), pero en API 31+ exige BLUETOOTH_SCAN.
        runCatching { adapter.cancelDiscovery() }

        // 1) Socket seguro estándar SPP, 2) inseguro, 3) canal RFCOMM 1 por reflexión
        //    (workaround clásico para pilas Bluetooth problemáticas de algunos fabricantes).
        val attempts: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var lastError: Exception? = null
        for (create in attempts) {
            if (closed) throw IOException("Cancelado")
            val s = try { create() } catch (e: Exception) { lastError = e; continue }
            socket = s
            try {
                s.connect() // bloqueante, ~1-12 s
                lastError = null
                break
            } catch (e: IOException) {
                lastError = e
                runCatching { s.close() }
                socket = null
            }
        }
        val s = socket ?: throw IOException("No se pudo abrir el socket RFCOMM: ${lastError?.message}", lastError)
        onConnected()
        pump(s.inputStream, onLine)
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() } // desbloquea el read() del hilo lector
    }

    companion object {
        /** UUID estándar del Serial Port Profile. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}

// ---------------------------------------------------------------------------------------------
// Wi-Fi
// ---------------------------------------------------------------------------------------------

/**
 * Busca la red Wi-Fi activa. CRÍTICO: cuando el teléfono está conectado al hotspot del RS2
 * (192.168.42.1, sin Internet) Android suele enrutar el tráfico por datos móviles.
 * Atando el socket a la red Wi-Fi garantizamos que el tráfico vaya al receptor.
 */
@Suppress("DEPRECATION")
fun findWifiNetwork(cm: ConnectivityManager?): Network? = cm?.allNetworks?.firstOrNull { n ->
    cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
}

/** El RS2 actúa como servidor TCP (Position output → TCP server, puerto p. ej. 9001). */
class TcpClientTransport(
    private val host: String,
    private val port: Int,
    private val cm: ConnectivityManager?,
) : GnssTransport {
    override val label = "TCP cliente · $host:$port"
    @Volatile private var socket: Socket? = null

    override fun run(onConnected: () -> Unit, onLine: (String) -> Unit) {
        val s = Socket()
        socket = s
        findWifiNetwork(cm)?.let { net -> runCatching { net.bindSocket(s) } }
        s.tcpNoDelay = true
        s.keepAlive = true
        s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        // Si en 5 s no llega ningún byte, read() lanza SocketTimeoutException → reconexión.
        s.soTimeout = READ_TIMEOUT_MS
        onConnected()
        pump(s.getInputStream(), onLine)
    }

    override fun close() { runCatching { socket?.close() } }
}

/** El teléfono escucha y el RS2 se conecta como cliente TCP (Position output → TCP client). */
class TcpServerTransport(private val port: Int) : GnssTransport {
    override val label = "TCP servidor · :$port"
    @Volatile private var server: ServerSocket? = null
    @Volatile private var client: Socket? = null

    override fun run(onConnected: () -> Unit, onLine: (String) -> Unit) {
        val ss = ServerSocket()
        server = ss
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        val c = ss.accept() // bloquea hasta que el receptor se conecte; close() lo destraba
        client = c
        c.soTimeout = READ_TIMEOUT_MS
        onConnected()
        try {
            pump(c.getInputStream(), onLine)
        } finally {
            runCatching { ss.close() }
        }
    }

    override fun close() {
        runCatching { client?.close() }
        runCatching { server?.close() }
    }
}

/** Escucha datagramas UDP con NMEA (una o varias líneas por paquete). */
class UdpTransport(private val port: Int, private val cm: ConnectivityManager?) : GnssTransport {
    override val label = "UDP · :$port"
    @Volatile private var socket: DatagramSocket? = null

    override fun run(onConnected: () -> Unit, onLine: (String) -> Unit) {
        val ds = DatagramSocket(null)
        socket = ds
        ds.reuseAddress = true
        findWifiNetwork(cm)?.let { net -> runCatching { net.bindSocket(ds) } }
        ds.bind(InetSocketAddress(port))
        ds.soTimeout = READ_TIMEOUT_MS
        onConnected()
        val buf = ByteArray(8192)
        val splitter = NmeaLineSplitter(onLine)
        val newline = byteArrayOf('\n'.code.toByte())
        while (true) {
            val p = DatagramPacket(buf, buf.size)
            ds.receive(p)
            splitter.feed(p.data, p.length)
            splitter.feed(newline, 1) // cada datagrama cierra su última línea
        }
    }

    override fun close() { runCatching { socket?.close() } }
}

private const val CONNECT_TIMEOUT_MS = 6000
private const val READ_TIMEOUT_MS = 5000
