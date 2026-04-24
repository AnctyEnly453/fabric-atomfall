package com.codex.atomfall;

import com.codex.atomfall.registry.ModMobEffects;
import com.codex.atomfall.registry.ModBlockEntities;
import com.codex.atomfall.registry.ModBlocks;
import com.codex.atomfall.registry.ModCreativeTabs;
import com.codex.atomfall.registry.ModEntities;
import com.codex.atomfall.registry.ModItems;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AtomfallMod implements ModInitializer {
    public static final String MODID = "atomfall";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    @Override
    public void onInitialize() {
        ModItems.init();
        ModBlocks.init();
        ModEntities.init();
        ModBlockEntities.init();
        ModMobEffects.init();
        ModCreativeTabs.init();
        AtomfallCommands.register();
        AtomfallEvents.register();
        LOGGER.info("Atomfall initialized for Fabric 1.21.11");
    }
}
