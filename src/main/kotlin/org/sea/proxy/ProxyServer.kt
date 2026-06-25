package org.sea.proxy

/**
 * Interface defining the lifecycle and configuration of a TCP proxy server.
 */
interface ProxyServer {

    /**
     * The local port this proxy listens on.
     */
    val localPort: Int

    /**
     * The remote host to forward traffic to.
     */
    val remoteHost: String

    /**
     * The remote port to forward traffic to.
     */
    val remotePort: Int

    /**
     * Whether to enable TLS for incoming requests.
     */
    val enableIncomingTls: Boolean

    /**
     * Whether to enable TLS when connecting to target server.
     */
    val enableTargetTls: Boolean

    /**
     * Whether to trust target server certificate (when enableTargetTls is true).
     */
    val trustTargetCert: Boolean

    /**
     * Whether the proxy server is currently running.
     */
    val isRunning: Boolean

    /**
     * Starts the proxy server.
     * @throws IllegalStateException if the server is already running.
     */
    fun start()

    /**
     * Stops the proxy server and releases all resources.
     */
    fun stop()

    /**
     * Listener to receive proxy traffic events.
     */
    var listener: ProxyEventListener?
}

/**
 * Callback interface for proxy traffic events.
 * All events are modelled as [ProxyEvent] subtypes.
 */
interface ProxyEventListener {
    fun onEvent(event: ProxyEvent)
}
