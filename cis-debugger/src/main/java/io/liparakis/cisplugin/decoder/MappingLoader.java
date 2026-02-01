package io.liparakis.cisplugin.decoder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads block mapping from global_ids.json.
 * Format: {"minecraft:stone":1,"minecraft:air":0}
 */
public final class MappingLoader {
    private final Map<Integer, String> idToName = new HashMap<>();

    public void load(File mappingFile) throws IOException {
        if (!mappingFile.exists())
            return;

        String content = Files.readString(mappingFile.toPath());
        parseJson(content);
    }

    public String getName(int id) {
        return idToName.getOrDefault(id, "unknown:" + id);
    }

    // Simple regex parser to avoid adding JSON dependency
    private void parseJson(String content) {
        // Remove braces
        content = content.trim();
        if (content.startsWith("{"))
            content = content.substring(1);
        if (content.endsWith("}"))
            content = content.substring(0, content.length() - 1);

        String[] entries = content.split(",");
        Pattern pattern = Pattern.compile("\"(.+)\":(\\d+)");

        for (String entry : entries) {
            Matcher m = pattern.matcher(entry);
            if (m.find()) {
                String name = m.group(1);
                int id = Integer.parseInt(m.group(2));
                idToName.put(id, name);
            }
        }
    }
}
