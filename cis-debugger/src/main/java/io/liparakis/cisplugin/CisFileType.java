package io.liparakis.cisplugin;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.openapi.util.NlsSafe;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

/**
 * File type definition for .cis files.
 */
public final class CisFileType implements FileType {

    private CisFileType() {
    }

    @Override
    public @NonNls @NotNull String getName() {
        return "CIS File";
    }

    @Override
    public @NlsContexts.Label @NotNull String getDescription() {
        return "Chunkis CIS storage file (V7)";
    }

    @Override
    public @NlsSafe @NotNull String getDefaultExtension() {
        return "cis";
    }

    @Override
    public Icon getIcon() {
        return null; // Use default icon
    }

    @Override
    public boolean isBinary() {
        return true;
    }
}
