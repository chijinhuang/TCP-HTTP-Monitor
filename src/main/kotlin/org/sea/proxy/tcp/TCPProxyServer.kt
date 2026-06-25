package org.sea.proxy.tcp

import org.sea.proxy.ProxyEvent
import org.sea.proxy.ProxyEventListener
import org.sea.proxy.ProxyServer
import org.sea.proxy.ProxyType
import org.sea.proxy.util.CertificateUtil
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocket

/**
 * TCP implementation of [ProxyServer] that accepts client connections on [localPort]
 * and forwards traffic to [remoteHost]:[remotePort].
 */
class TCPProxyServer(
    override val localPort: Int,
    override val remoteHost: String,
    override val remotePort: Int,
    override val enableIncomingTls: Boolean = false,
    override val enableTargetTls: Boolean = false,
    override val trustTargetCert: Boolean = false
) : ProxyServer {

    override var listener: ProxyEventListener? = null

    private val _isRunning = AtomicBoolean(false)
    override val isRunning: Boolean get() = _isRunning.get()

    private val activeSockets = Collections.synchronizedSet(mutableSetOf<Socket>())
    private val connectionCounter = AtomicInteger(0)
    private var serverSocket: ServerSocket? = null
    private var acceptExecutor: ExecutorService? = null
    private var connectionExecutor: ExecutorService? = null

    override fun start() {
        check(!_isRunning.get()) { "Proxy server is already running on port $localPort" }
        _isRunning.set(true)
        
        serverSocket = if (enableIncomingTls) {
            val sslContext = CertificateUtil.createIncomingTlsSslContext()
            val sslServerSocketFactory = sslContext.serverSocketFactory
            sslServerSocketFactory.createServerSocket(localPort)
        } else {
            ServerSocket(localPort)
        }
        
        connectionExecutor = Executors.newCachedThreadPool()
        acceptExecutor = Executors.newSingleThreadExecutor().also { executor ->
            executor.submit { acceptLoop() }
        }
        
        val tlsInfo = if (enableIncomingTls) " (TLS)" else ""
        println("[TCPProxyServer] Server started on port $localPort$tlsInfo, forwarding to $remoteHost:$remotePort")
    }

    override fun stop() {
        if (!_isRunning.compareAndSet(true, false)) {
            println("[TCPProxyServer] stop() called but server is not running on port $localPort")
            return
        }
        println("[TCPProxyServer] Stopping server on port $localPort...")
        
        // First close server socket to unblock accept()
        val socketToClose = serverSocket
        serverSocket = null
        
        runCatching {
            socketToClose?.close()
            println("[TCPProxyServer] Server socket on port $localPort closed")
        }.onFailure { ex ->
            println("[TCPProxyServer] Error closing server socket: ${ex.message}")
        }
        
        // Close all active sockets to interrupt ongoing connections
        val socketsToClose = activeSockets.toList()
        activeSockets.clear()
        for (socket in socketsToClose) {
            runCatching {
                socket.close()
            }.onFailure { ex ->
                println("[TCPProxyServer] Error closing active socket: ${ex.message}")
            }
        }
        if (socketsToClose.isNotEmpty()) {
            println("[TCPProxyServer] Closed ${socketsToClose.size} active socket(s) on port $localPort")
        }
        
        // Then shutdown executors
        acceptExecutor?.shutdownNow()
        connectionExecutor?.shutdownNow()
        acceptExecutor = null
        connectionExecutor = null
        
        println("[TCPProxyServer] Server on port $localPort stopped successfully")
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        val executor = connectionExecutor ?: return
        while (_isRunning.get() && !ss.isClosed) {
            runCatching {
                val clientSocket = ss.accept()
                executor.submit { handleConnection(clientSocket) }
            }.onFailure { ex ->
                if (_isRunning.get()) {
                    val connectionId = "accept-error-${connectionCounter.incrementAndGet()}"
                    listener?.onEvent(ProxyEvent.Error(ProxyType.TCP, connectionId, "error", "", "$remoteHost:$remotePort", ex))
                }
            }
        }
    }

    private fun handleConnection(clientSocket: Socket) {
        val connectionId = "conn-${connectionCounter.incrementAndGet()}"
        activeSockets.add(clientSocket)
        val sourceAddr = clientSocket.remoteSocketAddress.toString().removePrefix("/")
        val targetAddr = "$remoteHost:$remotePort"
        val label = sourceAddr.substringAfterLast(':')  // Use client port as label
        // Accumulate raw bytes for this client connection (shared by both directions)
        val connectionBuffer = ByteArrayOutputStream()
        // Track the last direction that wrote to the buffer (for inserting a newline on direction switch)
        val lastDirection = AtomicReference<String?>(null)
        var remoteSocket: Socket? = null
        val executor = connectionExecutor ?: return
        try {
            // Create remote socket with TLS if enabled
            remoteSocket = if (enableTargetTls) {
                // Create SSLContext with appropriate TrustManager based on trustTargetCert
                val sslContext = CertificateUtil.createTargetTlsSslContext(trustAllCerts = trustTargetCert)
                val sslSocketFactory = sslContext.socketFactory
                val sslSocket = sslSocketFactory.createSocket(remoteHost, remotePort) as SSLSocket
                sslSocket.startHandshake()
                sslSocket
            } else {
                Socket(remoteHost, remotePort)
            }

            activeSockets.add(remoteSocket)

            synchronized(connectionBuffer) {
                connectionBuffer.write("[CONNECTED $sourceAddr -> $targetAddr]\n".toByteArray())
            }

            listener?.onEvent(ProxyEvent.Connected(ProxyType.TCP, connectionId, label, sourceAddr, targetAddr, connectionBuffer))

            // Client -> Remote: forward client input to remote output
            val clientToRemote = executor.submit {
                forward(
                    connectionId = connectionId,
                    label = label,
                    input = clientSocket.getInputStream(),
                    output = remoteSocket.getOutputStream(),
                    source = sourceAddr,
                    target = targetAddr,
                    direction = "C->S",
                    buffer = connectionBuffer,
                    lastDirection = lastDirection
                )
            }
            // Remote -> Client: forward remote input to client output
            forward(
                connectionId = connectionId,
                label = label,
                input = remoteSocket.getInputStream(),
                output = clientSocket.getOutputStream(),
                source = sourceAddr,
                target = targetAddr,
                direction = "S->C",
                buffer = connectionBuffer,
                lastDirection = lastDirection
            )
            clientToRemote.get()
        } catch (ex: Exception) {
            listener?.onEvent(ProxyEvent.Error(ProxyType.TCP, connectionId, label, sourceAddr, targetAddr, ex))
        } finally {
            activeSockets.remove(clientSocket)
            activeSockets.remove(remoteSocket)
            runCatching { clientSocket.close() }
            runCatching { remoteSocket?.close() }
            synchronized(connectionBuffer) {
                connectionBuffer.write("[DISCONNECTED]\n".toByteArray())
            }
            listener?.onEvent(ProxyEvent.Disconnected(ProxyType.TCP, connectionId, label, sourceAddr, targetAddr, connectionBuffer))
        }
    }

    private fun forward(
        connectionId: String,
        label: String,
        input: InputStream,
        output: OutputStream,
        source: String,
        target: String,
        direction: String,
        buffer: ByteArrayOutputStream,
        lastDirection: AtomicReference<String?>
    ) {
        val readBuffer = ByteArray(BUFFER_SIZE)
        try {
            var bytesRead: Int
            while (input.read(readBuffer).also { bytesRead = it } != -1) {
                output.write(readBuffer, 0, bytesRead)
                output.flush()
                synchronized(buffer) {
                    // Insert a newline when the direction switches from C->S to S->C
                    if (lastDirection.get() == "C->S" && direction == "S->C") {
                        buffer.write('\n'.code)
                    }
                    buffer.write(readBuffer, 0, bytesRead)
                    lastDirection.set(direction)
                }
                val event = if (direction == "C->S") {
                    ProxyEvent.DataTransferred.TcpIncoming(ProxyType.TCP, connectionId, label, source, target, bytesRead, "", buffer)
                } else {
                    ProxyEvent.DataTransferred.TcpOutgoing(ProxyType.TCP, connectionId, label, source, target, bytesRead, "", buffer)
                }
                listener?.onEvent(event)
            }
        } catch (_: Exception) {
            // Stream closed – exit silently; errors are handled in handleConnection
        }
    }

    companion object {
        private const val BUFFER_SIZE = 8192
    }
}
