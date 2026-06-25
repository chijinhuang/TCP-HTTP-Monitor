package org.sea.proxy.util

import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Utility class for managing TLS certificates.
 * Uses keystore file from resources for incoming TLS connections.
 */
object CertificateUtil {

    private const val KEYSTORE_PASSWORD = "tcp-proxy-plugin"
    private const val KEYSTORE_RESOURCE_PATH = "/keystore/server.jks"

    /**
     * Load keystore from resources and return an SSLContext for incoming TLS connections.
     * The keystore file (server.jks) is packaged in the plugin's resources.
     */
    fun createIncomingTlsSslContext(): SSLContext {
        val keyStore = loadKeystoreFromResources()
        
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, KEYSTORE_PASSWORD.toCharArray())
        
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagerFactory.keyManagers, null, null)
        
        return sslContext
    }

    /**
     * Create an SSLContext for outgoing TLS connections to target server.
     * The SSLContext is initialized with a TrustManager based on the trustAllCerts parameter:
     * - true: trust all certificates (skip verification)
     * - false: use the default system TrustManager (normal CA verification)
     */
    fun createTargetTlsSslContext(trustAllCerts: Boolean = false): SSLContext {
        val trustManager: X509TrustManager = if (trustAllCerts) {
            getTrustAllManager()
        } else {
            getDefaultTrustManager()
        }
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(
            null,
            arrayOf<TrustManager>(trustManager),
            SecureRandom()
        )
        return sslContext
    }

    /**
     * Get a trust manager that trusts all certificates (for use with SSLSocketFactory).
     * Use this to skip certificate verification (e.g., for self-signed certificates).
     */
    fun getTrustAllManager(): X509TrustManager {
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }

    /**
     * Get the default X509TrustManager from the system trust store.
     * Use this for normal certificate verification (only trusts CA-signed certificates).
     */
    fun getDefaultTrustManager(): X509TrustManager {
        val tmf = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
        )
        tmf.init(null as java.security.KeyStore?)
        return tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
    }

    /**
     * Load keystore from the plugin's resources.
     * The keystore file is located at /keystore/server.jks in the classpath.
     */
    private fun loadKeystoreFromResources(): KeyStore {
        val keyStore = KeyStore.getInstance("JKS")
        
        // Load from classpath resource
        val resourceStream = CertificateUtil::class.java.getResourceAsStream(KEYSTORE_RESOURCE_PATH)
            ?: throw IllegalStateException("Keystore resource not found: $KEYSTORE_RESOURCE_PATH")
        
        resourceStream.use { stream ->
            keyStore.load(stream, KEYSTORE_PASSWORD.toCharArray())
        }
        
        return keyStore
    }
}
