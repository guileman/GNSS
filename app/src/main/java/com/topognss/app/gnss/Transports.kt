package com.topognss.app.gnss

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    var sentences = 0
    var bytes = 0L
    val start = System.currentTimeMillis()
    val preview = StringBuilder()
    val splitter = NmeaLineSplitter { sentences++; onLine(it) }
    val buf = ByteArray(4096)
    while (true) {
        val n = input.read(buf)
        if (n < 0) throw IOException("El receptor cerró la conexión")
        if (n > 0) {
            bytes += n
            if (preview.length < 40) for (i in 0 until n) {
                val c = buf[i].toInt() and 0xFF
                if (preview.length < 40 && c in 0x20..0x7E) preview.append(c.toChar())
            }
            splitter.feed(buf, n)
            // Llegan bytes pero ninguna trama NMEA: la salida del receptor está en otro formato (ERB, LLH…)
            if (sentences == 0 && System.currentTimeMillis() - start > 4000) {
                throw IOException(
                    "El receptor envía datos que no son NMEA" +
                        (if (preview.isNotBlank()) " («${preview.toString().trim()}»)" else "") +
                        ". En Emlid Flow poné el formato de la salida de posición en NMEA."
                )
            }
        }
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
        // GnssManager reutiliza este objeto en cada reintento y llama a close() tras cada caída:
        // si no se reinicia la bandera, todos los reintentos fallan al instante con "Cancelado".
        closed = false
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
    networkName: String? = null,
) : GnssTransport {
    override val label = if (networkName != null) "Wi-Fi · $networkName" else "TCP cliente · $host:$port"
    @Volatile private var socket: Socket? = null
    @Volatile private var netCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Pide la red Wi-Fi a Android. Mientras la app tenga un pedido activo, el sistema NO la abandona
     * por "no tiene Internet" para pasarse a datos móviles (el hotspot del RS2 nunca tiene Internet).
     */
    private fun acquireWifi(): Network? {
        val c = cm ?: return null
        releaseWifi()
        var found: Network? = null
        val latch = CountDownLatch(1)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { found = network; latch.countDown() }
        }
        val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        try {
            c.requestNetwork(req, cb)
            netCallback = cb
            latch.await(4, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // Sin permiso o sin soporte: se usa la red Wi-Fi activa como antes
        }
        return found ?: findWifiNetwork(c)
    }

    private fun releaseWifi() {
        val cb = netCallback ?: return
        netCallback = null
        runCatching { cm?.unregisterNetworkCallback(cb) }
    }

    override fun run(onConnected: () -> Unit, onLine: (String) -> Unit) {
        val net = acquireWifi()
        val s = Socket()
        socket = s
        net?.let { runCatching { it.bindSocket(s) } }
        s.tcpNoDelay = true
        s.keepAlive = true
        try {
            s.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        } catch (e: SocketTimeoutException) {
            throw IOException("No responde el receptor en $host:$port. ¿Sigue el teléfono conectado a su red Wi-Fi?", e)
        } catch (e: java.net.ConnectException) {
            throw IOException(
                "El receptor rechazó la conexión en el puerto $port. En Emlid Flow, Configuración → Salida de posición: " +
                    "TCP, rol servidor, puerto $port, formato NMEA.", e
            )
        }
        // Si en 5 s no llega ningún byte, read() lanza SocketTimeoutException → reconexión.
        s.soTimeout = READ_TIMEOUT_MS
        onConnected()
        try {
            pump(s.getInputStream(), onLine)
        } catch (e: SocketTimeoutException) {
            throw IOException(
                "Conectó, pero el receptor no envía datos. En Emlid Flow, Configuración → Salida de posición: " +
                    "TCP servidor, puerto $port, formato NMEA, y activá la salida.", e
            )
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        releaseWifi()
    }
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
