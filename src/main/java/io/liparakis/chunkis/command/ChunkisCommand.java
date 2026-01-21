package io.liparakis.chunkis.command;

import com.mojang.brigadier.CommandDispatcher;
import io.liparakis.chunkis.core.ChunkDelta;
import io.liparakis.chunkis.storage.CisStorage;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;

import static net.minecraft.server.command.CommandManager.literal;

public class ChunkisCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("chunkis")
                .then(literal("inspect")
                        .executes(context -> executeInspect(context, false))
                        .then(literal("dump")
                                .executes(context -> executeInspect(context, true)))));
    }

    private static int executeInspect(com.mojang.brigadier.context.CommandContext<ServerCommandSource> context,
            boolean detailed) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        if (player == null) {
            source.sendError(Text.literal("This command must be executed by a player!"));
            return 0;
        }

        ChunkPos pos = player.getChunkPos();
        ServerWorld world = player.getServerWorld();

        CisStorage storage = new CisStorage(world);
        ChunkDelta delta = storage.load(pos);
        int count = delta.size();
        source.sendFeedback(
                () -> Text.literal("CIS Inspect: Chunk " + pos + " has " + count + " persistent block changes."),
                false);

        return 1;
    }
}
