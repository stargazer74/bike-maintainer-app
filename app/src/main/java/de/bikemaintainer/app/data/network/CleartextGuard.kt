package de.bikemaintainer.app.data.network

import java.io.IOException
import java.net.InetAddress
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
            val address = chain.connection()!!.route().socketAddress.address
            if (!isHomeNetwork(address)) throw CleartextNotAllowedException(request.url.host)
        }
        return chain.proceed(request)
    }
}
