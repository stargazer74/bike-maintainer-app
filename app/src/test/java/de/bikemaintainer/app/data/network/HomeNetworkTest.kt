package de.bikemaintainer.app.data.network

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNetworkTest {
    private fun home(ip: String) = HomeNetwork.contains(InetAddress.getByName(ip))

    @Test
    fun privateAndVpnRangesAreHome() {
        listOf(
            "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.178.20", "127.0.0.1", "169.254.1.1",
            "100.64.0.1", "100.127.255.255", "fd12::1", "fe80::1", "::1",
        ).forEach { assertTrue(it, home(it)) }
    }

    @Test
    fun publicAddressesAreNotHome() {
        listOf("8.8.8.8", "172.32.0.1", "100.128.0.1", "192.169.0.1", "2001:db8::1", "::ffff:8.8.8.8")
            .forEach { assertFalse(it, home(it)) }
    }

    @Test
    fun ipv4MappedPrivateAddressIsHome() {
        assertTrue(home("::ffff:192.168.1.5"))
    }
}
