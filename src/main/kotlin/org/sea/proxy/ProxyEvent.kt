package org.sea.proxy

import java.io.ByteArrayOutputStream

/**
 * Sealed class hierarchy for all proxy traffic events.
 *
 * Connection lifecycle:
 *   [Connected] → [DataTransferred]… → [Disconnected]
 *   [Error] can occur at any point.
 *
 * Data transfer:
 *   [IncomingData] – client → server (request for HTTP, raw bytes for TCP)
 *   [OutgoingData] – server → client (response for HTTP, raw bytes for TCP)
 */
sealed class ProxyEvent(
    val proxyType: ProxyType,
    val connectionId: String,
    val label: String,
    val source: String,
    val target: String
) {

    // ------------------------------------------------------------------
    // Connection lifecycle events
    // ------------------------------------------------------------------

    /** A new connection (TCP) or HTTP request has been established. */
    class Connected(
        proxyType: ProxyType, connectionId: String, label: String,
        source: String, target: String,
        val buffer: ByteArrayOutputStream = ByteArrayOutputStream()
    ) : ProxyEvent(proxyType, connectionId, label, source, target)

    /** A connection has been closed. */
    class Disconnected(
        proxyType: ProxyType, connectionId: String, label: String,
        source: String, target: String,
        val buffer: ByteArrayOutputStream = ByteArrayOutputStream()
    ) : ProxyEvent(proxyType, connectionId, label, source, target)

    /** An error occurred on the connection. */
    class Error(
        proxyType: ProxyType, connectionId: String, label: String,
        source: String, target: String,
        val error: Throwable
    ) : ProxyEvent(proxyType, connectionId, label, source, target)

    // ------------------------------------------------------------------
    // Data transfer events (sealed sub-hierarchy)
    // ------------------------------------------------------------------

    /** Base for all data-transfer events. */
    sealed class DataTransferred(
        proxyType: ProxyType, connectionId: String, label: String,
        source: String, target: String,
        val byteCount: Int,
        val data: String
    ) : ProxyEvent(proxyType, connectionId, label, source, target) {

        // ----- Incoming: client → server -----

        /** Client-to-server data (TCP raw bytes). */
        class TcpIncoming(
            proxyType: ProxyType, connectionId: String, label: String,
            source: String, target: String,
            byteCount: Int, data: String,
            val buffer: ByteArrayOutputStream
        ) : DataTransferred(proxyType, connectionId, label, source, target, byteCount, data)

        /** HTTP request line (e.g. "GET /path HTTP/1.1"). */
        class HttpRequestLine(
            proxyType: ProxyType, connectionId: String, label: String,
            source: String, target: String,
            val method: String, val uri: String
        ) : DataTransferred(proxyType, connectionId, label, source, target, 0, "$method $uri")

        /** HTTP request headers. */
        class HttpRequestHeader(
            proxyType: ProxyType, connectionId: String, label: String,
            source: String, target: String,
            val headers: Map<String, List<String>>
        ) : DataTransferred(
            proxyType, connectionId, label, source, target, 0,
            headers.entries.joinToString("\n") { (n, vs) -> "$n: ${vs.joinToString("; ")}" }
        )

        /** HTTP request body / payload. */
        class HttpRequestPayload(
            proxyType: ProxyType, connectionId: String, label: String,
            source: String, target: String,
            byteCount: Int, data: String
        ) : DataTransferred(proxyType, connectionId, label, source, target, byteCount, data)

        // ----- Outgoing: server → client -----

        /** Server-to-client data (TCP raw bytes). */
        class TcpOutgoing(
            proxyType: ProxyType, connectionId: String, label: String,
            source: String, target: String,
            byteCount: Int, data: String,
            val buffer: ByteArrayOutputStream
        ) : DataTransferred(proxyType, connectionId, label, source, target, byteCount, data)

        /** HTTP response status line (e.g. "200 OK"). */
        class HttpResponseStatus(
            proxyType: ProxyType, connectionId: String, label: String,
            source: String, target: String,
            val statusCode: Int, val reasonPhrase: String
        ) : DataTransferred(
            proxyType, connectionId, label, source, target, 0,
            "HTTP $statusCode $reasonPhrase"
        )

        /** HTTP response headers. */
        class HttpResponseHeader(
            proxyType: ProxyType, connectionId: String, label: String,
            source: String, target: String,
            val headers: Map<String, List<String>>
        ) : DataTransferred(
            proxyType, connectionId, label, source, target, 0,
            headers.entries.joinToString("\n") { (n, vs) -> "$n: ${vs.joinToString("; ")}" }
        )

        /** HTTP response body / payload. */
        class HttpResponsePayload(
            proxyType: ProxyType, connectionId: String, label: String,
            source: String, target: String,
            byteCount: Int, data: String
        ) : DataTransferred(proxyType, connectionId, label, source, target, byteCount, data)
    }
}
