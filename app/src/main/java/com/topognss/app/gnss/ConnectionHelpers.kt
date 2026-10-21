package com.topognss.app.gnss

/*
 * Lógica pura de la pantalla de conexión (sin Android, testeable en JVM).
 */

/** Medio de conexión con el receptor. */
enum class ConnMode(val label: String) { BLUETOOTH("Bluetooth"), WIFI("Wi-Fi") }

/** Cómo viajan los datos por Wi-Fi. */
enum class WifiRole(val label: String, val description: String) {
    RECEIVER_SERVER(
        "Receptor servidor",
        "Recomendado. En Emlid Flow: Position output → TCP, rol servidor. El teléfono se conecta a la IP del receptor.",
    ),
    PHONE_SERVER(
        "Teléfono servidor",
        "En Emlid Flow: Position output → TCP, rol cliente, con la IP de este teléfono y el puerto de abajo.",
    ),
    UDP(
        "UDP",
        "En Emlid Flow: Position output → UDP hacia la IP de este teléfono y el puerto de abajo.",
    ),
}

/**
 * Convierte el modo guardado por versiones anteriores ("TCP_CLIENT", "TCP_SERVER", "UDP")
 * al par (modo, rol) actual.
 */
fun migrateConnMode(saved: String?, savedRole: String?): Pair<ConnMode, WifiRole> {
    val role = runCatching { WifiRole.valueOf(savedRole ?: "") }.getOrNull()
    return when (saved) {
        "BLUETOOTH", null, "" -> ConnMode.BLUETOOTH to (role ?: WifiRole.RECEIVER_SERVER)
        "WIFI" -> ConnMode.WIFI to (role ?: WifiRole.RECEIVER_SERVER)
        "TCP_CLIENT" -> ConnMode.WIFI to WifiRole.RECEIVER_SERVER
        "TCP_SERVER" -> ConnMode.WIFI to WifiRole.PHONE_SERVER
        "UDP" -> ConnMode.WIFI to WifiRole.UDP
        else -> ConnMode.BLUETOOTH to (role ?: WifiRole.RECEIVER_SERVER)
    }
}

/** IPv4 "a.b.c.d" → entero sin signo en un Long; null si no es válida. */
fun ipv4ToLong(ip: String): Long? {
    val parts = ip.trim().split('.')
    if (parts.size != 4) return null
    var v = 0L
    for (p in parts) {
        val n = p.toIntOrNull() ?: return null
        if (n !in 0..255) return null
        v = (v shl 8) or n.toLong()
    }
    return v
}

fun longToIpv4(v: Long): String =
    "${(v shr 24) and 0xFF}.${(v shr 16) and 0xFF}.${(v shr 8) and 0xFF}.${v and 0xFF}"

/**
 * Direcciones a probar al buscar el receptor en la red Wi-Fi: primero la puerta de enlace
 * (con el hotspot del Reach es el propio receptor, 192.168.42.1), después el resto de la subred.
 * Redes más grandes que /24 se recortan al /24 del teléfono (máx. 254 equipos).
 */
fun scanCandidates(phoneIp: String, prefixLength: Int, gateway: String?): List<String> {
    val ip = ipv4ToLong(phoneIp) ?: return listOfNotNull(gateway)
    val prefix = prefixLength.coerceIn(24, 30)
    val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
    val network = ip and mask
    val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
    val out = LinkedHashSet<String>()
    gateway?.let { g -> ipv4ToLong(g)?.takeIf { it != ip }?.let { out += longToIpv4(it) } }
    var h = network + 1
    while (h < broadcast) {
        if (h != ip) out += longToIpv4(h)
        h++
    }
    return out.toList()
}

/** true si la red parece el hotspot propio de un Emlid Reach. */
fun looksLikeReachHotspot(gateway: String?): Boolean = gateway == REACH_HOTSPOT_IP

const val REACH_HOTSPOT_IP = "192.168.42.1"
const val DEFAULT_TCP_PORT = 9001

/** Nombres típicos de receptores, para destacarlos en la lista de Bluetooth. */
fun looksLikeReceiver(name: String?): Boolean {
    val n = name?.lowercase() ?: return false
    return listOf("reach", "rs2", "rs3", "rs+", "emlid", "rover", "base", "gnss", "rtk").any { n.contains(it) }
}

/** true si los primeros bytes leídos de un puerto parecen NMEA. */
fun looksLikeNmea(sample: String): Boolean =
    Regex("""\$(GP|GN|GL|GA|GB|GQ)(GGA|RMC|GST|GSA|GSV|VTG)""").containsMatchIn(sample)

/**
 * Traduce los errores crudos de los sockets a un mensaje con la causa probable y qué revisar.
 * Devuelve null si no hay nada útil que agregar al mensaje original.
 */
fun connectionHint(label: String, error: String?): String? {
    val e = error?.lowercase() ?: return null
    val bt = label.startsWith("Bluetooth")
    return when {
        bt && ("read failed" in e || "socket might closed" in e || "timeout" in e || "rfcomm" in e) ->
            "El receptor no aceptó la conexión Bluetooth. Revisá en Emlid Flow → Settings → Position output " +
                "que una salida esté en Bluetooth con formato NMEA, que el receptor no esté conectado a otro " +
                "teléfono y que esté a pocos metros. Si sigue fallando, olvidá el dispositivo en Android y " +
                "volvé a emparejarlo desde \"Buscar dispositivos\"."
        bt && ("bond" in e || "pair" in e || "auth" in e) ->
            "El emparejamiento no se completó. Volvé a emparejar el receptor desde \"Buscar dispositivos\"."
        bt && "permission" in e ->
            "Falta el permiso \"Dispositivos cercanos\". Dalo desde Ajustes → Apps → TopoGNSS → Permisos."
        "econnrefused" in e || "connection refused" in e ->
            "El receptor rechazó la conexión: el puerto no coincide o la salida TCP no está activada en Emlid Flow."
        "ehostunreach" in e || "no route" in e || "enetunreach" in e || "network is unreachable" in e ->
            "No hay ruta hasta el receptor: verificá que el teléfono esté en la misma red Wi-Fi (o en el hotspot del receptor)."
        "failed to connect" in e || "timed out" in e || "sockettimeout" in e || "etimedout" in e ->
            "El receptor no responde en esa IP y puerto. Probá \"Buscar receptor\" para encontrarlo en la red."
        "eaddrinuse" in e || "address already in use" in e ->
            "El puerto local está ocupado por otra app. Elegí otro puerto."
        "cerró la conexión" in e ->
            "El receptor cortó la conexión. Si se repite, revisá que la salida NMEA siga activa en Emlid Flow."
        else -> null
    }
}
