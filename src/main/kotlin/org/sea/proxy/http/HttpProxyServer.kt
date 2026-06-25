package org.sea.proxy.http

import io.undertow.Undertow
import io.undertow.servlet.Servlets
import okhttp3.OkHttpClient
import org.sea.proxy.ProxyEventListener
import org.sea.proxy.ProxyServer
import org.sea.proxy.util.CertificateUtil
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * HTTP implementation of [ProxyServer] using Undertow Servlet API that accepts HTTP requests on [localPort]
 * and forwards traffic to [remoteHost]:[remotePort].
 */
class HttpProxyServer(
    override val localPort: Int,
    override val remoteHost: String,
    override val remotePort: Int,
    override val enableIncomingTls: Boolean = false,
    override val enableTargetTls: Boolean = false,
    override val trustTargetCert: Boolean = false,
    val replaceHostHeader: Boolean = false,
    val replaceRewriteLocation: Boolean = false,
    /**
     * User-configured default encoding used for HTTP message body decoding when the
     * Content-Type header does not declare a charset and the MIME type is text-like
     * (e.g. text/anything, application/json, application/xml,
     * application/xhtml+xml, application/atom+xml).
     */
    val encoding: String = ""
) : ProxyServer {

    override var listener: ProxyEventListener? = null

    private val _isRunning = AtomicBoolean(false)
    override val isRunning: Boolean get() = _isRunning.get()

    internal val connectionCounter = AtomicInteger(0)
    private var undertow: Undertow? = null

    override fun start() {
        check(!_isRunning.get()) { "Proxy server is already running on port $localPort" }
        _isRunning.set(true)

        // Register this server instance so servlets can look it up
        HttpProxyServerRegistry.register(localPort, this)

        // Create servlet deployment
        val deployment = Servlets.deployment()
        deployment.setClassLoader(HttpProxyServer::class.java.classLoader)
        deployment.setContextPath("/")
        deployment.setDeploymentName("http-proxy")

        // Register proxy servlet
        val servletInfo = Servlets.servlet("ProxyServlet", ProxyServlet::class.java)
            .addMapping("/*")
        servletInfo.addInitParam("remoteHost", remoteHost)
        servletInfo.addInitParam("remotePort", remotePort.toString())
        servletInfo.addInitParam("replaceHostHeader", replaceHostHeader.toString())
        servletInfo.addInitParam("replaceRewriteLocation", replaceRewriteLocation.toString())
        servletInfo.addInitParam("enableTargetTls", enableTargetTls.toString())
        servletInfo.addInitParam("localPort", localPort.toString())
        servletInfo.addInitParam("encoding", encoding)
        deployment.addServlet(servletInfo)

        val manager = Servlets.defaultContainer().addDeployment(deployment)
        manager.deploy()

        // Build Undertow with HTTP or HTTPS listener
        val undertowBuilder = Undertow.builder()

        if (enableIncomingTls) {
            val sslContext = CertificateUtil.createIncomingTlsSslContext()
            undertowBuilder.addHttpsListener(localPort, "0.0.0.0", sslContext)
        } else {
            undertowBuilder.addHttpListener(localPort, "0.0.0.0")
        }

        undertow = undertowBuilder
            .setHandler(manager.start())
            .build()
            .also { it.start() }

        val tlsInfo = if (enableIncomingTls) " (TLS)" else ""
        val targetTlsInfo = if (enableTargetTls) " (TLS to target)" else ""
        println("[HttpProxyServer] Server started on port $localPort$tlsInfo, forwarding to $remoteHost:$remotePort$targetTlsInfo")
    }

    override fun stop() {
        if (!_isRunning.compareAndSet(true, false)) {
            println("[HttpProxyServer] stop() called but server is not running on port $localPort")
            return
        }
        println("[HttpProxyServer] Stopping server on port $localPort...")

        // Stop undertow server
        runCatching {
            undertow?.stop()
            println("[HttpProxyServer] Undertow server on port $localPort stopped")
        }.onFailure { ex ->
            println("[HttpProxyServer] Error stopping undertow server: ${ex.message}")
        }

        undertow = null
        HttpProxyServerRegistry.unregister(localPort)

        println("[HttpProxyServer] Server on port $localPort stopped successfully")
    }

    /**
     * Returns the SSLContext for connecting to the target server, or null when TLS is disabled.
     */
    private val targetSslContext: SSLContext?
        get() = if (enableTargetTls) {
            CertificateUtil.createTargetTlsSslContext(trustAllCerts = trustTargetCert)
        } else null

    /**
     * Returns the TrustManager for the target server, or null when TLS is disabled.
     */
    private val targetTrustManager: X509TrustManager?
        get() = if (enableTargetTls) {
            if (trustTargetCert) CertificateUtil.getTrustAllManager()
            else CertificateUtil.getDefaultTrustManager()
        } else null

    /**
     * Whether the hostname verifier should accept any hostname (used when trustTargetCert=true).
     */
    private val trustAllHostnames: Boolean
        get() = enableTargetTls && trustTargetCert

    /**
     * Build a per-request [OkHttpClient] configured for the target server.
     *
     * Each request gets its own client so the executorService and connectionPool can be
     * released in the servlet's finally block, preventing resource leaks.
     */
    internal fun buildOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)

        if (enableTargetTls) {
            // enableTargetTls == true => targetSslContext and targetTrustManager are non-null.
            val sslContext = targetSslContext!!
            val trustManager = targetTrustManager!!
            builder.sslSocketFactory(sslContext.socketFactory, trustManager)
            if (trustAllHostnames) {
                builder.hostnameVerifier { _, _ -> true }
            }
        }

        return builder.build()
    }

}
