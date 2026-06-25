package org.sea.proxy.http

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.sea.proxy.ProxyEvent
import org.sea.proxy.ProxyType
import java.nio.charset.Charset
import java.util.Collections

/**
 * Servlet that handles HTTP proxy requests.
 * Each servlet instance holds a reference to its owning [HttpProxyServer] via [server].
 */
class ProxyServlet : HttpServlet() {

    private lateinit var remoteHost: String
    private lateinit var remotePort: String
    private var replaceHostHeader: Boolean = false
    private var replaceRewriteLocation: Boolean = false
    private var targetTls: Boolean = false
    /** User-configured default encoding for text-like HTTP message bodies. */
    private var defaultEncoding: String = ""

    // Each servlet instance holds a reference to its owning server
    internal lateinit var server: HttpProxyServer
        private set

    override fun init() {
        remoteHost = servletConfig.getInitParameter("remoteHost")
        remotePort = servletConfig.getInitParameter("remotePort")
        replaceHostHeader = servletConfig.getInitParameter("replaceHostHeader")?.toBoolean() ?: false
        replaceRewriteLocation = servletConfig.getInitParameter("replaceRewriteLocation")?.toBoolean() ?: false
        targetTls = servletConfig.getInitParameter("enableTargetTls")?.toBoolean() ?: false
        defaultEncoding = servletConfig.getInitParameter("encoding") ?: ""
        
        // Get server instance from registry using localPort
        val localPort = servletConfig.getInitParameter("localPort")?.toIntOrNull()
            ?: throw IllegalStateException("localPort init parameter not configured for ProxyServlet")
        server = HttpProxyServerRegistry.getServer(localPort)
            ?: throw IllegalStateException("No HttpProxyServer registered for port $localPort")
    }

    override fun service(req: HttpServletRequest, resp: HttpServletResponse) {
        handleRequest(req, resp)
    }

