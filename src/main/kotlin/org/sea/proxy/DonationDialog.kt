package org.sea.proxy

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.*

class DonationDialog : DialogWrapper(true) {

    companion object {
        private const val MAX_IMAGE_SIZE = 250
    }

    private val cardLayout = CardLayout()
    private val imagePanel = JPanel(cardLayout)

    init {
        title = "Support This Plugin"
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
        }

        // Description – two natural paragraphs with proper wrapping
        val description = JBLabel(
            """
            <html>
              <div style='text-align:left; width:340px;'>
                <b>Thank you for using Proxy Plugin!</b>
                <br/><br/>
                If you find this plugin helpful, please consider supporting its development.
                Your generous support means a lot to us and is truly appreciated.
                <br/><br/>
                Choose a payment method below:
              </div>
            </html>
            """.trimIndent()
        ).apply {
            horizontalAlignment = SwingConstants.CENTER
            alignmentX = java.awt.Component.CENTER_ALIGNMENT
        }

        // Radio buttons for payment method
        val alipayRadio = JRadioButton("Alipay", true)
        val wechatRadio = JRadioButton("WeChat Pay")
        val group = ButtonGroup().apply {
            add(alipayRadio)
            add(wechatRadio)
        }

        val radioPanel = JPanel(FlowLayout(FlowLayout.CENTER, 20, 0)).apply {
            add(alipayRadio)
            add(wechatRadio)
            alignmentX = java.awt.Component.CENTER_ALIGNMENT
        }

        // Load images and add to card panel
        val alipayImage = loadDonationImage("/donation/alipay.jpg")
        val wechatImage = loadDonationImage("/donation/wechatpay.png")

        imagePanel.apply {
            alignmentX = java.awt.Component.CENTER_ALIGNMENT
            preferredSize = Dimension(MAX_IMAGE_SIZE + 20, MAX_IMAGE_SIZE + 20)
            add(alipayImage, "alipay")
            add(wechatImage, "wechat")
        }
        cardLayout.show(imagePanel, "alipay")

        // Radio button listeners
        alipayRadio.addActionListener { cardLayout.show(imagePanel, "alipay") }
        wechatRadio.addActionListener { cardLayout.show(imagePanel, "wechat") }

        // Use BoxLayout: elements stack vertically, never overlap
        mainPanel.add(description)
        mainPanel.add(Box.createVerticalStrut(10))
        mainPanel.add(radioPanel)
        mainPanel.add(Box.createVerticalStrut(12))
        mainPanel.add(imagePanel)

        return mainPanel
    }

    private fun loadDonationImage(path: String): JComponent {
        return try {
            val stream = javaClass.getResourceAsStream(path)
            if (stream != null) {
                val original: BufferedImage = ImageIO.read(stream)
                val scaled = scaleImage(original, MAX_IMAGE_SIZE)
                val icon = ImageIcon(scaled)
                JLabel(icon).apply {
                    horizontalAlignment = SwingConstants.CENTER
                }
            } else {
                JBLabel("Image not found").apply {
                    horizontalAlignment = SwingConstants.CENTER
                }
            }
        } catch (e: Exception) {
            JBLabel("Failed to load image: ${e.message}").apply {
                horizontalAlignment = SwingConstants.CENTER
            }
        }
    }

    /** Scale image proportionally so neither width nor height exceeds [maxSize]. */
    private fun scaleImage(source: BufferedImage, maxSize: Int): BufferedImage {
        val origW = source.width
        val origH = source.height
        if (origW <= maxSize && origH <= maxSize) return source

        val scale = maxSize.toDouble() / maxOf(origW, origH)
        val newW = (origW * scale).toInt()
        val newH = (origH * scale).toInt()

        val scaled = BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB)
        val g2d: Graphics2D = scaled.createGraphics()
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2d.drawImage(source, 0, 0, newW, newH, null)
        g2d.dispose()
        return scaled
    }
}
