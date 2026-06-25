package org.sea.proxy

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import org.sea.proxy.http.HttpProxyServer
import org.sea.proxy.tcp.TCPProxyServer
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.ByteArrayOutputStream
import javax.swing.*
import javax.swing.border.EmptyBorder

class ProxyPanel(private val project: Project) : JPanel(BorderLayout()) {

    // ------------------------------------------------------------------
    // Tab pane (center)
    // ------------------------------------------------------------------
    private val tabbedPane = JBTabbedPane()

    // ------------------------------------------------------------------
    // Left toolbar
    // ------------------------------------------------------------------
    private val toolbar: ActionToolbar = buildToolbar()

    init {
        val toolbarComponent = toolbar.component
        toolbarComponent.preferredSize = Dimension(toolbarComponent.preferredSize.width, toolbarComponent.preferredSize.height)

        add(toolbarComponent, BorderLayout.WEST)
        add(tabbedPane, BorderLayout.CENTER)

        // Load saved proxy configs from previous session (servers start stopped)
        loadSavedConfigs()
    }

    // ------------------------------------------------------------------
    // Public helpers (used by existing callers)
    // ------------------------------------------------------------------

    /**
     * Adds a row to the traffic table of the currently selected tab.
     * If no tab is open this call is a no-op.
     */
    fun addRow(time: String, source: String, target: String, status: String, data: String) {
        (tabbedPane.selectedComponent as? ProxyTabPanel)?.addRow(time, source, target, status, data)
    }

    /**
     * Clears the traffic table of the currently selected tab.
     */
    fun clearRows() {
        (tabbedPane.selectedComponent as? ProxyTabPanel)?.clearRows()
    }

