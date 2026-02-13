package io.liparakis.cisplugin;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBList;
import io.liparakis.cisplugin.decoder.*;
import io.liparakis.cisplugin.ui.CisInspector;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import java.awt.*;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.util.List;

/**
 * Custom file editor for CIS Region files.
 * Provides a split view:
 * - Left: Region Chunk List
 * - Right: Chunkis Inspector (Detailed View)
 */
public final class CisFileEditor extends UserDataHolderBase implements FileEditor {
    private final VirtualFile file;
    private final JPanel mainPanel;

    // Components
    private final JBList<RegionFileReader.ChunkEntry> chunkList;
    private final DefaultListModel<RegionFileReader.ChunkEntry> chunkListModel;
    private final CisInspector inspector;
    private final JTextArea statusArea;

    // Logic
    private RegionFileReader regionReader;
    private MappingLoader mappingLoader;

    public CisFileEditor(@NotNull VirtualFile file) {
        this.file = file;
        this.mainPanel = new JPanel(new BorderLayout());

        // 1. Chunk List (Left)
        this.chunkListModel = new DefaultListModel<>();
        this.chunkList = new JBList<>(chunkListModel);
        this.chunkList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        this.chunkList.addListSelectionListener(this::onChunkSelected);

        JPanel listPanel = new JPanel(new BorderLayout());
        listPanel.setBorder(BorderFactory.createTitledBorder("Region Chunks"));
        listPanel.add(new JScrollPane(chunkList), BorderLayout.CENTER);

        // 2. Inspector (Right)
        this.inspector = new CisInspector();

        // 3. Splitter
        JBSplitter splitter = new JBSplitter(false, 0.2f);
        splitter.setFirstComponent(listPanel);
        splitter.setSecondComponent(inspector);

        // 4. Status Bar
        this.statusArea = new JTextArea(1, 40);
        this.statusArea.setEditable(false);
        this.statusArea.setBackground(UIManager.getColor("Panel.background"));
        this.statusArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        mainPanel.add(splitter, BorderLayout.CENTER);
        mainPanel.add(statusArea, BorderLayout.SOUTH);

        // Load Data
        loadMappings();
        loadFile();
    }

    private void loadMappings() {
        try {
            VirtualFile parent = file.getParent(); // regions
            if (parent != null) {
                VirtualFile grandParent = parent.getParent(); // chunkis
                if (grandParent != null) {
                    VirtualFile mappingFile = grandParent.findChild("global_ids.json");
                    if (mappingFile != null) {
                        mappingLoader = new MappingLoader();
                        mappingLoader.load(new File(mappingFile.getPath()));
                        setStatus("Mappings loaded from: " + mappingFile.getPath());
                    }
                }
            }
        } catch (Exception e) {
            setStatus("Failed to load mappings: " + e.getMessage());
        }
    }

    private void loadFile() {
        try {
            byte[] data = file.contentsToByteArray();
            regionReader = new RegionFileReader(data);
            List<RegionFileReader.ChunkEntry> chunks = regionReader.getChunks();

            chunkListModel.clear();
            for (RegionFileReader.ChunkEntry chunk : chunks) {
                chunkListModel.addElement(chunk);
            }

            setStatus("Region file loaded: " + chunks.size() + " chunks.");

            // Auto-select first chunk
            if (!chunks.isEmpty()) {
                chunkList.setSelectedIndex(0);
            }
        } catch (Exception e) {
            setStatus("Error: " + e.getMessage());
        }
    }

    private void onChunkSelected(ListSelectionEvent e) {
        if (e.getValueIsAdjusting())
            return;

        RegionFileReader.ChunkEntry selected = chunkList.getSelectedValue();
        if (selected == null || regionReader == null)
            return;

        try {
            long startTime = System.nanoTime();
            byte[] chunkData = regionReader.readChunk(selected.index());

            StandaloneCisDecoder decoder = new StandaloneCisDecoder(mappingLoader);
            CisFileData fileData = decoder.decode(chunkData);

            long endTime = System.nanoTime();
            double ms = (endTime - startTime) / 1_000_000.0;

            inspector.setData(fileData, selected);

            setStatus(String.format("Decoded chunk [%d, %d] in %.2f ms", selected.localX(), selected.localZ(), ms));
        } catch (Exception ex) {
            setStatus("Decode Failed: " + ex.getMessage());
        }
    }

    private void setStatus(String message) {
        statusArea.setText(" " + message);
    }

    @Override
    public @NotNull JComponent getComponent() {
        return mainPanel;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return chunkList;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) @NotNull String getName() {
        return "Chunkis Inspector";
    }

    @Override
    public void setState(@NotNull FileEditorState state) {
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public boolean isValid() {
        return file.isValid();
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
    }

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
    }

    @Override
    public void dispose() {
    }

    @Override
    public @Nullable VirtualFile getFile() {
        return file;
    }
}
