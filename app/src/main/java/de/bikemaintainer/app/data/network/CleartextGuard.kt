package de.bikemaintainer.app.data.network

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import okhttp3.Interceptor
import okhttp3.Response

class CleartextNotAllowedException(host: String) :
    IOException("Cleartext HTTP to $host is only allowed within the home network")

/**
 * Refuses plain HTTP unless the address actually connected to is in the home network. The check uses the resolved
 * connection (not the host name), so a name resolving to a public address cannot smuggle cleartext traffic out.
 * Must be a network interceptor: only there the connection is known. It throws before `proceed`, so nothing is sent.
 */
class CleartextGuard(
    private val isHomeNetwork: (InetAddress) -> Boolean = HomeNetwork::contains,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.isHttps) {
            val route = chain.connection()!!.route()
            // Through a proxy, socketAddress is the proxy's address (HTTP) or unresolved (SOCKS), not the target's, so
            // the home-network check would say nothing about where the cleartext traffic ends up. Hence: no proxy.
            val address = if (route.proxy.type() == Proxy.Type.DIRECT) route.socketAddress.address else null
            if (address == null || !isHomeNetwork(address)) throw CleartextNotAllowedException(request.url.host)
        }
        return chain.proceed(request)
    }
}