    private fun handleRequest(req: HttpServletRequest, resp: HttpServletResponse) {
        val sourceAddr = "${req.remoteAddr}:${req.remotePort}"
        val targetAddr = "$remoteHost:$remotePort"

        val queryString = req.queryString
        val uri = if (queryString != null) "${req.requestURI}?$queryString" else req.requestURI

        val connectionId = "Http-${server.connectionCounter.incrementAndGet()}"
        val label = uri

        val okHttpClient: OkHttpClient = server.buildOkHttpClient()

        try {
            if (!server.isRunning) {
                resp.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
                return
            }

            val targetScheme = if (server.enableTargetTls) "https" else "http"
            val targetUrl = "$targetScheme://$remoteHost:$remotePort$uri"

            // Collect ALL request headers from the servlet.
            // Use Collections.list() because Undertow's getHeaderNames() returns a
            // one-shot Enumeration that can only be iterated once.
            val allHeaders = LinkedHashMap<String, MutableList<String>>()
            val headerNames = Collections.list(req.headerNames)
            headerNames.forEach { name ->
                val values = req.getHeaders(name)
                val list = mutableListOf<String>()
                while (values.hasMoreElements()) {
                    val v = values.nextElement()
                    if (v != null) list.add(v)
                }
                if (list.isNotEmpty()) allHeaders[name] = list
            }
            // Explicitly pick up Host (not exposed by getHeaderNames)
            req.getHeader("Host")?.takeIf { it.isNotEmpty() }?.let { hostValue ->
                val finalHost = if (replaceHostHeader) "$remoteHost:$remotePort" else hostValue
                allHeaders["Host"] = mutableListOf(finalHost)
            }

            // Replace Referer and Origin headers with target if configured
            if (replaceHostHeader) {
                // Replace Referer header scheme+authority with target
                val refererKey = allHeaders.keys.firstOrNull { it.equals("Referer", ignoreCase = true) }
                val refererValue = refererKey?.let { allHeaders[it]?.firstOrNull() }
                if (refererValue != null) {
                    try {
                        val refererUri = java.net.URI(refererValue)
                        val newReferer = "$targetScheme://$remoteHost:$remotePort${refererUri.rawPath.orEmpty()}${refererUri.rawQuery?.let { "?$it" }.orEmpty()}${refererUri.rawFragment?.let { "#$it" }.orEmpty()}"
                        allHeaders.remove(refererKey)
                        allHeaders["Referer"] = mutableListOf(newReferer)
                    } catch (_: Exception) {
                        // Keep original Referer if parsing fails
                    }
                }

                // Replace Origin header with target
                val originKey = allHeaders.keys.firstOrNull { it.equals("Origin", ignoreCase = true) }
                if (originKey != null) {
                    allHeaders.remove(originKey)
                    allHeaders["Origin"] = mutableListOf("$targetScheme://$remoteHost:$remotePort")
                }
            }

            println("[HttpProxyServer] Collected ${allHeaders.size} headers from client:")
            allHeaders.forEach { (name, values) ->
                println("[HttpProxyServer]   $name: ${values.joinToString("; ")}")
            }

            // Read body once (InputStream is single-use).
            val requestBodyBytes: ByteArray = if (req.contentLength > 0) {
                req.inputStream.readBytes()
            } else ByteArray(0)
            val requestBodySize = requestBodyBytes.size
            // Select charset with 3-tier fallback:
            //   1. charset declared in Content-Type (if present and valid)
            //   2. configured defaultEncoding (when MIME is text-like)
            //   3. UTF-8
            val requestBodyCharset = selectCharset(req.contentType, defaultEncoding)
            val requestBodyPreview = if (requestBodySize > 0) {
                requestBodyBytes.toString(requestBodyCharset)
            } else ""

            // Build OkHttp request with all headers
            val requestBody: RequestBody = object : RequestBody() {
                override fun contentType() = req.contentType?.toMediaTypeOrNull()
                override fun contentLength(): Long = requestBodySize.toLong()
                override fun writeTo(sink: BufferedSink) {
                    if (requestBodySize > 0) sink.write(requestBodyBytes)
                }
            }
            val method = req.method.uppercase()
            val body: RequestBody? = if (method == "GET" || method == "HEAD") null else requestBody
            val requestBuilder = Request.Builder()
                .url(targetUrl)
                .method(method, body)

            // Set all headers directly on Request.Builder
            allHeaders.forEach { (name, values) ->
                values.forEach { requestBuilder.addHeader(name, it) }
            }

            val proxiedRequest = requestBuilder.build()

            println("[HttpProxyServer] Final OkHttp request headers (what will be sent):")
            proxiedRequest.headers.forEach { (name, value) ->
                println("[HttpProxyServer]   $name: $value")
            }

            // Emit request line event
            server.listener?.onEvent(
                ProxyEvent.DataTransferred.HttpRequestLine(ProxyType.HTTP, connectionId, label, sourceAddr, targetAddr, req.method, uri)
            )

            // Emit request headers event
            if (allHeaders.isNotEmpty()) {
                server.listener?.onEvent(
                    ProxyEvent.DataTransferred.HttpRequestHeader(ProxyType.HTTP, connectionId, label, sourceAddr, targetAddr, allHeaders)
                )
            }

            // Emit request payload event
            if (requestBodySize > 0) {
                server.listener?.onEvent(
                    ProxyEvent.DataTransferred.HttpRequestPayload(ProxyType.HTTP, connectionId, label, sourceAddr, targetAddr, requestBodySize, requestBodyPreview)
                )
            }

            // Execute request synchronously
            val okHttpResponse: Response = okHttpClient.newCall(proxiedRequest).execute()
            okHttpResponse.use { response ->
                val responseCode = response.code
                val responseMessage = response.message

                resp.status = responseCode

                println("[HttpProxyServer] OkHttp response headers (status=$responseCode $responseMessage):")
                response.headers.forEach { (name, value) ->
                    println("[HttpProxyServer]   $name: $value")
                }

                // Copy response headers to client
                val responseHeadersLog = StringBuilder()
                val responseHeaders = response.headers
                for (i in 0 until responseHeaders.size) {
                    val name = responseHeaders.name(i)
                    val value = responseHeaders.value(i)
                    if (name.equals("Transfer-Encoding", ignoreCase = true) ||
                        name.equals("Content-Length", ignoreCase = true)) continue
                    val finalValue = if (replaceRewriteLocation && name.equals("Location", ignoreCase = true)) {
                        rewriteLocation(value)
                    } else value
                    resp.addHeader(name, finalValue)
                    if (responseHeadersLog.isNotEmpty()) responseHeadersLog.append(", ")
                    if (replaceRewriteLocation && name.equals("Location", ignoreCase = true) && finalValue != value) {
                        responseHeadersLog.append("$name: $value -> $finalValue [REWRITTEN]")
                    } else {
                        responseHeadersLog.append("$name: $value")
                    }
                }

                // Read response body
                val responseBody = response.body?.bytes() ?: ByteArray(0)
                val responseSize = responseBody.size
                // Select charset with 3-tier fallback (same strategy as request body):
                //   1. charset declared in Content-Type (if present and valid)
                //   2. configured defaultEncoding (when MIME is text-like)
                //   3. UTF-8
                val responseBodyCharset = selectCharset(response.header("Content-Type"), defaultEncoding)

                // Emit response status event
                server.listener?.onEvent(
                    ProxyEvent.DataTransferred.HttpResponseStatus(ProxyType.HTTP, connectionId, label, sourceAddr, targetAddr, responseCode, responseMessage)
                )

                // Emit response headers event
                if (responseHeadersLog.isNotEmpty()) {
                    val respHeaderMap = linkedMapOf<String, MutableList<String>>()
                    for (i in 0 until responseHeaders.size) {
                        val n = responseHeaders.name(i)
                        if (n.equals("Transfer-Encoding", ignoreCase = true) || n.equals("Content-Length", ignoreCase = true)) continue
                        respHeaderMap.getOrPut(n) { mutableListOf() }.add(responseHeaders.value(i))
                    }
                    server.listener?.onEvent(
                        ProxyEvent.DataTransferred.HttpResponseHeader(ProxyType.HTTP, connectionId, label, sourceAddr, targetAddr, respHeaderMap)
                    )
                }

                // Emit response payload event
                if (responseSize > 0) {
                    val responseBodyPreview = responseBody.toString(responseBodyCharset)
                    server.listener?.onEvent(
                        ProxyEvent.DataTransferred.HttpResponsePayload(ProxyType.HTTP, connectionId, label, sourceAddr, targetAddr, responseSize, responseBodyPreview)
                    )
                }

                // Write response body to client
                resp.outputStream.use { it.write(responseBody) }
            }

        } catch (ex: Exception) {
            println("[HttpProxyServer] Error handling request: ${ex.javaClass.simpleName}: ${ex.message}")
            ex.printStackTrace()
            server.listener?.onEvent(ProxyEvent.Error(ProxyType.HTTP, connectionId, label, sourceAddr, targetAddr, ex))
            if (!resp.isCommitted) {
                resp.status = HttpServletResponse.SC_BAD_GATEWAY
            }
        } finally {
            runCatching {
                okHttpClient.dispatcher.executorService.shutdown()
                okHttpClient.connectionPool.evictAll()
            }
        }
    }

