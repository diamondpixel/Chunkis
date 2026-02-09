package io.liparakis.chunkis;

import net.fabricmc.api.ModInitializer;
/**
 * Common initialization for the Chunkis mod.
 * <p>
 * This class serves as the main entry point for the mod across both physical
 * client
 * and dedicated server environments (Fabric "main" entrypoint).
 * </p>
 * 
 * <h2>Responsibilities:</h2>
 * <ul>
 * <li><b>Common Registration:</b> Registers shared content like packets,
 * blocks, items, etc.</li>
 * <li><b>Server-Side Logic:</b> Handles logic that runs on both singleplayer
 * and multiplayer servers.</li>
 * </ul>
 * 
 * <p>
 * For client-specific initialization (rendering, client packet handling),
 * see {@link ClientChunkisMod}.
 * </p>
 */
public class ChunkisMod implements ModInitializer {

    @Override
    public void onInitialize() {
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C().register(
                io.liparakis.chunkis.network.ChunkDeltaPayload.ID,
                io.liparakis.chunkis.network.ChunkDeltaPayload.CODEC);
    }
}
