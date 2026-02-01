package io.liparakis.cisplugin.ui;

import io.liparakis.cisplugin.decoder.SimpleBlockState;
import com.intellij.ui.JBColor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.List;

/**
 * Visualizes a 16x16 horizontal slice of block data.
 */
public class SliceView extends JPanel {
    private CisStats.SectionStats currentSection;
    private List<SimpleBlockState> palette;
    private int yLayer = 0;

    public SliceView() {
        setBackground(JBColor.background());
        setToolTipText(""); // Enable tooltips

        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                handleMouseMove(e);
            }
        });
    }

    public void setSection(CisStats.SectionStats section, List<SimpleBlockState> palette) {
        this.currentSection = section;
        this.palette = palette;
        repaint();
    }

    public void setLayer(int y) {
        this.yLayer = Math.max(0, Math.min(15, y));
        repaint();
    }

    private void handleMouseMove(MouseEvent e) {
        if (currentSection == null)
            return;

        int w = getWidth();
        int h = getHeight();
        int scale = Math.min(w, h) / 16;
        if (scale == 0)
            return;

        int x = e.getX() / scale;
        int z = e.getY() / scale;

        if (x >= 0 && x < 16 && z >= 0 && z < 16) {
            int paletteIndex = currentSection.voxels()[x][yLayer][z];
            String blockName = "Air";
            if (palette != null && paletteIndex >= 0 && paletteIndex < palette.size()) {
                blockName = palette.get(paletteIndex).blockName(); // Use just the name
            }
            // If checking specifically against ID 0 or name "air" could be better
            if (blockName.contains("air") || blockName.contains("Air")) {
                setToolTipText(String.format("Air (%d, %d, %d)", x, yLayer, z));
            } else {
                setToolTipText(String.format("%s (%d, %d, %d)", blockName, x, yLayer, z));
            }
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);

        if (currentSection == null) {
            g.setColor(JBColor.foreground());
            g.drawString("No Section Selected", 10, 20);
            return;
        }

        int w = getWidth();
        int h = getHeight();
        int scale = Math.min(w, h) / 16;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int paletteIndex = currentSection.voxels()[x][yLayer][z];
                Color c = getBlockColor(paletteIndex);

                g.setColor(c);
                g.fillRect(x * scale, z * scale, scale, scale);
                g.setColor(JBColor.border());
                g.drawRect(x * scale, z * scale, scale, scale);
            }
        }
    }

    private Color getBlockColor(int index) {
        if (index <= 0)
            return new JBColor(new Color(240, 240, 240, 20), new Color(60, 60, 60, 20)); // "Air" / Empty

        if (palette != null && index < palette.size()) {
            String name = palette.get(index).blockName();
            if (name.contains("air"))
                return new JBColor(new Color(240, 240, 240, 20), new Color(60, 60, 60, 20));

            // Generate consistent color hash
            int hash = name.hashCode();
            float hue = (Math.abs(hash) % 360) / 360f;
            return Color.getHSBColor(hue, 0.6f, 0.8f);
        }

        return JBColor.GRAY;
    }
}