    /**
     * Parse the charset from a Content-Type header value.
     *
     * Returns `null` when no `charset` parameter is present or the declared charset
     * name is not supported on this JVM. Callers should fall back to UTF-8 in that case.
     *
     * Examples:
     *  - "application/json; charset=UTF-8"        -> [Charsets.UTF_8]
     *  - "text/html;charset=ISO-8859-1"           -> ISO_8859_1
     *  - "application/x-www-form-urlencoded"      -> null
     *  - "text/plain; charset=\"GBK\""            -> GBK
     */
    private fun parseCharsetFromContentType(contentType: String?): Charset? {
        if (contentType.isNullOrBlank()) return null
        val semicolonIndex = contentType.indexOf(';')
        if (semicolonIndex < 0 || semicolonIndex == contentType.length - 1) return null
        val paramsPart = contentType.substring(semicolonIndex + 1)
        val match = CHARSET_REGEX.find(paramsPart) ?: return null
        val charsetName = match.groupValues[1]
        if (charsetName.isEmpty()) return null
        return try {
            Charset.forName(charsetName)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extract the lower-cased MIME type (without parameters) from a Content-Type header value.
     * For example: "Application/JSON; charset=UTF-8" -> "application/json".
     */
    private fun extractMimeType(contentType: String?): String? {
        if (contentType.isNullOrBlank()) return null
        val first = contentType.substringBefore(';').trim().lowercase()
        return first.takeIf { it.isNotEmpty() }
    }

    /**
     * Returns true when the MIME type is expected to carry textual payload (where a configured
     * default encoding should apply in absence of an explicit charset).
     *
     * Covers: text/<any>, application/json, application/xml, application/xhtml+xml, application/atom+xml.
     */
    private fun isTextLikeMime(mime: String): Boolean {
        if (mime.isEmpty()) return false
        if (mime.startsWith("text/")) return true
        return mime in TEXT_LIKE_APPLICATION_MIME
    }

    /**
     * Resolve the [Charset] used to decode an HTTP message body for display.
     *
     * Strategy:
     *   1. Charset explicitly declared in [contentType] (e.g. "application/json; charset=UTF-8").
     *   2. The configured [defaultEncoding] when [contentType] is text-like
     *      (text/<any>, application/json, application/xml, application/xhtml+xml, application/atom+xml).
     *   3. UTF-8 as final fallback.
     */
    private fun selectCharset(contentType: String?, defaultEncoding: String): Charset {
        parseCharsetFromContentType(contentType)?.let { return it }
        val mime = extractMimeType(contentType) ?: return Charsets.UTF_8
        if (isTextLikeMime(mime) && defaultEncoding.isNotBlank()) {
            try {
                return Charset.forName(defaultEncoding)
            } catch (_: Exception) {
                // configured encoding name invalid on this JVM -> fall through
            }
        }
        return Charsets.UTF_8
    }

    /**
     * Rewrite the Location header to point to localhost:localPort.
     */
    private fun rewriteLocation(location: String): String {
        return try {
            if (location.startsWith("http://", ignoreCase = true) ||
                location.startsWith("https://", ignoreCase = true)) {
                val u = java.net.URI(location)
                val pathAndQuery = buildString {
                    u.rawPath?.let { append(it) }
                    u.rawQuery?.let { append("?").append(it) }
                    u.rawFragment?.let { append("#").append(it) }
                }
                val scheme = if (server.enableIncomingTls) "https" else "http"
                "$scheme://localhost:${server.localPort}$pathAndQuery"
            } else location
        } catch (ex: Exception) {
            location
        }
    }

    private companion object {
        /** Matches `charset=VALUE` or `charset="VALUE"` inside the Content-Type parameter list. */
        val CHARSET_REGEX = Regex(
            """charset\s*=\s*["']?([^"';\s]+)["']?""",
            RegexOption.IGNORE_CASE
        )

        /**
         * Application MIME types considered "text-like" for the purpose of default-encoding fallback.
         * Combined with the text/<any> prefix (matched separately), this covers all textual HTTP payloads.
         */
        val TEXT_LIKE_APPLICATION_MIME = setOf(
            "application/json",
            "application/xml",
            "application/xhtml+xml",
            "application/atom+xml"
        )

    }
}