    // ------------------------------------------------------------------
    // Toolbar actions
    // ------------------------------------------------------------------

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup().apply {
            add(AddProxyAction())
            add(EditProxyAction())
            addSeparator()
            add(StartProxyAction())
            add(StopProxyAction())
            add(RemoveProxyAction())
            addSeparator()
            add(ClearProxyAction())
        }
        val toolbar = ActionManager.getInstance()
            .createActionToolbar("ProxyToolbar", group, false) // false = vertical
        toolbar.targetComponent = this
        return toolbar
    }

    /** "+" – opens [ProxyConfigurationDialog] and adds a new tab on confirmation. */
    private inner class AddProxyAction : AnAction(
        "Add Proxy",
        "Create a new proxy configuration",
        AllIcons.General.Add
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val dialog = ProxyConfigurationDialog(project)
            if (dialog.showAndGet()) {
                val config = dialog.getConfig()
                if (dialog.saveOnly) {
                    // Save: persist config, create tab without starting
                    saveConfig(config)
                    addProxyTab(config, autoStart = false)
                    Messages.showInfoMessage(
                        project,
                        "Proxy configuration for port ${config.localPort} has been saved.",
                        "Saved Successfully"
                    )
                } else {
                    // OK: create tab and start server (no save)
                    addProxyTab(config, autoStart = true)
                }
            }
        }
    }

    /** "i" – opens [ProxyConfigurationDialog] with current tab's config for editing. */
    private inner class EditProxyAction : AnAction(
        "Edit Proxy",
        "Edit the selected proxy configuration",
        AllIcons.General.Information
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val idx = tabbedPane.selectedIndex
            if (idx >= 0) {
                val tabPanel = tabbedPane.getComponentAt(idx) as? ProxyTabPanel ?: return
                val currentConfig = tabPanel.config
                val wasRunning = tabPanel.server.isRunning
                val dialog = ProxyConfigurationDialog(project, currentConfig)
                if (dialog.showAndGet()) {
                    if (dialog.unsaveOnly) {
                        // Unsave: remove from persistent store, keep tab as-is
                        removeSavedConfig(currentConfig.localPort)
                        Messages.showInfoMessage(
                            project,
                            "Proxy configuration for port ${currentConfig.localPort} has been removed from saved configurations.",
                            "Unsaved Successfully"
                        )
                        return
                    }
                    val newConfig = dialog.getConfig()
                    // Stop old server if running
                    if (wasRunning) tabPanel.server.stop()
                    
                    if (dialog.saveOnly) {
                        // Save: persist updated config
                        removeSavedConfig(currentConfig.localPort)
                        saveConfig(newConfig)
                        Messages.showInfoMessage(
                            project,
                            "Proxy configuration for port ${newConfig.localPort} has been saved.",
                            "Saved Successfully"
                        )
                    }
                    
                    // Determine if server should be running
                    val shouldStart = if (dialog.saveOnly) false else wasRunning
                    // Remove old tab and create new one
                    tabbedPane.removeTabAt(idx)
                    addProxyTab(newConfig, autoStart = shouldStart)
                }
            }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = tabbedPane.tabCount > 0
        }
    }

    /** "💾" – removed from toolbar, now in dialog. */

    /** "▶" – starts the proxy server for the selected tab. */
    private inner class StartProxyAction : AnAction(
        "Start Proxy",
        "Start the selected proxy server",
        AllIcons.Actions.Execute
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val idx = tabbedPane.selectedIndex
            if (idx >= 0) {
                val tabPanel = tabbedPane.getComponentAt(idx) as? ProxyTabPanel ?: return
                if (!tabPanel.server.isRunning) {
                    try {
                        tabPanel.server.start()
                    } catch (ex: Exception) {
                        Messages.showErrorDialog(
                            project,
                            "Failed to start proxy on port ${tabPanel.config.localPort}: ${ex.message}",
                            "Start Error"
                        )
                    }
                }
            }
        }

        override fun update(e: AnActionEvent) {
            val idx = tabbedPane.selectedIndex
            val tabPanel = if (idx >= 0) tabbedPane.getComponentAt(idx) as? ProxyTabPanel else null
            e.presentation.isEnabled = tabPanel != null && !tabPanel.server.isRunning
        }
    }

    /** "■" – stops the proxy server for the selected tab and clears the log. */
    private inner class StopProxyAction : AnAction(
        "Stop Proxy",
        "Stop the selected proxy server and clear logs",
        AllIcons.Actions.Suspend
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val idx = tabbedPane.selectedIndex
            if (idx >= 0) {
                val tabPanel = tabbedPane.getComponentAt(idx) as? ProxyTabPanel ?: return
                if (tabPanel.server.isRunning) {
                    tabPanel.server.stop()
                    // Clear log data after stopping
                    tabPanel.clearRows()
                }
            }
        }

        override fun update(e: AnActionEvent) {
            val idx = tabbedPane.selectedIndex
            val tabPanel = if (idx >= 0) tabbedPane.getComponentAt(idx) as? ProxyTabPanel else null
            e.presentation.isEnabled = tabPanel != null && tabPanel.server.isRunning
        }
    }

    /** "-" – stops the server, removes the tab, and deletes the saved config. */
    private inner class RemoveProxyAction : AnAction(
        "Remove Proxy",
        "Remove the selected proxy configuration",
        AllIcons.General.Remove
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val idx = tabbedPane.selectedIndex
            if (idx >= 0) {
                val tabPanel = tabbedPane.getComponentAt(idx) as? ProxyTabPanel
                println("[RemoveProxyAction] Removing tab at index $idx, server running: ${tabPanel?.server?.isRunning}")
                tabPanel?.server?.stop()
                // Remove config from persistent store
                tabPanel?.let { removeSavedConfig(it.config.localPort) }
                tabbedPane.removeTabAt(idx)
            }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = tabbedPane.tabCount > 0
        }
    }

    /** Trash – clears the current tab's connection list and log. */
    private inner class ClearProxyAction : AnAction(
        "Clear Log",
        "Clear the current tab's connections and logs",
        AllIcons.Actions.GC
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            (tabbedPane.selectedComponent as? ProxyTabPanel)?.clearRows()
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = tabbedPane.tabCount > 0
        }
    }

    

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private fun addProxyTab(config: ProxyConfig, autoStart: Boolean = false) {
        // Check for duplicate local port among existing tabs
        val duplicate = (0 until tabbedPane.tabCount)
            .mapNotNull { tabbedPane.getComponentAt(it) as? ProxyTabPanel }
            .any { it.config.localPort == config.localPort }
        if (duplicate) {
            Messages.showErrorDialog(
                project,
                "Local port ${config.localPort} already has an open tab.",
                "Duplicate Tab"
            )
            return
        }
        
        // Create server based on proxy type
        val server: ProxyServer = createServer(config)
        
        val tabPanel = ProxyTabPanel(config, server)
        server.listener = tabPanel.asEventListener()
        if (autoStart) {
            server.start()
        }
        val tabIndex = tabbedPane.tabCount
        tabbedPane.addTab(config.tabTitle, tabPanel)
        // Set custom tab title component with close button
        tabbedPane.setTabComponentAt(tabIndex, buildCloseableTabTitle(config.tabTitle, tabPanel))
        tabbedPane.selectedIndex = tabbedPane.tabCount - 1
    }

    /** Creates a [ProxyServer] instance for the given [config] without starting it. */
    private fun createServer(config: ProxyConfig): ProxyServer = when (config.proxyType) {
        ProxyType.TCP -> TCPProxyServer(
            config.localPort,
            config.remoteHost,
            config.remotePort,
            config.enableIncomingTls,
            config.enableTargetTls,
            config.trustTargetCert
        )
        ProxyType.HTTP -> HttpProxyServer(
            config.localPort,
            config.remoteHost,
            config.remotePort,
            config.enableIncomingTls,
            config.enableTargetTls,
            config.trustTargetCert,
            config.replaceHostHeader,
            config.replaceRewriteLocation,
            config.encoding
        )
    }

    /**
     * Build a tab title component containing the title label and a close button.
     * The close button stops the proxy server and removes the tab when clicked.
     */
    private fun buildCloseableTabTitle(title: String, tabPanel: ProxyTabPanel): JPanel {
        val panel = JPanel().apply {
            layout = java.awt.BorderLayout(4, 0)
            isOpaque = false
            border = EmptyBorder(2, 4, 2, 4)
        }
        val titleLabel = JLabel(title).apply {
            isOpaque = false
        }
        val closeButton = JButton(AllIcons.Actions.Close).apply {
            toolTipText = "Close"
            preferredSize = Dimension(16, 16)
            minimumSize = Dimension(16, 16)
            maximumSize = Dimension(16, 16)
            isBorderPainted = false
            isFocusPainted = false
            isContentAreaFilled = false
            margin = java.awt.Insets(0, 0, 0, 0)
            addActionListener {
                val idx = tabbedPane.indexOfComponent(tabPanel)
                if (idx >= 0) {
                    println("[CloseTab] Closing tab at index $idx, server running: ${tabPanel.server.isRunning}")
                    tabPanel.server.stop()
                    tabbedPane.removeTabAt(idx)
                }
            }
        }
        panel.add(titleLabel, java.awt.BorderLayout.CENTER)
        panel.add(closeButton, java.awt.BorderLayout.EAST)
        return panel
    }

    // ------------------------------------------------------------------
    // Persistence helpers
    // ------------------------------------------------------------------

    /** Loads all saved configs and creates tabs (servers stay stopped). */
    private fun loadSavedConfigs() {
        val configs = ProxyConfigStore.loadConfigs()
        configs.forEach { config ->
            addProxyTab(config, autoStart = false)
        }
    }

    /** Appends [config] to the persistent store. */
    private fun saveConfig(config: ProxyConfig) {
        val current = ProxyConfigStore.loadConfigs().toMutableList()
        // Replace if same localPort already exists
        current.removeAll { it.localPort == config.localPort }
        current.add(config)
        ProxyConfigStore.saveConfigs(current)
    }

    /** Removes the config with the given [localPort] from the persistent store. */
    private fun removeSavedConfig(localPort: Int) {
        val current = ProxyConfigStore.loadConfigs().toMutableList()
        current.removeAll { it.localPort == localPort }
        ProxyConfigStore.saveConfigs(current)
    }
}

