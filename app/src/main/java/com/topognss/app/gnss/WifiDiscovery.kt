package com.topognss.app.gnss

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/** Estado de la red Wi-Fi del teléfono. */
data class WifiStatus(
    val network: Network,
    val phoneIp: String?,
    val prefixLength: Int,
    val gateway: String?,
    /** false en el hotspot del receptor (no tiene Internet); es normal. */
    val hasInternet: Boolean,
) {
    val isReachHotspot: Boolean get() = looksLikeReachHotspot(gateway)
}

fun wifiStatusOf(cm: ConnectivityManager, net: Network): WifiStatus? {
    val caps = cm.getNetworkCapabilities(net) ?: return null
    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
    val lp: LinkProperties = cm.getLinkProperties(net) ?: return null
    val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address }
    val gw = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
    return WifiStatus(
        network = net,
        phoneIp = la?.address?.hostAddress,
        prefixLength = la?.prefixLength ?: 24,
        gateway = gw,
        hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
    )
}

/** Emite el estado de la red Wi-Fi cada vez que cambia (conexión, IP, desconexión). Null = sin Wi-Fi. */
fun wifiStatusFlow(cm: ConnectivityManager): Flow<WifiStatus?> = callbackFlow {
    val nets = LinkedHashMap<Network, WifiStatus?>()
    fun emit() { trySend(nets.values.filterNotNull().firstOrNull()) }
    val cb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { nets[network] = wifiStatusOf(cm, network); emit() }
        override fun onLost(network: Network) { nets.remove(network); emit() }
        override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
            nets[network] = wifiStatusOf(cm, network); emit()
        }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            nets[network] = wifiStatusOf(cm, network); emit()
        }
    }
    val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
    trySend(findWifiNetwork(cm)?.let { wifiStatusOf(cm, it) })
    cm.registerNetworkCallback(req, cb)
    awaitClose { runCatching { cm.unregisterNetworkCallback(cb) } }
}.distinctUntilChanged()

/** Un receptor encontrado en la red. */
data class FoundHost(val ip: String, val port: Int, val nmea: Boolean)

/**
 * Busca equipos con el [port] TCP abierto en la red Wi-Fi (atando los sockets a esa red para que
 * Android no los mande por datos móviles). Los que responden se leen ~1,5 s para ver si emiten NMEA.
 */
@OptIn(ExperimentalCoroutinesApi::class)
suspend fun scanWifiForReceivers(
    status: WifiStatus,
    port: Int,
    onProgress: (done: Int, total: Int) -> Unit,
    onFound: (FoundHost) -> Unit,
) {
    val hosts = scanCandidates(status.phoneIp ?: return, status.prefixLength, status.gateway)
    val done = AtomicInteger(0)
    val io = Dispatchers.IO.limitedParallelism(48)
    coroutineScope {
        hosts.map { ip ->
            async(io) {
                probe(status.network, ip, port)?.let(onFound)
                onProgress(done.incrementAndGet(), hosts.size)
            }
        }.awaitAll()
    }
}

private suspend fun probe(net: Network, ip: String, port: Int): FoundHost? = withContext(Dispatchers.IO) {
    val s = Socket()
    try {
        runCatching { net.bindSocket(s) }
        s.connect(InetSocketAddress(ip, port), 500)
        s.soTimeout = 1500
        val buf = ByteArray(2048)
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + 1500
        runCatching {
            while (System.currentTimeMillis() < deadline && sb.length < 4000) {
                val n = s.getInputStream().read(buf)
                if (n <= 0) break
                for (i in 0 until n) sb.append((buf[i].toInt() and 0xFF).toChar())
                if (looksLikeNmea(sb.toString())) break
            }
        }
        FoundHost(ip, port, looksLikeNmea(sb.toString()))
    } catch (e: Exception) {
        null
    } finally {
        runCatching { s.close() }
    }
}
