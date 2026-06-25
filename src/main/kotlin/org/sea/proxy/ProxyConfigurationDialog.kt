package org.sea.proxy

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import org.sea.proxy.ui.FilterableComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Proxy type enumeration.
 */
enum class ProxyType {
    TCP,
    HTTP
}

/**
 * Dialog for configuring a proxy connection.
 *
 * Collects [proxyType], [localPort], [remoteHost], and [remotePort] from the user.
 */
class ProxyConfigurationDialog(
    project: Project,
    initialConfig: ProxyConfig? = null
) : DialogWrapper(project) {

    /** True when the user clicked "Save" instead of "OK". */
    var saveOnly: Boolean = false
        private set

    /** True when the user clicked "Unsave". */
    var unsaveOnly: Boolean = false
        private set

    private val editingConfig = initialConfig
    private val tcpRadioButton = com.intellij.ui.components.JBRadioButton("TCP", true)
    private val httpRadioButton = com.intellij.ui.components.JBRadioButton("HTTP", false)
    private val localPortField = JBTextField(initialConfig?.localPort?.toString() ?: "", 50)
    private val remoteHostField = JBTextField(initialConfig?.remoteHost ?: "", 50)
    private val remotePortField = JBTextField(initialConfig?.remotePort?.toString() ?: "", 50)
    private val enableIncomingTlsCheckBox = JBCheckBox("Enable TLS for incoming requests", initialConfig?.enableIncomingTls ?: false)
    private val enableTargetTlsCheckBox = JBCheckBox("Enable TLS for target server", initialConfig?.enableTargetTls ?: false)
    private val trustTargetCertCheckBox = JBCheckBox("Trust target server certificate", initialConfig?.trustTargetCert ?: false)
    private val replaceHostHeaderCheckBox = JBCheckBox("Replace Host header with target", initialConfig?.replaceHostHeader ?: false)
    private val replaceRewriteLocationCheckBox = JBCheckBox("Rewrite Location header to localhost", initialConfig?.replaceRewriteLocation ?: false)

    /** Encoding options for the filterable combo box (all charsets supported by the JVM). */
    private val encodingOptions: List<String> = java.nio.charset.Charset.availableCharsets()
        .keys
        .toList()
        .sorted()
    private val encodingComboBox = FilterableComboBox(encodingOptions).apply {
        if (initialConfig != null) {
            setSelectedItemValue(initialConfig.encoding)
        }
    }

    init {
        title = if (initialConfig == null) "New Proxy Configuration" else "Edit Proxy Configuration"

        // Set initial proxy type if editing
        if (initialConfig != null) {
            tcpRadioButton.isSelected = initialConfig.proxyType == ProxyType.TCP
            httpRadioButton.isSelected = initialConfig.proxyType == ProxyType.HTTP
        }

        // Update trustTargetCertCheckBox state based on enableTargetTlsCheckBox
        updateTrustTargetCertState()

        // Add listener to enableTargetTlsCheckBox to update trustTargetCertCheckBox
        enableTargetTlsCheckBox.addItemListener { _ ->
            updateTrustTargetCertState()
        }

        // Update HTTP-specific checkboxes state based on proxy type
        updateHttpSpecificState()

        // Add listener to proxy type radio buttons
        tcpRadioButton.addItemListener { _ -> updateHttpSpecificState() }
        httpRadioButton.addItemListener { _ -> updateHttpSpecificState() }

        init()
    }

    /**
     * Update trustTargetCertCheckBox enabled state and selection based on enableTargetTlsCheckBox.
     */
    private fun updateTrustTargetCertState() {
        if (enableTargetTlsCheckBox.isSelected) {
            trustTargetCertCheckBox.isEnabled = true
        } else {
            trustTargetCertCheckBox.isEnabled = false
            trustTargetCertCheckBox.isSelected = false
        }
    }

    /**
     * Update HTTP-specific checkboxes state based on proxy type.
     * These are only applicable to HTTP proxy.
     */
    private fun updateHttpSpecificState() {
        val isHttp = httpRadioButton.isSelected
        replaceHostHeaderCheckBox.isEnabled = isHttp
        replaceRewriteLocationCheckBox.isEnabled = isHttp
        if (!isHttp) {
            replaceHostHeaderCheckBox.isSelected = false
            replaceRewriteLocationCheckBox.isSelected = false
        }
    }

    override fun createCenterPanel(): JComponent {
        // Group radio buttons
        val buttonGroup = javax.swing.ButtonGroup().apply {
            add(tcpRadioButton)
            add(httpRadioButton)
        }

        // Panel for radio buttons
        val radioPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT)).apply {
            add(tcpRadioButton)
            add(httpRadioButton)
        }

        // Basic Configuration Section with border
        val basicConfigPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Proxy Type:"), radioPanel, 1, false)
            .addLabeledComponent(JBLabel("Local Port:"), localPortField, 1, false)
            .addLabeledComponent(JBLabel("Remote Host:"), remoteHostField, 1, false)
            .addLabeledComponent(JBLabel("Remote Port:"), remotePortField, 1, false)
            .addLabeledComponent(JBLabel("Encoding:"), encodingComboBox, 1, false)
            .panel

        val basicConfigTitledBorder = javax.swing.BorderFactory.createTitledBorder(
            javax.swing.BorderFactory.createLineBorder(java.awt.Color(180, 180, 180)),
            "Basic Configuration"
        )
        basicConfigPanel.border = basicConfigTitledBorder

        // TLS Configuration Section with border
        val tlsConfigPanel = FormBuilder.createFormBuilder()
            .addComponent(enableIncomingTlsCheckBox, 1)
            .addComponent(enableTargetTlsCheckBox, 1)
            .addComponent(trustTargetCertCheckBox, 1)
            .panel

        val tlsConfigTitledBorder = javax.swing.BorderFactory.createTitledBorder(
            javax.swing.BorderFactory.createLineBorder(java.awt.Color(180, 180, 180)),
            "TLS Configuration"
        )
        tlsConfigPanel.border = tlsConfigTitledBorder

        // HTTP Configuration Section with border
        val httpConfigPanel = FormBuilder.createFormBuilder()
            .addComponent(replaceHostHeaderCheckBox, 1)
            .addComponent(replaceRewriteLocationCheckBox, 1)
            .panel

        val httpConfigTitledBorder = javax.swing.BorderFactory.createTitledBorder(
            javax.swing.BorderFactory.createLineBorder(java.awt.Color(180, 180, 180)),
            "HTTP Configuration"
        )
        httpConfigPanel.border = httpConfigTitledBorder

        // Main panel with sections
        val mainPanel = JPanel(java.awt.BorderLayout(0, 10)).apply {
            add(basicConfigPanel, java.awt.BorderLayout.NORTH)
            add(tlsConfigPanel, java.awt.BorderLayout.CENTER)
            add(httpConfigPanel, java.awt.BorderLayout.SOUTH)
        }

        return mainPanel
    }

    // ------------------------------------------------------------------
    // Dialog button bar: [Save Unsave]          [OK Cancel]
    // ------------------------------------------------------------------

    override fun createActions(): Array<javax.swing.Action> {
        // All actions on the right side: Save, Unsave (if editing), OK, Cancel
        val actions = mutableListOf<javax.swing.Action>()
        actions.add(SaveDialogAction())
        if (editingConfig != null) {
            actions.add(UnsaveDialogAction())
        }
        actions.add(okAction)
        actions.add(cancelAction)
        return actions.toTypedArray()
    }

    /** Save button in dialog button bar – validates, sets saveOnly, closes dialog. */
    private inner class SaveDialogAction : DialogWrapperAction("Save") {
        override fun doAction(e: java.awt.event.ActionEvent) {
            val validationInfo = doValidate()
            if (validationInfo != null) {
                validationInfo.component?.requestFocusInWindow()
                return
            }
            saveOnly = true
            close(OK_EXIT_CODE)
        }
    }

    /** Unsave button in dialog bar – sets unsaveOnly, closes dialog. */
    private inner class UnsaveDialogAction : DialogWrapperAction("Unsave") {
        override fun doAction(e: java.awt.event.ActionEvent) {
            unsaveOnly = true
            close(OK_EXIT_CODE)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = localPortField

    override fun doValidate(): ValidationInfo? {
        val lp = localPortField.text.trim().toIntOrNull()
        if (lp == null || lp !in 1..65535) {
            return ValidationInfo("Local port must be between 1 and 65535.", localPortField)
        }
        if (remoteHostField.text.isBlank()) {
            return ValidationInfo("Remote host must not be empty.", remoteHostField)
        }
        val rp = remotePortField.text.trim().toIntOrNull()
        if (rp == null || rp !in 1..65535) {
            return ValidationInfo("Remote port must be between 1 and 65535.", remotePortField)
        }
        return null
    }

    /**
     * Returns the validated [ProxyConfig] entered by the user.
     * Only valid after the dialog is confirmed (exit code == OK).
     */
    fun getConfig(): ProxyConfig = ProxyConfig(
        proxyType = if (tcpRadioButton.isSelected) ProxyType.TCP else ProxyType.HTTP,
        localPort = localPortField.text.trim().toInt(),
        remoteHost = remoteHostField.text.trim(),
        remotePort = remotePortField.text.trim().toInt(),
        encoding = encodingComboBox.getSelectedValue() ?: "",
        enableIncomingTls = enableIncomingTlsCheckBox.isSelected,
        enableTargetTls = enableTargetTlsCheckBox.isSelected,
        trustTargetCert = trustTargetCertCheckBox.isSelected,
        replaceHostHeader = replaceHostHeaderCheckBox.isSelected,
        replaceRewriteLocation = replaceRewriteLocationCheckBox.isSelected
    )
}

/**
 * Immutable value object holding a single proxy entry's configuration.
 */
data class ProxyConfig(
    val proxyType: ProxyType = ProxyType.TCP,
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
    val encoding: String = "",
    val enableIncomingTls: Boolean = false,
    val enableTargetTls: Boolean = false,
    val trustTargetCert: Boolean = false,
    val replaceHostHeader: Boolean = false,
    val replaceRewriteLocation: Boolean = false
) {
    /** Tab display name: [Type] localPort -> remoteHost:remotePort */
    val tabTitle: String get() = "[$proxyType] $localPort -> $remoteHost:$remotePort"
}
