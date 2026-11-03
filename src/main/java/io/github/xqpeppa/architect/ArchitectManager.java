package io.github.xqpeppa.architect;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.MemoryModuleType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.GlobalPos;
import net.minecraft.village.VillagerDataContainer;
import net.minecraft.world.World;
import net.minecraft.world.entity.EntityLike;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The villager AI itself. Villagers use a Brain (not a GoalSelector), so we drive them from
 * the server tick event: no mixin required and nothing has to be installed on the client.
 */
public final class ArchitectManager {
    private static final Identifier MASON_ID = Identifier.of("minecraft", "mason");
    private static final String TAG_PENDING = "va_pending";
    private static final String TAG_GIFTED = "va_gifted";

    private static final Map<UUID, Builder> BUILDERS = new HashMap<>();
    private static final Map<UUID, UUID> MASON_TO_BUILDER = new HashMap<>();
    private static final Set<UUID> GIFT_PENDING = new HashSet<>();
    private static final Map<RegistryKey<World>, List<BuildMark>> RECENT_BUILDS = new HashMap<>();
    /** Every house the mod has built, keyed by dimension id, so new ones keep their distance. */
    private static final Map<String, List<BuiltHouse>> HOUSES = new HashMap<>();
    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder().create();

    private static int searchesThisInterval;
    private static boolean housesLoaded;

    private ArchitectManager() {
    }

    private static final class Builder {
        BlockPos site;
        StructurePlacer.Plan plan;
        long siteSince;
        long nextSearchTick;
        long nextWanderTick;
        boolean standing;
        long buildingSince;
        UUID mason;
    }

    private record BuildMark(BlockPos pos, long expire) {
    }

    /** What a finished building is: a plain home (free or already paired) or a work place. */
    enum HouseKind {
        HOME, HOME_BOUND, WORKSHOP
    }

    private record BuiltHouse(HouseBox box, HouseKind kind) {
    }

    /** The two oldest homes that do not have a work place yet, or null when there are fewer than two. */
    private static List<BuiltHouse> unpairedPair(ServerWorld world) {
        List<BuiltHouse> pair = new ArrayList<>(2);
        for (BuiltHouse house : housesIn(world)) {
            if (house.kind() == HouseKind.HOME) {
                pair.add(house);
                if (pair.size() == 2) {
                    return pair;
                }
            }
        }
        return null;
    }

    private static BlockPos pairCenter(List<BuiltHouse> pair) {
        HouseBox a = pair.get(0).box();
        HouseBox b = pair.get(1).box();
        return new BlockPos(
                (a.minX() + a.maxX() + b.minX() + b.maxX()) / 4,
                (a.minY() + b.minY()) / 2,
                (a.minZ() + a.maxZ() + b.minZ() + b.maxZ()) / 4);
    }

    private static List<HouseBox> boxesOf(List<BuiltHouse> houses) {
        List<HouseBox> boxes = new ArrayList<>(houses.size());
        for (BuiltHouse house : houses) {
            boxes.add(house.box());
        }
        return boxes;
    }

    public static void onEndTick(MinecraftServer server) {
        Config cfg = ArchMod.CONFIG;
        int interval = Math.max(1, cfg.intervalTicks);
        for (ServerWorld world : server.getWorlds()) {
            if (world.getTime() % interval != 0L) {
                continue;
            }
            List<ServerPlayerEntity> players = world.getPlayers(player -> true);
            if (players.isEmpty()) {
                continue;
            }
            List<BuildMark> marks = RECENT_BUILDS.computeIfAbsent(world.getRegistryKey(), key -> new ArrayList<>());
            long now = world.getTime();
            marks.removeIf(mark -> mark.expire() < now);

            searchesThisInterval = 0;
            List<VillagerEntity> villagers = collect(world, players, cfg.villagerScanRadius);
            if (villagers.isEmpty()) {
                continue;
            }
            for (VillagerEntity villager : villagers) {
                try {
                    tickVillager(world, villager, villagers, players, cfg);
                } catch (RuntimeException e) {
                    ArchMod.LOG.debug("[Village Architect] villager tick failed", e);
                }
            }
            try {
                tryBuild(world, villagers, cfg);
            } catch (RuntimeException e) {
                ArchMod.LOG.debug("[Village Architect] build attempt failed", e);
            }
            try {
                tickFreeMasons(villagers, cfg);
            } catch (RuntimeException e) {
                ArchMod.LOG.debug("[Village Architect] mason tick failed", e);
            }
        }
    }

