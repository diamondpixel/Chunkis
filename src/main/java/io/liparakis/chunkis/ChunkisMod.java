package io.liparakis.chunkis;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChunkisMod implements ModInitializer {
    public static final String MOD_ID = "chunkis";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Hello Fabric world from Chunkis!");

        // Register Commands
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT
                .register((dispatcher, registryAccess, environment) -> {
                    io.liparakis.chunkis.command.ChunkisCommand.register(dispatcher);
                });
    }
}
