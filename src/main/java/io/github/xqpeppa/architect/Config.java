package io.github.xqpeppa.architect;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** JSON config at config/villagearchitect.json. */
public final class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Tick interval between AI updates for each world. */
    public int intervalTicks = 10;

    /** Only villagers within this distance of a player are considered (perf). */
    public int villagerScanRadius = 48;

    /** Horizontal radius searched for a building site around the villager. */
    public int searchRadius = 20;

    /** Required flat footprint and headroom, in blocks (user default: 16). */
    public int openSpaceSize = 16;

    /** Two builders within this distance count as a group. */
    public int buildClusterRadius = 16;

    /** A mason must be within this distance of the building villager. */
    public int masonRadius = 8;

    /** Masons are attracted from this distance. */
    public int attractRadius = 24;

    /** Player must be within this distance to receive the gift. */
    public int giftRadius = 8;

    /** Emerald amount handed over once. */
    public int giftAmount = 8;

    /** Ticks a villager waits before searching again. */
    public int searchCooldownTicks = 400;

    /** Ticks before the same spot may be built on again (0 = no cooldown at all). */
    public int buildCooldownTicks = 0;

    /** Minimum number of empty blocks between two houses built by the mod. */
    public int minHouseGap = 2;

    /** Ticks the two builders + mason stand at the plot before the building is placed. */
    public int buildDelayTicks = 60;

    public boolean enableBuilding = true;
    public boolean enableMasonAttraction = true;
    public boolean enableGifts = true;

    public static Config load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("villagearchitect.json");
        try {
            if (Files.exists(path)) {
                String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                Config loaded = GSON.fromJson(json, Config.class);
                if (loaded != null) {
                    loaded.clamp();
                    return loaded;
                }
            }
        } catch (IOException | JsonSyntaxException e) {
            ArchMod.LOG.warn("[Village Architect] could not read config, using defaults", e);
        }
        Config fresh = new Config();
        fresh.clamp();
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, GSON.toJson(fresh).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            ArchMod.LOG.warn("[Village Architect] could not write default config", e);
        }
        return fresh;
    }

    private void clamp() {
        intervalTicks = Math.max(1, intervalTicks);
        villagerScanRadius = clamp(villagerScanRadius, 8, 128);
        searchRadius = clamp(searchRadius, 4, 48);
        openSpaceSize = clamp(openSpaceSize, 8, 32);
        buildClusterRadius = clamp(buildClusterRadius, 4, 64);
        masonRadius = clamp(masonRadius, 1, 32);
        attractRadius = clamp(attractRadius, masonRadius, 64);
        giftRadius = clamp(giftRadius, 1, 32);
        giftAmount = clamp(giftAmount, 1, 64);
        searchCooldownTicks = clamp(searchCooldownTicks, 20, 12000);
        buildCooldownTicks = clamp(buildCooldownTicks, 0, 240000);
        minHouseGap = clamp(minHouseGap, 0, 16);
        buildDelayTicks = clamp(buildDelayTicks, 1, 1200);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