// ---------------------------------------------------------------------------
// Per-proxy tab content
// ---------------------------------------------------------------------------

/**
 * Layout inside each proxy tab:
 *
 * ```
 * ┌──────────────┬───────────────────────────────────────────┐
 * │ Connections  │  Log text area (read-only)                │
 * │  (2 parts)   │  (8 parts)                                │
 * │              │                                           │
 * │  54321       │  [10:23:01]  CONNECTED  127.0.0.1:54321  │
 * │  54322       │             -> 192.168.1.1:3306           │
 * │  54323       │  [10:23:01]  12 B  SELECT...             │
 * │  ...         │  [10:23:02]  DISCONNECTED                │
 * └──────────────┴───────────────────────────────────────────┘
 * ```
 *
 * Each entry in the left list represents one accepted client connection.
 * Clicking an entry shows its accumulated log on the right.
 *
 * @param config the proxy configuration
 * @param server the running [ProxyServer] bound to this tab
 */
class ProxyTabPanel(val config: ProxyConfig, var server: ProxyServer) : JPanel(BorderLayout()) {

    // key = connectionId (unique identifier for each connection/request)
    // HTTP: formatted log lines; TCP: raw byte buffer
    private val connectionLogs = LinkedHashMap<String, StringBuilder>()
    private val connectionBuffers = LinkedHashMap<String, ByteArrayOutputStream>()
    
