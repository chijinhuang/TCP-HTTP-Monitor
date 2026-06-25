package org.sea.proxy.ui

import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.ComboBoxEditor
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicComboBoxEditor
import javax.swing.text.JTextComponent

/**
 * A filterable combo box backed by [JComboBox] with case-insensitive auto-selection
 * and real-time popup filtering.
 *
 * Supports:
 * - Typing to filter items (case-insensitive)
 * - Arrow up/down to navigate
 * - Enter to select, Escape to close
 * - Free-form text input (returns typed text if no match)
 *
 * @param T the type of items
 * @param items the full list of items
 * @param labelProvider converts an item to its display string
 */
class FilterableComboBox<T : Any>(
    private val items: List<T>,
    private val labelProvider: (T) -> String = { it.toString() }
) : JComboBox<String>(DefaultComboBoxModel(items.map(labelProvider).toTypedArray())) {

    /** All items as display strings, used for filtering. */
    private val allItemStrings: List<String> = items.map(labelProvider).toList()

    /** When true, [showPopup] preserves the current filtered model (set by typing).
     *  When false (arrow button), it restores the full model. */
    private var preserveModel = false

    private fun restoreFullModel() {
        if (model.size != allItemStrings.size) {
            val text = (editor?.editorComponent as? JTextComponent)?.text ?: ""
            val caretPos = (editor?.editorComponent as? JTextComponent)?.caretPosition ?: 0
            model = DefaultComboBoxModel(allItemStrings.toTypedArray())
            (editor?.editorComponent as? JTextComponent)?.text = text
            (editor?.editorComponent as? JTextComponent)?.caretPosition = caretPos.coerceAtMost(text.length)
        }
    }

    init {
        // Step 1: Ensure JComboBox.editor is non-null
        super.setEditor(BasicComboBoxEditor())

        // Step 2: Mark as editable
        isEditable = true

        // Step 3: Force UI delegate re-install — now installComponents() will see
        // comboBox.isEditable() = true and set BasicComboBoxUI.editor = getEditor() (non-null).
        // This is the ONLY fix for the Darcula NPE at getDisplaySize().
        updateUI()

        // Step 4: Clear default selection (model auto-selects first item).
        // Must be done after #updateUI so the editor is fully initialized.
        selectedIndex = -1

        // Step 5: Register key listener on the now-initialized editor
        val editorComp = editor?.editorComponent as? JTextComponent
        editorComp?.addKeyListener(object : KeyAdapter() {
            /**
             * Intercept Enter BEFORE JComboBox's default handler.
             * The default handler selects based on editor text, but we want
             * the item highlighted in the popup (selectedIndex).
             */
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && isPopupVisible) {
                    e.consume()
                    val idx = if (selectedIndex >= 0) selectedIndex else 0
                    if (idx < model.size) {
                        val selected = model.getElementAt(idx)
                        editorComp.text = selected
                        selectedItem = selected
                    }
                    hidePopup()
                }
            }

            override fun keyReleased(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_ENTER, KeyEvent.VK_ESCAPE,
                    KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT -> return
                    else -> {}
                }
                val text = editorComp.text
                if (text.isEmpty()) {
                    restoreFullModel()
                    if (isPopupVisible) hidePopup()
                    return
                }

                preserveModel = true
                val filtered = allItemStrings.filter {
                    it.contains(text, ignoreCase = true)
                }

                if (filtered.isEmpty()) {
                    if (isPopupVisible) hidePopup()
                    return
                }

                // Update model only if the filtered list changed
                val currentModel = model
                val changed = currentModel.size != filtered.size ||
                    (0 until currentModel.size).any { currentModel.getElementAt(it) != filtered[it] }

                if (changed) {
                    val caretPos = editorComp.caretPosition
                    val currentText = text
                    model = DefaultComboBoxModel(filtered.toTypedArray())
                    // selectedIndex = 0 changes editor text via setSelectedItem.
                    // Restore typed text right after.
                    selectedIndex = 0
                    editorComp.text = currentText
                    editorComp.caretPosition = caretPos.coerceAtMost(currentText.length)
                }

                // Show popup for the filtered list
                if (!isPopupVisible) {
                    showPopup()
                }

                // Auto-select first match and highlight the completion suffix
                if (!filtered.isEmpty() && text.length < filtered[0].length) {
                    SwingUtilities.invokeLater {
                        if (editorComp.text.equals(filtered[0], ignoreCase = true) ||
                            editorComp.text.startsWith(filtered[0], ignoreCase = true) ||
                            filtered[0].startsWith(editorComp.text, ignoreCase = true)) {
                            editorComp.caretPosition = editorComp.text.length
                            editorComp.moveCaretPosition(filtered[0].length)
                        }
                    }
                }
            }
        })
    }

    /**
     * Safety net: after ANY UI delegate installation, ensure editor is non-null.
     */
    override fun updateUI() {
        super.updateUI()
        if (editor == null) {
            super.setEditor(BasicComboBoxEditor())
        }
    }

    /**
     * Darcula's `createEditor()` returns null, so `setEditable(true)` would call
     * `setEditor(null)` – reject null while editable to protect the editor.
     */
    override fun setEditor(editor: ComboBoxEditor?) {
        if (editor == null && isEditable) return
        super.setEditor(editor)
    }

    /**
     * Arrow button click → restore full model so all options are shown.
     * Typing filter → [preserveModel] keeps the filtered model intact.
     *
     * NOTE: The arrow button directly calls [setPopupVisible] (not [showPopup]),
     * so we must override [setPopupVisible] to catch both paths.
     */
    override fun setPopupVisible(v: Boolean) {
        if (v && !preserveModel) {
            restoreFullModel()
        }
        preserveModel = false
        super.setPopupVisible(v)
    }

    /**
     * Returns the selected/matched item, or the raw typed text if no exact match.
     */
    @Suppress("UNCHECKED_CAST")
    fun getSelectedValue(): T? {
        val text = (editor?.editorComponent as? JTextComponent)?.text?.trim() ?: ""
        if (text.isEmpty()) return null
        // Try to find an exact match among the original items
        val exactMatch = items.firstOrNull { labelProvider(it) == text }
        return exactMatch ?: text as T
    }

    fun setSelectedItemValue(item: T?) {
        val text = item?.let(labelProvider) ?: ""
        (editor?.editorComponent as? JTextComponent)?.text = text
    }
}
