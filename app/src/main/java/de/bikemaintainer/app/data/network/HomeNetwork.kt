package de.bikemaintainer.app.data.network

import java.math.BigInteger
import java.net.Inet6Address
import java.net.InetAddress

/** Address ranges in which cleartext HTTP is acceptable: loopback, private, link-local, CGNAT/VPN (Tailscale). */
object HomeNetwork {
    private class Cidr(text: String) {
        private val network: BigInteger
        private val bits: Int
        private val total: Int

        init {
            val (addr, prefix) = text.split('/')
            val bytes = InetAddress.getByName(addr).address
            total = bytes.size * 8
            bits = prefix.toInt()
            network = BigInteger(1, bytes).shiftRight(total - bits)
        }

        fun contains(bytes: ByteArray): Boolean =
            bytes.size * 8 == total && BigInteger(1, bytes).shiftRight(total - bits) == network
    }

    private val ranges = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16", "100.64.0.0/10",
        "fc00::/7", "fe80::/10", "::1/128",
    ).map(::Cidr)

    fun contains(address: InetAddress): Boolean {
        // Java normally unwraps ::ffff:a.b.c.d to Inet4Address itself; the explicit check keeps this independent of it.
        val bytes = address.address
        val effective = if (address is Inet6Address && isV4Mapped(bytes)) bytes.copyOfRange(12, 16) else bytes
        return ranges.any { it.contains(effective) }
    }

    private fun isV4Mapped(bytes: ByteArray): Boolean =
        (0 until 10).all { bytes[it] == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()

}