    // key = connectionId, value = label (for display in left panel)
    private val connectionLabels = mutableMapOf<String, String>()
    
    // key = connectionId, value = proxy type (to decide how to render the log)
    private val connectionProxyTypes = mutableMapOf<String, ProxyType>()

    // For HTTP: track list item index to connectionId mapping (since labels can be duplicated)
    private val listIndexToConnectionId = mutableListOf<String>()

    // All items (unfiltered) for filter restoration
    private val allItems = mutableListOf<Pair<String, String>>() // label to connectionId

    // Left: list model + JBList
    private val listModel = DefaultListModel<String>()
    private val connectionList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
    }

    // Filter text field
    private val filterField = SearchTextField().apply {
        textEditor.columns = 12
        addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                applyFilter(text)
            }
        })
    }

    // Right: read-only monospaced text area
    private val logArea = JBTextArea().apply {
        isEditable = false
        font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        lineWrap = false
    }

    init {
        // Left panel: filter field + connection list
        val leftPanel = JPanel(BorderLayout()).apply {
            add(filterField, BorderLayout.NORTH)
            add(JBScrollPane(connectionList).apply {
                minimumSize = Dimension(0, 0)
            }, BorderLayout.CENTER)
        }
        
        // Split pane with 2:8 weight, thin black line divider
        val rightScroll = JBScrollPane(logArea).apply {
            minimumSize = Dimension(0, 0)
        }
        val splitPane = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            leftPanel,
            rightScroll
        ).apply {
            resizeWeight = 0.2          // left takes 20 %, right takes 80 %
            isContinuousLayout = true
            dividerLocation = 200       // initial divider position in pixels
            dividerSize = 1             // thin 1px divider
        }
        // Force the divider itself to render as a solid black line
        (splitPane.ui as? javax.swing.plaf.basic.BasicSplitPaneUI)?.divider?.let { divider ->
            divider.background = java.awt.Color.BLACK
        }
        add(splitPane, BorderLayout.CENTER)

        // When selection changes, refresh the right-side log
        connectionList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val selectedIndex = connectionList.selectedIndex
                if (selectedIndex >= 0 && selectedIndex < listIndexToConnectionId.size) {
                    // Get the connectionId for this specific list item
                    val connectionId = listIndexToConnectionId[selectedIndex]
                    refreshLogArea(connectionId)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Called from ProxyEventListener (may be on a background thread)
    // ------------------------------------------------------------------

    /** Routes a [ProxyEvent] to the TCP or HTTP handler. */
    fun appendLog(event: ProxyEvent) {
        when (event.proxyType) {
            ProxyType.TCP -> appendTcpLog(event)
            ProxyType.HTTP -> appendHttpLog(event)
        }
    }

    /** TCP handler: the log content for a TCP connection is always its [ByteArrayOutputStream]. */
    private fun appendTcpLog(event: ProxyEvent) {
        val connectionId = event.connectionId
        val label = event.label

        // Should register a new connection in the left list?
        val shouldRegister = when (event) {
            is ProxyEvent.Connected -> true
            is ProxyEvent.Error -> true
            is ProxyEvent.DataTransferred.TcpIncoming,
            is ProxyEvent.DataTransferred.TcpOutgoing -> true
            else -> false
        }

        SwingUtilities.invokeLater {
            if (shouldRegister && !connectionBuffers.containsKey(connectionId)) {
                registerConnection(connectionId, label, ProxyType.TCP)
            }

            // The buffer is the single source of truth for TCP log content.
            val buffer = when (event) {
                is ProxyEvent.Connected -> event.buffer
                is ProxyEvent.Disconnected -> event.buffer
                is ProxyEvent.DataTransferred.TcpIncoming -> event.buffer
                is ProxyEvent.DataTransferred.TcpOutgoing -> event.buffer
                else -> null
            }
            if (buffer != null) {
                connectionBuffers[connectionId] = buffer
            }

            if (connectionList.selectedValue == label) {
                refreshLogArea(connectionId)
            }
        }
    }

    /** HTTP handler: formatted log lines are accumulated in a [StringBuilder]. */
    private fun appendHttpLog(event: ProxyEvent) {
        val connectionId = event.connectionId
        val label = event.label

        val shouldRegister = event is ProxyEvent.DataTransferred.HttpRequestLine

        SwingUtilities.invokeLater {
            if (shouldRegister && !connectionLogs.containsKey(connectionId)) {
                registerConnection(connectionId, label, ProxyType.HTTP)
            }

            val line = when (event) {
                is ProxyEvent.Connected -> "[${now()}]  CONNECTED  ${event.source} -> ${event.target}"
                is ProxyEvent.Disconnected -> "[${now()}]  DISCONNECTED"
                is ProxyEvent.Error -> "[${now()}]  ERROR  ${event.error.message ?: event.error.javaClass.simpleName}"
                is ProxyEvent.DataTransferred.HttpRequestLine -> "[${now()}]  >> REQUEST  ${event.method} ${event.uri}"
                is ProxyEvent.DataTransferred.HttpRequestHeader -> "[${now()}]     HEADERS\n${event.data}"
                is ProxyEvent.DataTransferred.HttpRequestPayload -> "[${now()}]  >> C->S  ${event.byteCount} B  ${event.data}"
                is ProxyEvent.DataTransferred.HttpResponseStatus -> "[${now()}]  << STATUS  ${event.statusCode} ${event.reasonPhrase}"
                is ProxyEvent.DataTransferred.HttpResponseHeader -> "[${now()}]     RESP_HEADERS\n${event.data}"
                is ProxyEvent.DataTransferred.HttpResponsePayload -> "[${now()}]  << S->C  ${event.byteCount} B  ${event.data}"
                else -> return@invokeLater
            }
            val sb = connectionLogs.getOrPut(connectionId) { StringBuilder() }
            sb.appendLine(line)

            if (connectionList.selectedValue == label) {
                refreshLogArea(connectionId)
            }
        }
    }

    /** Registers a new connection in the left list and initializes the appropriate log storage. */
    private fun registerConnection(connectionId: String, label: String, proxyType: ProxyType) {
        connectionLabels[connectionId] = label
        connectionProxyTypes[connectionId] = proxyType
        if (proxyType == ProxyType.TCP) {
            connectionBuffers[connectionId] = ByteArrayOutputStream()
        } else {
            connectionLogs[connectionId] = StringBuilder()
        }
        // TCP: only add if label doesn't exist (same port shares label)
        // HTTP: always add (label can be duplicated, each request is independent)
        if (proxyType == ProxyType.TCP) {
            if (!listModel.contains(label)) {
                allItems.add(label to connectionId)
                if (matchesFilter(label)) {
                    listModel.addElement(label)
                    listIndexToConnectionId.add(connectionId)
                }
            }
        } else {
            allItems.add(label to connectionId)
            if (matchesFilter(label)) {
                listModel.addElement(label)
                listIndexToConnectionId.add(connectionId)
            }
        }
    }

    /** Refreshes the right-side log area from the stored StringBuilder or ByteArrayOutputStream. */
    private fun refreshLogArea(connectionId: String) {
        val text = if (connectionProxyTypes[connectionId] == ProxyType.TCP) {
            val charset = resolveCharset()
            println("encoding:$charset")
            synchronized(connectionBuffers[connectionId] ?: ByteArrayOutputStream()) {
                connectionBuffers[connectionId]?.toString(charset) ?: ""
            }
        } else {
            connectionLogs[connectionId]?.toString() ?: ""
        }
        logArea.text = text
        logArea.caretPosition = logArea.document.length
    }

    /** Resolves the charset to use for displaying TCP log bytes.
     *  If [ProxyConfig.encoding] is configured, use it; otherwise fall back to the JVM default charset. */
    private fun resolveCharset(): java.nio.charset.Charset {
        val name = config.encoding
        if (name.isBlank()) return java.nio.charset.Charset.defaultCharset()
        return runCatching { java.nio.charset.Charset.forName(name) }
            .getOrElse { java.nio.charset.Charset.defaultCharset() }
    }

    // ------------------------------------------------------------------
    // Legacy helpers (kept for API compatibility)
    // ------------------------------------------------------------------

    fun addRow(time: String, source: String, target: String, status: String, data: String) {
        val connectionId = "$source -> $target"
        val label = connectionId
        appendLog(ProxyEvent.Connected(ProxyType.TCP, connectionId, label, source, target))
    }

    fun clearRows() {
        SwingUtilities.invokeLater {
            connectionLogs.clear()
            connectionBuffers.clear()
            connectionProxyTypes.clear()
            listModel.clear()
            logArea.text = ""
            connectionLabels.clear()
            listIndexToConnectionId.clear()
            allItems.clear()
            filterField.text = ""
            // Reset selection to ensure auto-select works for new connections
            connectionList.clearSelection()
        }
    }

    /**
     * Check if a label matches the current filter text (case-insensitive).
     */
    private fun matchesFilter(label: String): Boolean {
        val filter = filterField.text.trim()
        if (filter.isEmpty()) return true
        return label.contains(filter, ignoreCase = true)
    }

    /**
     * Apply filter to the connection list, showing only items that match the filter.
     */
    private fun applyFilter(filter: String) {
        SwingUtilities.invokeLater {
            listModel.clear()
            listIndexToConnectionId.clear()
            
            val trimmedFilter = filter.trim()
            for ((label, connectionId) in allItems) {
                if (trimmedFilter.isEmpty() || label.contains(trimmedFilter, ignoreCase = true)) {
                    listModel.addElement(label)
                    listIndexToConnectionId.add(connectionId)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // ProxyEventListener factory
    // ------------------------------------------------------------------

    /** Creates a [ProxyEventListener] that routes all events to [appendLog]. */
    fun asEventListener() = object : ProxyEventListener {
        override fun onEvent(event: ProxyEvent) {
            appendLog(event)
        }
    }

    private fun now(): String = java.time.LocalTime.now().toString().take(12)
}
