package org.sea.proxy

import com.intellij.ide.util.PropertiesComponent

/**
 * Persists [ProxyConfig] entries across IDE restarts using the application-level
 * [PropertiesComponent] (not bound to any specific project).
 *
 * Each config is serialised as a JSON string keyed by its local port.
 * A separate key tracks the list of saved ports in order.
 */
object ProxyConfigStore {

    private const val PREFIX = "tcp-proxy.config."
    private const val KEY_LIST = "${PREFIX}list"

    private fun props(): PropertiesComponent = PropertiesComponent.getInstance()

    // ------------------------------------------------------------------
    // Load
    // ------------------------------------------------------------------

    /**
     * Loads all saved proxy configurations.
     * Returns an empty list if nothing has been saved yet.
     */
    fun loadConfigs(): List<ProxyConfig> {
        val p = props()
        val listJson = p.getValue(KEY_LIST) ?: return emptyList()
        val ports = try {
            parseJsonArray(listJson)
        } catch (_: Exception) {
            return emptyList()
        }
        return ports.mapNotNull { portStr ->
            val port = portStr.trim().toIntOrNull() ?: return@mapNotNull null
            loadSingleConfig(p, port)
        }

    }

    private fun loadSingleConfig(props: PropertiesComponent, localPort: Int): ProxyConfig? {
        val json = props.getValue("$PREFIX$localPort") ?: return null
        val map = try {
            parseJsonObject(json)
        } catch (_: Exception) {
            return null
        }
        return ProxyConfig(
            proxyType = if (map["proxyType"] == "HTTP") ProxyType.HTTP else ProxyType.TCP,
            localPort = localPort,
            remoteHost = map["remoteHost"] ?: return null,
            remotePort = (map["remotePort"]?.toIntOrNull()) ?: return null,
            encoding = map["encoding"] ?: "",
            enableIncomingTls = map["enableIncomingTls"] == "true",
            enableTargetTls = map["enableTargetTls"] == "true",
            trustTargetCert = map["trustTargetCert"] == "true",
            replaceHostHeader = map["replaceHostHeader"] == "true",
            replaceRewriteLocation = map["replaceRewriteLocation"] == "true"
        )
    }

    // ------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------

    /**
     * Saves the full list of [configs],
     * replacing any previously stored configurations.
     */
    fun saveConfigs(configs: List<ProxyConfig>) {
        val p = props()

        // Remove all existing entries first
        val oldListJson = p.getValue(KEY_LIST)
        if (oldListJson != null) {
            try {
                parseJsonArray(oldListJson).forEach { portStr ->
                    val port = portStr.trim().toIntOrNull()
                    if (port != null) p.unsetValue("$PREFIX$port")
                }
            } catch (_: Exception) { /* ignore */ }
        }

        // Write new entries
        val ports = configs.map { it.localPort.toString() }
        p.setValue(KEY_LIST, toJsonArray(ports))
        configs.forEach { saveSingleConfig(p, it) }
    }

    private fun saveSingleConfig(props: PropertiesComponent, config: ProxyConfig) {
        val json = buildString {
            append("{")
            append("\"proxyType\":\"${config.proxyType}\",")
            append("\"localPort\":${config.localPort},")
            append("\"remoteHost\":\"${escapeJson(config.remoteHost)}\",")
            append("\"remotePort\":${config.remotePort},")
            append("\"encoding\":\"${escapeJson(config.encoding)}\",")
            append("\"enableIncomingTls\":${config.enableIncomingTls},")
            append("\"enableTargetTls\":${config.enableTargetTls},")
            append("\"trustTargetCert\":${config.trustTargetCert},")
            append("\"replaceHostHeader\":${config.replaceHostHeader},")
            append("\"replaceRewriteLocation\":${config.replaceRewriteLocation}")
            append("}")
        }
        props.setValue("$PREFIX${config.localPort}", json)
    }

    // ------------------------------------------------------------------
    // Minimal JSON helpers (no external dependency)
    // ------------------------------------------------------------------

    private fun toJsonArray(list: List<String>): String =
        list.joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" }

    private fun parseJsonArray(json: String): List<String> {
        val trimmed = json.trim()
        if (trimmed.length < 2 || trimmed.first() != '[' || trimmed.last() != ']') return emptyList()
        val inner = trimmed.substring(1, trimmed.length - 1)
        if (inner.isBlank()) return emptyList()
        return inner.split(",").map { it.trim().removeSurrounding("\"") }
    }

    private fun parseJsonObject(json: String): Map<String, String> {
        val trimmed = json.trim()
        if (trimmed.first() != '{' || trimmed.last() != '}') return emptyMap()
        val inner = trimmed.substring(1, trimmed.length - 1)
        if (inner.isBlank()) return emptyMap()
        return inner.split(",").associate { entry ->
            val (key, value) = entry.split(":", limit = 2)
            key.trim().removeSurrounding("\"") to value.trim().removeSurrounding("\"")
        }
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}
