package io.liparakis.cisplugin.ui;

import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import io.liparakis.cisplugin.decoder.CisFileData;
import io.liparakis.cisplugin.decoder.RegionFileReader;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;

/**
 * Main Controller for the Chunkis Inspector UI.
 * Layout: 3-pane (Tree | Main View | Stats).
 */
public class CisInspector extends JPanel {

    // Components
    private final JTree navigationTree;
    private final JPanel centerPanel;
    private final CardLayout centerLayout;
    private final StatsPanel statsPanel;

    // Views
    private final PaletteView paletteView;
    private final SectionView sectionView;
    private final JPanel headerView;

    // Data
    private CisStats currentStats;

    public CisInspector() {
        setLayout(new BorderLayout());

        // 1. Navigation Tree (Left)
        navigationTree = new Tree();
        navigationTree.addTreeSelectionListener(e -> onNodeSelected());
        JBScrollPane treeScroll = new JBScrollPane(navigationTree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("Navigation"));

        // 2. Center Panel (Views)
        centerLayout = new CardLayout();
        centerPanel = new JPanel(centerLayout);

        JPanel emptyView = new JPanel(new GridBagLayout());
        emptyView.add(new JLabel("Select a node to view details"));

        paletteView = new PaletteView();
        sectionView = new SectionView();
        headerView = createHeaderView();

        centerPanel.add(emptyView, "EMPTY");
        centerPanel.add(headerView, "HEADER");
        centerPanel.add(paletteView, "PALETTE");
        centerPanel.add(sectionView, "SECTION");

        // 3. Stats Panel (Right)
        statsPanel = new StatsPanel();
        JBScrollPane statsScroll = new JBScrollPane(statsPanel);
        statsScroll.setBorder(BorderFactory.createTitledBorder("Chunk Stats"));

        // Layout Assembly using nested JBSplitter for 3-pane effect
        // Left | (Center | Right)
        JBSplitter contentSplitter = new JBSplitter(false, 0.7f);
        contentSplitter.setFirstComponent(centerPanel);
        contentSplitter.setSecondComponent(statsScroll);

        JBSplitter mainSplitter = new JBSplitter(false, 0.25f);
        mainSplitter.setFirstComponent(treeScroll);
        mainSplitter.setSecondComponent(contentSplitter);

        add(mainSplitter, BorderLayout.CENTER);
    }

    public void setData(CisFileData data, RegionFileReader.ChunkEntry entry) {
        this.currentStats = new CisStats(data, entry);

        buildTree();
        statsPanel.update(currentStats);

        // Select header by default
        if (navigationTree.getRowCount() > 0) {
            navigationTree.setSelectionRow(0);
        }
    }

    private void buildTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("Chunk");

        DefaultMutableTreeNode headerNode = new DefaultMutableTreeNode(new NodeInfo("Header", "HEADER", -1));
        root.add(headerNode);

        DefaultMutableTreeNode paletteNode = new DefaultMutableTreeNode(new NodeInfo("Palette", "PALETTE", -1));
        root.add(paletteNode);

        DefaultMutableTreeNode sectionsNode = new DefaultMutableTreeNode("Sections");
        for (CisStats.SectionStats section : currentStats.getSections()) {
            String label = String.format("Section Y=%d (%s)", section.y(), section.sparse() ? "Sparse" : "Dense");
            sectionsNode.add(new DefaultMutableTreeNode(new NodeInfo(label, "SECTION", section.y())));
        }
        root.add(sectionsNode);

        navigationTree.setModel(new DefaultTreeModel(root));

        // Expand root and sections
        navigationTree.expandRow(0);
        navigationTree.expandPath(new TreePath(sectionsNode.getPath()));
    }

    private void onNodeSelected() {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) navigationTree.getLastSelectedPathComponent();
        if (node == null || !(node.getUserObject() instanceof NodeInfo info)) {
            return;
        }

        switch (info.viewName) {
            case "HEADER":
                updateHeaderView();
                centerLayout.show(centerPanel, "HEADER");
                break;
            case "PALETTE":
                paletteView.update(currentStats);
                centerLayout.show(centerPanel, "PALETTE");
                break;
            case "SECTION":
                sectionView.update(info.yVal, currentStats);
                centerLayout.show(centerPanel, "SECTION");
                break;
            default:
                centerLayout.show(centerPanel, "EMPTY");
        }
    }

    private JPanel createHeaderView() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        return panel; // Placeholders populated in updateHeaderView
    }

    private void updateHeaderView() {
        headerView.removeAll();
        if (currentStats == null)
            return;

        CisFileData data = currentStats.getData();
        headerView.add(new JLabel("Version: " + data.getVersion()));
        headerView.add(Box.createVerticalStrut(10));
        headerView.add(new JLabel("Block Entities: " + data.getBlockEntityCount()));
        headerView.add(Box.createVerticalStrut(10));
        headerView.add(new JLabel("Entities: " + data.getEntityCount()));

        headerView.revalidate();
        headerView.repaint();
    }

    // Helper Record
    private record NodeInfo(String label, String viewName, int yVal) {
        @Override
        public String toString() {
            return label;
        }
    }

    // Internal Stats Panel
    private static class StatsPanel extends JPanel {
        private final JLabel diskSize = new JLabel();
        private final JLabel vanillaSize = new JLabel();
        private final JLabel ratio = new JLabel();

        public StatsPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            add(new JLabel("Disk Size:"));
            add(diskSize);
            add(Box.createVerticalStrut(10));
            add(new JLabel("Est. Vanilla Size:"));
            add(vanillaSize);
            add(Box.createVerticalStrut(10));
            add(new JLabel("Compression Ratio:"));
            add(ratio);
        }

        public void update(CisStats stats) {
            if (stats == null)
                return;

            long compressed = stats.getEntry().compressedSize();
            long rawVoxel = stats.getRawVoxelSize();

            diskSize.setText(formatBytes(compressed));
            vanillaSize.setText(formatBytes(rawVoxel)); // heuristic

            double r = (double) compressed / rawVoxel * 100.0;
            ratio.setText(String.format("%.1f%%", r));
        }

        private String formatBytes(long bytes) {
            if (bytes < 1024)
                return bytes + " B";
            return String.format("%.1f KB", bytes / 1024.0);
        }
    }
}
