package io.liparakis.cisplugin.ui;

import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import io.liparakis.cisplugin.decoder.CisFileData;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;

/**
 * Detailed view for a specific chunk section.
 * Contains Summary, Block List, and Voxel Slice visualization.
 */
public class SectionView extends JPanel {
    private final JBTabbedPane tabs;

    // Sub-components
    private final JPanel summaryPanel;
    private final JBTable blockListTable;
    private final DefaultTableModel blockListModel;
    private final SliceView sliceView;
    private final JSlider layerSlider;
    private final JTextArea rawJsonArea;

    public SectionView() {
        setLayout(new BorderLayout());
        tabs = new JBTabbedPane();

        // 1. Summary Tab
        summaryPanel = new JPanel();
        summaryPanel.setLayout(new BoxLayout(summaryPanel, BoxLayout.Y_AXIS));
        summaryPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        tabs.addTab("Summary", new JScrollPane(summaryPanel));

        // 2. Block List Tab
        blockListModel = new DefaultTableModel(new String[] { "X", "Y", "Z", "Block" }, 0);
        blockListTable = new JBTable(blockListModel);
        tabs.addTab("Block List", new JBScrollPane(blockListTable));

        // 3. Slice View Tab
        JPanel sliceContainer = new JPanel(new BorderLayout());
        sliceView = new SliceView();

        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        controlPanel.add(new JLabel("Layer Y (0-15):"));
        layerSlider = new JSlider(0, 15, 0);
        layerSlider.setMajorTickSpacing(5);
        layerSlider.setMinorTickSpacing(1);
        layerSlider.setPaintTicks(true);
        layerSlider.setPaintLabels(true);
        layerSlider.addChangeListener(e -> sliceView.setLayer(layerSlider.getValue()));
        controlPanel.add(layerSlider);

        sliceContainer.add(controlPanel, BorderLayout.NORTH);
        sliceContainer.add(sliceView, BorderLayout.CENTER);
        tabs.addTab("Voxel Slice", sliceContainer);

        // 4. Raw JSON (Placeholder)
        rawJsonArea = new JTextArea();
        rawJsonArea.setEditable(false);
        tabs.addTab("Raw Data", new JBScrollPane(rawJsonArea));

        add(tabs, BorderLayout.CENTER);
    }

    public void update(int y, CisStats stats) {
        if (stats == null)
            return;

        CisStats.SectionStats section = stats.getSection(y);
        if (section == null) {
            // Handle missing section (e.g. completely empty/air in sparse mode?)
            // Or just clear
            return;
        }

        // --- Summary ---
        summaryPanel.removeAll();
        summaryPanel.add(new JLabel("Y Level: " + y));
        summaryPanel.add(new JLabel("Encoding: " + (section.sparse() ? "Sparse (Map)" : "Dense (Array)")));
        summaryPanel.add(new JLabel("Non-Air Blocks: " + section.nonAirCount()));
        summaryPanel.add(Box.createVerticalGlue());
        summaryPanel.revalidate();
        summaryPanel.repaint();

        // --- Block List ---
        blockListModel.setRowCount(0);
        // Find the matching SectionData in pure data to iterate blocks list
        // (Since SectionStats only has voxels for rendering)
        stats.getData().getSections().stream()
                .filter(s -> s.getSectionY() == y)
                .findFirst()
                .ifPresent(sData -> {
                    // Limit to first 1000 to avoid UI freeze on huge dense sections
                    int count = 0;
                    for (CisFileData.BlockEntry b : sData.getBlocks()) {
                        if (count++ > 2000)
                            break;
                        String name = stats.getGlobalPalette().get(b.paletteIndex()).blockName();
                        blockListModel.addRow(new Object[] { b.x(), b.y(), b.z(), name });
                    }
                    if (sData.getBlocks().size() > 2000) {
                        blockListModel.addRow(new Object[] { "...", "...", "...", "Truncated" });
                    }
                });

        // --- Slice View ---
        sliceView.setSection(section, stats.getGlobalPalette());

        // --- Raw Data ---
        rawJsonArea.setText("Section Y=" + y + "\nSparse: " + section.sparse() + "\nBlocks: " + section.nonAirCount());
    }
}
