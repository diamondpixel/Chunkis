package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.core.CisChunkPos;
import io.liparakis.chunkis.storage.CisStorage;
import io.liparakis.chunkis.util.FabricCisStorageHelper;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Command to handle bulk migration of CIS data from older versions (CIS7)
 * to the latest format (CIS8).
 *
 * @author Liparakis
 * @version 1.0
 */
public final class MigrationCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationCommand.class);
    private static final Pattern REGION_FILE_PATTERN = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.cis");

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("chunkis")
                .requires(source -> hasPermission(source, 2))
                .then(CommandManager.literal("migrate")
                        .executes(MigrationCommand::runMigration)));
    }

    /**
     * Version-independent permission check.
     * <p>
     * Falls back to server-level permission check if hasPermissionLevel(int) is
     * missing at runtime.
     * </p>
     */
    private static boolean hasPermission(ServerCommandSource source, int level) {
        try {
            return source.hasPermissionLevel(level);
        } catch (Throwable t) {
            try {
                var server = source.getServer();
                if (server != null) {
                    var player = source.getPlayer();
                    if (player != null) {
                        // isOperator(GameProfile) is very stable across 1.21.x PlayerManager
                        return server.getPlayerManager().isOperator(player.getGameProfile());
                    }
                }
            } catch (Throwable t2) {
                // Ignore fallback failures
            }
            // Return true to prevent crashing the entire server on login
            return true;
        }
    }

    private static int runMigration(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        source.sendFeedback(() -> Text.literal("§6[Chunkis] Starting bulk migration from CIS7 to CIS8..."), true);

        // Run in a separate thread to avoid blocking the server main thread
        new Thread(() -> {
            try {
                int migratedCount = 0;
                for (ServerWorld world : source.getServer().getWorlds()) {
                    migratedCount += migrateWorld(world, source);
                }
                int finalCount = migratedCount;
                source.sendFeedback(
                        () -> Text
                                .literal("§a[Chunkis] Migration complete! Upgraded " + finalCount + " chunks to CIS8."),
                        true);
            } catch (Exception e) {
                LOGGER.error("Migration failed", e);
                source.sendFeedback(() -> Text.literal("§c[Chunkis] Migration failed! Check server logs for details."),
                        true);
            }
        }, "Chunkis-Migration-Thread").start();

        return 1;
    }

    private static int migrateWorld(ServerWorld world, ServerCommandSource source) {
        String dimId = world.getRegistryKey().getValue().toString();
        source.sendFeedback(() -> Text.literal("§7Migrating dimension: " + dimId + "..."), false);

        Path storageDir = getStorageDir(world);
        if (!Files.exists(storageDir))
            return 0;

        CisStorage<?, ?, ?, ?> storage = FabricCisStorageHelper.getStorage(world);
        int migratedInDim = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageDir, "r.*.*.cis")) {
            for (Path path : stream) {
                Matcher matcher = REGION_FILE_PATTERN.matcher(path.getFileName().toString());
                if (matcher.matches()) {
                    int rx = Integer.parseInt(matcher.group(1));
                    int rz = Integer.parseInt(matcher.group(2));
                    migratedInDim += migrateRegion(storage, rx, rz);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to iterate storage directory for {}", dimId, e);
        }

        return migratedInDim;
    }

    @SuppressWarnings({ "rawtypes" })
    private static int migrateRegion(CisStorage<?, ?, ?, ?> storage, int rx, int rz) {
        int migratedCount = 0;
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                CisChunkPos pos = new CisChunkPos((rx << 5) + x, (rz << 5) + z);
                ChunkDelta<?, ?> delta = storage.load(pos);
                if (delta != null && !delta.isEmpty() && delta.needsMigration()) {
                    ((CisStorage) storage).save(pos, delta);
                    migratedCount++;
                }
            }
        }
        return migratedCount;
    }

    private static Path getStorageDir(ServerWorld world) {
        Path baseDir = world.getServer().getSavePath(WorldSavePath.ROOT);
        String dimId = world.getRegistryKey().getValue().getPath();

        if (!"overworld".equals(dimId)) {
            String namespace = world.getRegistryKey().getValue().getNamespace();
            baseDir = baseDir.resolve("dimensions").resolve(namespace).resolve(dimId);
        }

        return baseDir.resolve("chunkis").resolve("regions");
    }
}
