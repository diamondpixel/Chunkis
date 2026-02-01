package io.liparakis.cisplugin.ui;

import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import io.liparakis.cisplugin.decoder.SimpleBlockState;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.Map;

/**
 * Displays the global palette usage in a table.
 */
public class PaletteView extends JPanel {
    private final JBTable table;
    private final DefaultTableModel model;

    public PaletteView() {
        setLayout(new BorderLayout());

        String[] columns = { "#", "Block ID", "Usage Count" };
        model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        table = new JBTable(model);
        table.setAutoCreateRowSorter(true);
        add(new JBScrollPane(table), BorderLayout.CENTER);
    }

    public void update(CisStats stats) {
        model.setRowCount(0);
        if (stats == null)
            return;

        Map<Integer, Integer> usage = stats.getPaletteUsage();
        java.util.List<SimpleBlockState> palette = stats.getGlobalPalette();

        for (int i = 0; i < palette.size(); i++) {
            SimpleBlockState state = palette.get(i);
            int count = usage.getOrDefault(i, 0);
            model.addRow(new Object[] { i, state.blockName(), count });
        }
    }
}