    private static List<VillagerEntity> collect(ServerWorld world, List<ServerPlayerEntity> players, int radius) {
        Set<VillagerEntity> found = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ServerPlayerEntity player : players) {
            Box box = new Box(
                    player.getX() - radius, player.getY() - 32, player.getZ() - radius,
                    player.getX() + radius, player.getY() + 32, player.getZ() + radius);
            found.addAll(world.getEntitiesByClass(VillagerEntity.class, box, Entity::isAlive));
        }
        return new ArrayList<>(found);
    }

    private static void tickVillager(ServerWorld world, VillagerEntity villager, List<VillagerEntity> villagers,
                                     List<ServerPlayerEntity> players, Config cfg) {
        if (villager.isBaby()) {
            forget(uuidOf(villager));
            return;
        }
        if (cfg.enableGifts && giftTick(world, villager, players, cfg)) {
            return;
        }
        if (!cfg.enableBuilding) {
            return;
        }
        if (hasBed(villager)) {
            forget(uuidOf(villager));
            return;
        }

        Builder builder = BUILDERS.computeIfAbsent(uuidOf(villager), id -> new Builder());
        if (builder.site == null) {
            if (world.getTime() >= builder.nextSearchTick && searchesThisInterval < 4) {
                searchesThisInterval++;
                builder.nextSearchTick = world.getTime() + Math.max(40, cfg.searchCooldownTicks);
                // The village rule: every two finished homes earn one work place (random profession).
                // While such a pair is waiting, builders put up the work place next to those two homes
                // instead of growing another plain house.
                List<BuiltHouse> pair = unpairedPair(world);
                boolean workshop = pair != null;
                BlockPos here = preferredCenter(villager, villagers);
                // The work place belongs next to its two homes, so that area is tried first; when it is
                // already full, the builder falls back to its own surroundings instead of giving up.
                boolean found = false;
                if (workshop) {
                    found = tryFindSite(world, builder, pairCenter(pair), cfg, true);
                }
                if (!found) {
                    found = tryFindSite(world, builder, here, cfg, workshop);
                }
                if (!found && workshop) {
                    // Still no room for a work place anywhere: keep the village growing with a normal
                    // house, the pair stays pending and gets another chance later.
                    found = tryFindSite(world, builder, here, cfg, false);
                }
                if (builder.site == null) {
                    builder.plan = null;
                    // Nothing suitable here (a freshly built house takes up the flat ground), so walk
                    // somewhere else and look again instead of standing around forever.
                    wander(villager, builder, world);
                    builder.nextSearchTick = world.getTime() + 120L;
                    return;
                }
                builder.siteSince = world.getTime();
                builder.standing = false;
                builder.buildingSince = 0L;
                ArchMod.LOG.debug("[Village Architect] villager found site {} for {}",
                        builder.site, builder.plan.id());
            } else {
                return;
            }
        }

        BlockPos site = builder.site;
        double dist2 = villager.squaredDistanceTo(site.getX() + 0.5, site.getY(), site.getZ() + 0.5);
        if (dist2 > 9.0) {
            builder.standing = false;
            if (villager.getNavigation().isIdle()) {
                villager.getNavigation().startMovingTo(site.getX() + 0.5, site.getY(), site.getZ() + 0.5, 0.6);
            }
        } else {
            builder.standing = true;
            villager.getNavigation().stop();
            if (world.getTime() - builder.siteSince > 600L && !hasPartner(villager, builder, villagers, cfg)) {
                // No second bedless villager joined in time: drop this spot and look again.
                builder.site = null;
                builder.standing = false;
                releaseMason(builder.mason);
                builder.mason = null;
                builder.nextSearchTick = world.getTime() + Math.max(40, cfg.searchCooldownTicks);
            }
        }

        // A villager that is on its way to a building site already "needs a house", so the mason is
        // pulled along from the very start instead of only after the villager has arrived.
        if (cfg.enableMasonAttraction && builder.site != null) {
            attractMason(villager, builder, villagers, cfg);
        }
    }

    /**
     * Walks a villager that could not find a spot towards some open land (preferably away from the
     * house that was just finished), so a crowd of villagers keeps spreading out instead of piling
     * up around the one house that already stands there.
     */
    private static void wander(VillagerEntity villager, Builder builder, ServerWorld world) {
        long now = world.getTime();
        if (now < builder.nextWanderTick) {
            return;
        }
        builder.nextWanderTick = now + 160L;
        BlockPos origin = posOf(villager);
        double angle = Math.random() * Math.PI * 2.0;

        List<BuildMark> marks = RECENT_BUILDS.get(world.getRegistryKey());
        if (marks != null) {
            for (BuildMark mark : marks) {
                double dx = origin.getX() - mark.pos().getX();
                double dz = origin.getZ() - mark.pos().getZ();
                double d2 = dx * dx + dz * dz;
                if (d2 > 0.25 && d2 < 24.0 * 24.0) {
                    angle = Math.atan2(dz, dx);
                    break;
                }
            }
        }

        int dist = 14 + (int) (Math.random() * 13.0);
        int tx = origin.getX() + (int) Math.round(Math.cos(angle) * dist);
        int tz = origin.getZ() + (int) Math.round(Math.sin(angle) * dist);
        int ground = OpenSpaceFinder.findGroundY(world, tx, tz, origin.getY() + 8, origin.getY() - 8);
        int ty = ground == Integer.MIN_VALUE ? origin.getY() : ground + 1;
        villager.getNavigation().startMovingTo(tx + 0.5, ty, tz + 0.5, 0.55);
    }

    /**
     * A mason that just finished a house (or is simply free) immediately walks to the closest
     * villager that is waiting to build, so no cooldown is ever felt between two houses.
     */
    private static void tickFreeMasons(List<VillagerEntity> villagers, Config cfg) {
        double range2 = (double) cfg.attractRadius * cfg.attractRadius;
        for (VillagerEntity mason : villagers) {
            if (!isMason(mason) || MASON_TO_BUILDER.containsKey(uuidOf(mason))) {
                continue;
            }
            UUID masonId = uuidOf(mason);
            VillagerEntity target = null;
            // First look close by, then take the closest waiting villager anywhere in the world, so a
            // mason never idles after finishing a house that was far away from the next plot.
            for (int pass = 0; pass < 2 && target == null; pass++) {
                double bestDist = pass == 0 ? range2 : Double.MAX_VALUE;
                for (VillagerEntity other : villagers) {
                    if (other == mason) {
                        continue;
                    }
                    Builder builder = BUILDERS.get(uuidOf(other));
                    if (builder == null || builder.site == null || builder.mason != null) {
                        continue;
                    }
                    double dist = other.squaredDistanceTo(mason);
                    if (dist < bestDist) {
                        bestDist = dist;
                        target = other;
                    }
                }
            }
            if (target == null) {
                continue;
            }
            Builder targetBuilder = BUILDERS.get(uuidOf(target));
            if (targetBuilder == null) {
                continue;
            }
            targetBuilder.mason = masonId;
            MASON_TO_BUILDER.put(masonId, uuidOf(target));
            if (mason.getNavigation().isIdle()) {
                mason.getNavigation().startMovingTo(target, 0.55);
            }
        }
    }

    /**
     * When another bedless villager already picked a spot nearby, search around that spot so the
     * builders end up next to each other (the "two villagers" rule) instead of 20 blocks apart.
     */
    /** Looks for a spot for one building of the given kind around {@code center}. */
    private static boolean tryFindSite(ServerWorld world, Builder builder, BlockPos center, Config cfg,
                                       boolean workshop) {
        for (int attempt = 0; attempt < 2 && builder.site == null; attempt++) {
            StructurePlacer.Plan plan = StructurePlacer.pick(world, center, world.getRandom(), workshop);
            if (plan == null) {
                return false;
            }
            BlockPos site = GroundProbe.findAnchor(world, center, cfg.searchRadius,
                    plan.sizeX(), plan.sizeY(), plan.sizeZ(), boxesOf(housesIn(world)), cfg.minHouseGap);
            if (site != null) {
                builder.plan = plan;
                builder.site = site;
            }
        }
        return builder.site != null;
    }
    private static BlockPos preferredCenter(VillagerEntity villager, List<VillagerEntity> villagers) {
        BlockPos best = null;
        double bestDist = 32.0 * 32.0;
        for (VillagerEntity other : villagers) {
            if (other == villager) {
                continue;
            }
            Builder otherBuilder = BUILDERS.get(uuidOf(other));
            if (otherBuilder == null || otherBuilder.site == null) {
                continue;
            }
            double dist = other.squaredDistanceTo(villager);
            if (dist < bestDist) {
                bestDist = dist;
                best = otherBuilder.site;
            }
        }
        return best == null ? posOf(villager) : best;
    }

    private static boolean hasPartner(VillagerEntity villager, Builder builder,
                                      List<VillagerEntity> villagers, Config cfg) {
        if (builder.site == null) {
            return false;
        }
        double cluster2 = (double) cfg.buildClusterRadius * cfg.buildClusterRadius;
        for (VillagerEntity other : villagers) {
            if (other == villager) {
                continue;
            }
            Builder otherBuilder = BUILDERS.get(uuidOf(other));
            if (otherBuilder != null && otherBuilder.standing && otherBuilder.site != null
                    && otherBuilder.site.getSquaredDistance(builder.site) <= cluster2) {
                return true;
            }
        }
        return false;
    }

    private static void attractMason(VillagerEntity builderVillager, Builder builder,
                                     List<VillagerEntity> villagers, Config cfg) {
        if (builder.mason != null) {
            VillagerEntity mason = findById(villagers, builder.mason);
            if (mason == null || !isMason(mason)) {
                releaseMason(builder.mason);
                builder.mason = null;
            } else {
                if (mason.squaredDistanceTo(builderVillager) > 64.0) {
                    mason.getNavigation().startMovingTo(builderVillager, 0.55);
                }
                return;
            }
        }

        double bestDist = (double) cfg.attractRadius * cfg.attractRadius;
        VillagerEntity best = null;
        for (VillagerEntity other : villagers) {
            if (other == builderVillager || !isMason(other)) {
                continue;
            }
            if (MASON_TO_BUILDER.containsKey(uuidOf(other))) {
                continue;
            }
            double dist = other.squaredDistanceTo(builderVillager);
            if (dist < bestDist) {
                bestDist = dist;
                best = other;
            }
        }
        if (best != null) {
            MASON_TO_BUILDER.put(uuidOf(best), uuidOf(builderVillager));
            builder.mason = uuidOf(best);
            best.getNavigation().startMovingTo(builderVillager, 0.55);
            ArchMod.LOG.debug("[Village Architect] mason attracted to builder");
        }
    }

    private static void tryBuild(ServerWorld world, List<VillagerEntity> villagers, Config cfg) {
        if (!cfg.enableBuilding) {
            return;
        }
        double cluster2 = (double) cfg.buildClusterRadius * cfg.buildClusterRadius;
        double mason2 = (double) cfg.masonRadius * cfg.masonRadius;

        for (VillagerEntity builderVillager : villagers) {
            Builder builder = BUILDERS.get(uuidOf(builderVillager));
            if (builder == null || builder.site == null || !builder.standing) {
                continue;
            }
            if (isMarked(world, builder.site, cfg)) {
                continue;
            }

            VillagerEntity partner = null;
            for (VillagerEntity other : villagers) {
                if (other == builderVillager) {
                    continue;
                }
                Builder otherBuilder = BUILDERS.get(uuidOf(other));
                if (otherBuilder == null || otherBuilder.site == null || !otherBuilder.standing) {
                    continue;
                }
                if (otherBuilder.site.getSquaredDistance(builder.site) <= cluster2) {
                    partner = other;
                    break;
                }
            }
            if (partner == null) {
                continue;
            }

            VillagerEntity mason = builder.mason == null ? null : findById(villagers, builder.mason);
            if (mason == null || mason.squaredDistanceTo(builderVillager) > mason2 || builder.plan == null) {
                builder.buildingSince = 0L;
                continue;
            }

            // The pair stands at the plot for a moment first, so a building does not pop into the
            // world the very tick the mason arrives.
            long now = world.getTime();
            if (builder.buildingSince == 0L) {
                builder.buildingSince = now;
                continue;
            }
            if (now - builder.buildingSince < Math.max(1, cfg.buildDelayTicks)) {
                continue;
            }

            // Get everybody out of the footprint BEFORE the blocks go down. A villager standing
            // inside gets pushed upwards by the walls/floor and lands on the roof, which is the
            // "villager on the roof" bug - so the builders are walked out first and the house is
            // only placed on an empty plot.
            HouseBox planned = StructurePlacer.boxOf(builder.site, builder.plan);
            moveEveryoneOut(world, planned);

            HouseBox box = StructurePlacer.place(world, builder.site, builder.plan, world.getRandom(),
                    boxesOf(housesIn(world)), cfg.minHouseGap);
            if (box != null) {
                housesIn(world).add(new BuiltHouse(box,
                        builder.plan.workshop() ? HouseKind.WORKSHOP : HouseKind.HOME));
                if (builder.plan.workshop()) {
                    bindPair(world);
                }
                saveHouses();
                RECENT_BUILDS.computeIfAbsent(world.getRegistryKey(), key -> new ArrayList<>())
                        .add(new BuildMark(builder.site, world.getTime() + cfg.buildCooldownTicks));
                ArchMod.LOG.info("[Village Architect] {} placed at {}", builder.plan.id(), builder.site);
                // Safety net: in case the template was bigger than the plan predicted, sweep the
                // real box once more so nobody is left inside the finished house.
                moveEveryoneOut(world, box);
                forget(uuidOf(builderVillager));
                forget(uuidOf(partner));
                clearSitesNear(world, builder.site, villagers);
                releaseMason(uuidOf(mason));
                builderVillager.getNavigation().stop();
                partner.getNavigation().stop();
                mason.getNavigation().stop();
                return;
            }
            // No template fits here without touching an existing house: drop the spot and look again.
            builder.site = null;
            builder.plan = null;
            builder.standing = false;
            builder.buildingSince = 0L;
            builder.nextSearchTick = world.getTime();
            releaseMason(builder.mason);
            builder.mason = null;
        }
    }

    /**
     * Walks every villager that stands inside (or on top of) the plot out to open ground next to it.
     * Used right before a house is placed, so the walls never grow around somebody.
     */
    private static void moveEveryoneOut(ServerWorld world, HouseBox box) {
        Box area = new Box(
                box.minX() - 2.0, box.minY() - 2.0, box.minZ() - 2.0,
                box.maxX() + 2.0, box.maxY() + 3.0, box.maxZ() + 2.0);
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, area, living -> true)) {
            moveOutside(world, villager, box);
        }
    }

    /** Moves one entity to the first free column just outside the house, if it is inside it. */
    private static void moveOutside(ServerWorld world, Entity entity, HouseBox box) {
        double x = entity.getX();
        double z = entity.getZ();
        double y = entity.getY();
        boolean inside = x >= box.minX() - 0.3 && x <= box.maxX() + 1.3
                && z >= box.minZ() - 0.3 && z <= box.maxZ() + 1.3
                && y >= box.minY() - 1.5 && y <= box.maxY() + 2.5;
        if (!inside) {
            return;
        }

        int groundY = box.minY();
        int cx = (box.minX() + box.maxX()) / 2;
        int cz = (box.minZ() + box.maxZ()) / 2;
        int reach = Math.max(box.maxX() - box.minX(), box.maxZ() - box.minZ()) / 2 + 6;
        for (int r = 2; r <= reach; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int tx = cx + dx;
                    int tz = cz + dz;
                    if (tx >= box.minX() - 1 && tx <= box.maxX() + 1 && tz >= box.minZ() - 1 && tz <= box.maxZ() + 1) {
                        continue;
                    }
                    int feetY = standY(world, tx, tz, groundY);
                    if (feetY == Integer.MIN_VALUE) {
                        continue;
                    }
                    entity.requestTeleport(tx + 0.5, feetY, tz + 0.5);
                    return;
                }
            }
        }
    }

    /** Lowest y in a column where a villager can stand (two air blocks over solid ground), or MIN_VALUE. */
    private static int standY(ServerWorld world, int x, int z, int groundY) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int y = groundY + 1; y >= groundY - 4; y--) {
            pos.set(x, y, z);
            if (!world.getBlockState(pos).isAir()) {
                continue;
            }
            pos.set(x, y + 1, z);
            if (!world.getBlockState(pos).isAir()) {
                continue;
            }
            pos.set(x, y - 1, z);
            if (world.getBlockState(pos).isSolidBlock(world, pos)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static boolean giftTick(ServerWorld world, VillagerEntity villager,
                                    List<ServerPlayerEntity> players, Config cfg) {
        UUID id = uuidOf(villager);
        Set<String> tags = villager.getCommandTags();
        boolean pending = GIFT_PENDING.contains(id) || tags.contains(TAG_PENDING);
        if (!pending && tags.contains(TAG_GIFTED)) {
            return false;
        }

        Optional<GlobalPos> home = villager.getBrain().getOptionalMemory(MemoryModuleType.HOME);
        if (home.isEmpty()) {
            GIFT_PENDING.remove(id);
            return false;
        }
        GlobalPos bed = home.get();
        if (!bed.dimension().equals(world.getRegistryKey())) {
            return false;
        }
        if (!OpenSpaceFinder.isOutdoor(world, bed.pos())) {
            return false;
        }

        if (!pending) {
            if (!villager.isSleeping()) {
                return false;
            }
            GIFT_PENDING.add(id);
            villager.addCommandTag(TAG_PENDING);
            return true;
        }

        ServerPlayerEntity target = null;
        double best = (double) cfg.giftRadius * cfg.giftRadius;
        for (ServerPlayerEntity player : players) {
            double dist = player.squaredDistanceTo(villager);
            if (dist <= best) {
                best = dist;
                target = player;
            }
        }
        if (target == null) {
            return true;
        }
        if (best <= 9.0) {
            target.getInventory().offerOrDrop(new ItemStack(Items.EMERALD, cfg.giftAmount));
            villager.removeCommandTag(TAG_PENDING);
            villager.addCommandTag(TAG_GIFTED);
            GIFT_PENDING.remove(id);
            target.sendMessage(Text.literal("村民感激地塞给你 " + cfg.giftAmount + " 颗绿宝石。"), false);
        } else {
            villager.getNavigation().startMovingTo(target, 0.6);
        }
        return true;
    }

    /** Sneak + right click feedback: tells the player what this villager is up to. */
    public static void reportStatus(ServerPlayerEntity player, VillagerEntity villager) {
        Config cfg = ArchMod.CONFIG;
        if (villager.isBaby()) {
            send(player, "§e这是一只小村民§7，长大后才会参与建筑。");
            return;
        }
        if (!cfg.enableBuilding) {
            send(player, "§c建筑功能已在配置文件中关闭。");
            return;
        }
        if (hasBed(villager)) {
            send(player, "§7这只村民已绑定床§7，不会去盖房子。");
            return;
        }
        Builder builder = BUILDERS.get(uuidOf(villager));
        if (builder == null || builder.site == null) {
            send(player, "§e这只村民还没找到合适的空地§7（需要 " + cfg.openSpaceSize + "x" + cfg.openSpaceSize
                    + " 的平坦露天场地）。");
            return;
        }
        if (!builder.standing) {
            send(player, "§e这只村民正在前往空地 §f" + builder.site.getX() + ", " + builder.site.getY() + ", "
                    + builder.site.getZ() + "§7。");
            return;
        }
        ServerWorld world = player.getWorld() instanceof ServerWorld serverWorld ? serverWorld : null;
        int partners = 0;
        int masons = 0;
        if (world != null) {
            List<VillagerEntity> near = world.getEntitiesByClass(VillagerEntity.class,
                    new Box(builder.site).expand(cfg.buildClusterRadius), Entity::isAlive);
            for (VillagerEntity other : near) {
                Builder otherBuilder = BUILDERS.get(uuidOf(other));
                if (otherBuilder != null && otherBuilder.standing && other != villager) {
                    partners++;
                }
                if (isMason(other) && other.squaredDistanceTo(villager) <= (double) cfg.masonRadius * cfg.masonRadius) {
                    masons++;
                }
            }
        }
        send(player, "§a这只村民已就位§7，附近就位村民 " + (partners + 1) + " 名、石匠 " + masons + " 名。");
        if (partners < 1) {
            send(player, "§7条件不足：需要至少 §f2§7 名未绑床成年村民一起就位。");
        } else if (masons < 1) {
            send(player, "§7条件不足：需要 §f" + cfg.masonRadius + "§7 格内有一名石匠职业村民。");
        } else {
            send(player, "§6条件满足，马上开工！");
        }
    }

    private static void send(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message), false);
    }

    private static UUID uuidOf(Entity entity) {
        return ((EntityLike) entity).getUuid();
    }

    private static BlockPos posOf(Entity entity) {
        return ((EntityLike) entity).getBlockPos();
    }

    /** /villagearchitect status: what the mod currently thinks about the village around you. */
    public static int printStatus(com.mojang.brigadier.context.CommandContext<net.minecraft.server.command.ServerCommandSource> context) {
        net.minecraft.server.command.ServerCommandSource source = context.getSource();
        ServerWorld world = source.getWorld();
        List<BuiltHouse> houses = housesIn(world);
        int homes = 0;
        int bound = 0;
        int workshops = 0;
        for (BuiltHouse house : houses) {
            switch (house.kind()) {
                case HOME -> homes++;
                case HOME_BOUND -> bound++;
                case WORKSHOP -> workshops++;
            }
        }
        say(source, "建筑记录：未配对住房 " + homes + " 栋 / 已配对 " + bound + " / 职业房 " + workshops);
        List<BuiltHouse> pair = unpairedPair(world);
        if (pair == null) {
            say(source, "下一步会盖：普通住房（未配对住房不足 2 栋）");
        } else {
            say(source, "下一步会盖：职业房（待配对 " + describe(pair.get(0).box()) + " 与 "
                    + describe(pair.get(1).box()) + "）");
        }

        net.minecraft.util.math.Vec3d pos = source.getPosition();
        net.minecraft.util.math.Box area = new net.minecraft.util.math.Box(pos, pos).expand(32.0);
        int nearby = 0;
        int masons = 0;
        int bedless = 0;
        int adults = 0;
        for (VillagerEntity villager : world.getEntitiesByClass(VillagerEntity.class, area, Entity::isAlive)) {
            nearby++;
            if (isMason(villager)) {
                masons++;
            }
            if (!villager.isBaby()) {
                adults++;
                if (!hasBed(villager)) {
                    bedless++;
                }
            }
        }
        say(source, "附近 32 格：村民 " + nearby + " 名（成年 " + adults + "，其中没绑床 " + bedless + "），石匠 " + masons + " 名");

        int tracked = 0;
        int waiting = 0;
        int arrived = 0;
        int withMason = 0;
        for (Builder builder : BUILDERS.values()) {
            tracked++;
            if (builder.site == null) {
                waiting++;
            } else if (builder.standing) {
                arrived++;
            }
            if (builder.mason != null) {
                withMason++;
            }
        }
        say(source, "正在张罗的村民：" + tracked + " 名（还没找到地 " + waiting + " / 已就位 " + arrived + " / 已配石匠 " + withMason + "）");
        say(source, "建成条件：2 名已就位且相距 " + ArchMod.CONFIG.buildClusterRadius + " 格内，其中一名的石匠在 "
                + ArchMod.CONFIG.masonRadius + " 格内");

        int shown = 0;
        for (Map.Entry<UUID, Builder> entry : BUILDERS.entrySet()) {
            if (shown++ >= 6) {
                break;
            }
            Builder builder = entry.getValue();
            String state = builder.site == null ? "找地中"
                    : (builder.standing ? "已就位" : "前往中");
            say(source, "  · " + entry.getKey().toString().substring(0, 8) + " " + state
                    + (builder.site == null ? "" : " @" + builder.site.toShortString())
                    + (builder.mason == null ? "" : " 有石匠"));
        }
        return 1;
    }

    private static String describe(HouseBox box) {
        return "(" + box.minX() + "," + box.minY() + "," + box.minZ() + ")";
    }

    private static void say(net.minecraft.server.command.ServerCommandSource source, String message) {
        source.sendFeedback(() -> net.minecraft.text.Text.literal(message), false);
    }
    private static boolean hasBed(VillagerEntity villager) {
        return villager.getBrain().getOptionalMemory(MemoryModuleType.HOME).isPresent();
    }

    private static boolean isMason(VillagerEntity villager) {
        VillagerDataContainer container = villager;
        Identifier profession = Registries.VILLAGER_PROFESSION.getId(container.getVillagerData().getProfession());
        return MASON_ID.equals(profession);
    }

    private static boolean isMarked(ServerWorld world, BlockPos site, Config cfg) {
        if (cfg.buildCooldownTicks <= 0) {
            return false;
        }
        List<BuildMark> marks = RECENT_BUILDS.get(world.getRegistryKey());
        if (marks == null) {
            return false;
        }
        double radius2 = 64.0;
        for (BuildMark mark : marks) {
            if (mark.expire() < world.getTime()) {
                continue;
            }
            if (mark.pos().getSquaredDistance(site) <= radius2) {
                return true;
            }
        }
        return false;
    }

    private static VillagerEntity findById(List<VillagerEntity> villagers, UUID id) {
        for (VillagerEntity villager : villagers) {
            if (uuidOf(villager).equals(id)) {
                return villager;
            }
        }
        return null;
    }

    /**
     * A freshly placed house swallows the plots the neighbours had already picked, so any villager
     * whose spot is that close is sent back to searching right away instead of standing on a wall.
     */
    private static void clearSitesNear(ServerWorld world, BlockPos built, List<VillagerEntity> villagers) {
        long now = world.getTime();
        double radius2 = 14.0 * 14.0;
        for (VillagerEntity other : villagers) {
            Builder builder = BUILDERS.get(uuidOf(other));
            if (builder == null || builder.site == null) {
                continue;
            }
            if (builder.site.getSquaredDistance(built) > radius2) {
                continue;
            }
            builder.site = null;
            builder.plan = null;
            builder.standing = false;
            builder.buildingSince = 0L;
            builder.nextSearchTick = now;
            if (builder.mason != null) {
                releaseMason(builder.mason);
                builder.mason = null;
            }
        }
    }

    private static String dimensionId(ServerWorld world) {
        return world.getRegistryKey().getValue().toString();
    }

    private static List<BuiltHouse> housesIn(ServerWorld world) {
        if (!housesLoaded) {
            loadHouses();
        }
        return HOUSES.computeIfAbsent(dimensionId(world), key -> new ArrayList<>());
    }

    /** Turns the two oldest unpaired homes into a pair, after their work place has been built. */
    private static void bindPair(ServerWorld world) {
        List<BuiltHouse> houses = housesIn(world);
        int bound = 0;
        for (int i = 0; i < houses.size() && bound < 2; i++) {
            if (houses.get(i).kind() == HouseKind.HOME) {
                houses.set(i, new BuiltHouse(houses.get(i).box(), HouseKind.HOME_BOUND));
                bound++;
            }
        }
        if (bound > 0) {
            ArchMod.LOG.info("[Village Architect] paired {} home(s) with the new work place", bound);
        }
    }

    private static Path housesFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("villagearchitect-houses.json");
    }

    private static synchronized void loadHouses() {
        housesLoaded = true;
        Path path = housesFile();
        HOUSES.clear();
        if (!Files.exists(path)) {
            return;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<Map<String, List<int[]>>>() {
            }.getType();
            Map<String, List<int[]>> raw = GSON.fromJson(json, type);
            if (raw == null) {
                return;
            }
            for (Map.Entry<String, List<int[]>> entry : raw.entrySet()) {
                List<BuiltHouse> houses = new ArrayList<>();
                for (int[] v : entry.getValue()) {
                    if (v == null || v.length < 6) {
                        continue;
                    }
                    HouseKind kind = HouseKind.HOME;
                    if (v.length >= 7 && v[6] >= 0 && v[6] < HouseKind.values().length) {
                        kind = HouseKind.values()[v[6]];
                    }
                    houses.add(new BuiltHouse(HouseBox.of(v[0], v[1], v[2], v[3], v[4], v[5]), kind));
                }
                HOUSES.put(entry.getKey(), houses);
            }
        } catch (IOException | RuntimeException e) {
            ArchMod.LOG.warn("[Village Architect] could not read {}", path, e);
        }
    }

    private static synchronized void saveHouses() {
        Map<String, List<int[]>> raw = new HashMap<>();
        for (Map.Entry<String, List<BuiltHouse>> entry : HOUSES.entrySet()) {
            List<int[]> boxes = new ArrayList<>();
            for (BuiltHouse house : entry.getValue()) {
                HouseBox box = house.box();
                boxes.add(new int[]{box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(),
                        house.kind().ordinal()});
            }
            raw.put(entry.getKey(), boxes);
        }
        try {
            Files.createDirectories(housesFile().getParent());
            Files.writeString(housesFile(), GSON.toJson(raw), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ArchMod.LOG.warn("[Village Architect] could not save house list", e);
        }
    }

    private static void forget(UUID villagerId) {
        Builder removed = BUILDERS.remove(villagerId);
        if (removed != null && removed.mason != null) {
            releaseMason(removed.mason);
        }
        MASON_TO_BUILDER.values().removeIf(builderId -> builderId.equals(villagerId));
    }

    private static void releaseMason(UUID masonId) {
        MASON_TO_BUILDER.remove(masonId);
    }
}
