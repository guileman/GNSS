package com.topognss.app

import com.topognss.app.gnss.ConnMode
import com.topognss.app.gnss.WifiRole
import com.topognss.app.gnss.connectionHint
import com.topognss.app.gnss.ipv4ToLong
import com.topognss.app.gnss.longToIpv4
import com.topognss.app.gnss.looksLikeNmea
import com.topognss.app.gnss.looksLikeReceiver
import com.topognss.app.gnss.migrateConnMode
import com.topognss.app.gnss.scanCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionHelpersTest {

    @Test fun migratesOldModes() {
        assertEquals(ConnMode.WIFI to WifiRole.RECEIVER_SERVER, migrateConnMode("TCP_CLIENT", null))
        assertEquals(ConnMode.WIFI to WifiRole.PHONE_SERVER, migrateConnMode("TCP_SERVER", null))
        assertEquals(ConnMode.WIFI to WifiRole.UDP, migrateConnMode("UDP", null))
        assertEquals(ConnMode.BLUETOOTH, migrateConnMode("BLUETOOTH", "UDP").first)
        assertEquals(ConnMode.WIFI to WifiRole.UDP, migrateConnMode("WIFI", "UDP"))
        assertEquals(ConnMode.BLUETOOTH, migrateConnMode("basura", null).first)
    }

    @Test fun ipConversions() {
        assertEquals("192.168.42.1", longToIpv4(ipv4ToLong("192.168.42.1")!!))
        assertNull(ipv4ToLong("192.168.1"))
        assertNull(ipv4ToLong("300.1.1.1"))
    }

    @Test fun candidatesStartWithGatewayAndSkipPhone() {
        val c = scanCandidates("192.168.42.17", 24, "192.168.42.1")
        assertEquals("192.168.42.1", c.first())
        assertEquals(253, c.size) // .1..254 sin el teléfono
        assertFalse(c.contains("192.168.42.17"))
        assertFalse(c.contains("192.168.42.255"))
        assertEquals(1, c.count { it == "192.168.42.1" })
    }

    @Test fun bigNetworksAreLimitedTo24() {
        val c = scanCandidates("10.1.2.3", 16, "10.1.0.1")
        assertEquals("10.1.0.1", c.first())
        assertTrue(c.all { it.startsWith("10.1.2.") || it == "10.1.0.1" })
        assertTrue(c.size <= 255)
    }

    @Test fun detectsNmeaAndReceivers() {
        assertTrue(looksLikeNmea("xx\$GNGGA,123519,4807.038,N"))
        assertFalse(looksLikeNmea("HTTP/1.1 200 OK"))
        assertTrue(looksLikeReceiver("Rover"))
        assertTrue(looksLikeReceiver("reach-rs2-ab12"))
        assertFalse(looksLikeReceiver("JBL Flip 5"))
    }

    @Test fun hintsForCommonErrors() {
        assertNotNull(connectionHint("Bluetooth · Rover", "read failed, socket might closed or timeout, read ret: -1"))
        assertNotNull(connectionHint("TCP cliente · 192.168.42.1:9001", "failed to connect to /192.168.42.1 (port 9001) after 6000ms"))
        assertNotNull(connectionHint("TCP cliente · 192.168.0.5:9001", "failed to connect: ECONNREFUSED (Connection refused)"))
        assertNull(connectionHint("Bluetooth · Rover", null))
    }
}
