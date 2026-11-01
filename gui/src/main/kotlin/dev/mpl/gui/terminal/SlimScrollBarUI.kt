package dev.mpl.gui.terminal

import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.plaf.basic.BasicScrollBarUI

class SlimScrollBarUI(private val track: Color, private val thumb: Color, private val thumbActive: Color) : BasicScrollBarUI() {
    override fun createDecreaseButton(orientation: Int) = hiddenButton()

    override fun createIncreaseButton(orientation: Int) = hiddenButton()

    override fun getMinimumThumbSize() = Dimension(WIDTH, 40)

    override fun getPreferredSize(c: JComponent) = Dimension(WIDTH, 0)

    override fun paintTrack(g: Graphics, c: JComponent, bounds: Rectangle) {
        g.color = track
        g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height)
    }

    override fun paintThumb(g: Graphics, c: JComponent, bounds: Rectangle) {
        if (bounds.isEmpty || scrollbar.visibleAmount >= scrollbar.maximum - scrollbar.minimum) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = if (isDragging || isThumbRollover) thumbActive else thumb
        val width = bounds.width - INSET * 2
        g2.fillRoundRect(bounds.x + INSET, bounds.y + INSET, width, bounds.height - INSET * 2, width, width)
        g2.dispose()
    }

    private fun hiddenButton() = JButton().apply {
        preferredSize = Dimension(0, 0)
        minimumSize = Dimension(0, 0)
        maximumSize = Dimension(0, 0)
    }

    private companion object {
        const val WIDTH = 12
        const val INSET = 3
    }
}
