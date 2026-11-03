package io.github.xqpeppa.architect;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Village Architect: villagers without a bed will look for a flat open area, walk there,
 * attract a mason, and the pair will place a real village house structure.
 */
public class ArchMod implements ModInitializer {
    public static final String MOD_ID = "villagearchitect";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public static Config CONFIG = new Config();

    @Override
    public void onInitialize() {
        CONFIG = Config.load();
        ServerTickEvents.END_SERVER_TICK.register(ArchitectManager::onEndTick);
        UseEntityCallback.EVENT.register(ArchMod::onUseEntity);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("villagearchitect")
                        .requires(source -> source.hasPermissionLevel(2))
                        .then(CommandManager.literal("status")
                                .executes(ArchitectManager::printStatus))));
        LOG.info("[Village Architect] loaded (building={}, gifts={}, openSpace={}x{})",
                CONFIG.enableBuilding, CONFIG.enableGifts, CONFIG.openSpaceSize, CONFIG.openSpaceSize);
    }

    /** Sneak + right click a villager to ask it what it is currently doing. */
    private static ActionResult onUseEntity(PlayerEntity player, World world, Hand hand, Entity entity,
                                            EntityHitResult hitResult) {
        if (world.isClient() || hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }
        if (!(entity instanceof VillagerEntity villager) || !player.isSneaking()) {
            return ActionResult.PASS;
        }
        if (player instanceof ServerPlayerEntity serverPlayer) {
            ArchitectManager.reportStatus(serverPlayer, villager);
            return ActionResult.SUCCESS;
        }
        return ActionResult.PASS;
    }
}
