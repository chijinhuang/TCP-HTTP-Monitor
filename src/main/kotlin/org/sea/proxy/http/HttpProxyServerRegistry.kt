package org.sea.proxy.http

import java.util.concurrent.ConcurrentHashMap

/**
 * Registry for [HttpProxyServer] instances, keyed by local port.
 * This allows servlets to look up their owning server instance.
 */
internal object HttpProxyServerRegistry {
    private val servers = ConcurrentHashMap<Int, HttpProxyServer>()

    fun register(port: Int, server: HttpProxyServer) {
        servers[port] = server
    }

    fun unregister(port: Int) {
        servers.remove(port)
    }

    fun getServer(port: Int): HttpProxyServer? {
        return servers[port]
    }
}
