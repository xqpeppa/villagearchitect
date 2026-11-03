package io.github.xqpeppa.architect;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Places the vanilla village houses (the same .nbt files the game itself uses for villages).
 * Structures live at data/minecraft/structure/..., so the identifier is written without that
 * prefix, e.g. "minecraft:village/plains/houses/plains_small_house_1".
 *
 * <p>Houses are placed <b>without rotation</b>. Rotating a template turns it around its pivot, so a
 * rotated house ends up half a house to the side of the spot that was checked - that is what made
 * earlier versions look "off grid" and allowed two houses to almost merge. Growing the village with
 * an unrotated, exactly centred house keeps the footprint identical to the area the corner check in
 * {@link GroundProbe} verified.</p>
 */
public final class StructurePlacer {
    public record Plan(Identifier id, int sizeX, int sizeY, int sizeZ, boolean workshop) {
    }

    /** Plain homes: the buildings that count as "a house" for the two-houses rule. */
    private static final Map<String, String[]> HOMES = new HashMap<>();

    /** Work places: one of these is added for every two finished homes. */
    private static final Map<String, String[]> WORKSHOPS = new HashMap<>();

    static {
        HOMES.put("plains", new String[]{
                "plains_small_house_1", "plains_small_house_2", "plains_small_house_3",
                "plains_small_house_4", "plains_small_house_5", "plains_small_house_6",
                "plains_small_house_7", "plains_small_house_8",
                "plains_medium_house_1", "plains_medium_house_2", "plains_big_house_1"
        });
        HOMES.put("desert", new String[]{
                "desert_small_house_1", "desert_small_house_2", "desert_small_house_3",
                "desert_small_house_4", "desert_small_house_5", "desert_small_house_6",
                "desert_small_house_7", "desert_small_house_8",
                "desert_medium_house_1", "desert_medium_house_2"
        });
        HOMES.put("savanna", new String[]{
                "savanna_small_house_1", "savanna_small_house_2", "savanna_small_house_3",
                "savanna_small_house_4", "savanna_small_house_5", "savanna_small_house_6",
                "savanna_small_house_7", "savanna_small_house_8",
                "savanna_medium_house_1", "savanna_medium_house_2"
        });
        HOMES.put("snowy", new String[]{
                "snowy_small_house_1", "snowy_small_house_2", "snowy_small_house_3",
                "snowy_small_house_4", "snowy_small_house_5", "snowy_small_house_6",
                "snowy_small_house_7",
                "snowy_medium_house_1", "snowy_medium_house_2", "snowy_medium_house_3"
        });
        HOMES.put("taiga", new String[]{
                "taiga_small_house_1", "taiga_small_house_2", "taiga_small_house_3",
                "taiga_small_house_4", "taiga_small_house_5",
                "taiga_medium_house_1", "taiga_medium_house_2", "taiga_medium_house_3",
                "taiga_medium_house_4"
        });

        WORKSHOPS.put("plains", new String[]{
                "plains_library_1", "plains_library_2", "plains_masons_house_1",
                "plains_butcher_shop_1", "plains_butcher_shop_2", "plains_cartographer_1",
                "plains_fisher_cottage_1", "plains_fletcher_house_1", "plains_shepherds_house_1",
                "plains_armorer_house_1", "plains_tool_smith_1", "plains_weaponsmith_1", "plains_tannery_1"
        });
        WORKSHOPS.put("desert", new String[]{
                "desert_library_1", "desert_mason_1", "desert_butcher_shop_1",
                "desert_cartographer_house_1", "desert_fisher_1", "desert_fletcher_house_1",
                "desert_shepherd_house_1", "desert_armorer_1", "desert_tool_smith_1",
                "desert_weaponsmith_1", "desert_tannery_1"
        });
        WORKSHOPS.put("savanna", new String[]{
                "savanna_library_1", "savanna_mason_1", "savanna_butchers_shop_1",
                "savanna_butchers_shop_2", "savanna_cartographer_1", "savanna_fisher_cottage_1",
                "savanna_fletcher_house_1", "savanna_shepherd_1", "savanna_armorer_1",
                "savanna_tool_smith_1", "savanna_weaponsmith_1", "savanna_weaponsmith_2",
                "savanna_tannery_1"
        });
        WORKSHOPS.put("snowy", new String[]{
                "snowy_library_1", "snowy_masons_house_1", "snowy_masons_house_2",
                "snowy_butchers_shop_1", "snowy_butchers_shop_2", "snowy_cartographer_house_1",
                "snowy_fisher_cottage", "snowy_fletcher_house_1", "snowy_shepherds_house_1",
                "snowy_armorer_house_1", "snowy_armorer_house_2", "snowy_tool_smith_1",
                "snowy_weapon_smith_1", "snowy_tannery_1"
        });
        WORKSHOPS.put("taiga", new String[]{
                "taiga_library_1", "taiga_masons_house_1", "taiga_butcher_shop_1",
                "taiga_cartographer_house_1", "taiga_fisher_cottage_1", "taiga_fletcher_house_1",
                "taiga_shepherds_house_1", "taiga_armorer_2", "taiga_armorer_house_1",
                "taiga_tool_smith_1", "taiga_weaponsmith_1", "taiga_weaponsmith_2", "taiga_tannery_1"
        });
    }

    private StructurePlacer() {
    }
    /** Picks one vanilla village house of the local biome, or null when none is available. */
    public static Plan pick(ServerWorld world, BlockPos pos, Random random) {
        return pick(world, pos, random, false);
    }

    /**
     * Picks a building of the local biome: a plain home, or - when {@code workshop} is true - one of
     * the work places (library, mason, butcher, ...). Falls back to the other kind when the wanted
     * one has no template that fits.
     */
    public static Plan pick(ServerWorld world, BlockPos pos, Random random, boolean workshop) {
        String biome = world.getBiome(pos).getKey().map(key -> key.getValue().getPath()).orElse("plains");
        Plan plan = pickFrom(world, biome, random, workshop ? WORKSHOPS : HOMES);
        if (plan == null) {
            plan = pickFrom(world, biome, random, workshop ? HOMES : WORKSHOPS);
        }
        return plan;
    }

    private static Plan pickFrom(ServerWorld world, String biome, Random random, Map<String, String[]> table) {
        String[] names = table.getOrDefault(biome, table.get("plains"));
        if (names == null) {
            return null;
        }
        boolean isWorkshop = table == WORKSHOPS;
        List<Plan> found = new ArrayList<>();
        for (String name : names) {
            Plan plan = planOf(world, Identifier.of("minecraft", "village/" + biome + "/houses/" + name), isWorkshop);
            if (plan != null && plan.sizeX() <= 16 && plan.sizeY() <= 16 && plan.sizeZ() <= 16) {
                found.add(plan);
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        return found.get(random.nextInt(found.size()));
    }

    private static Plan planOf(ServerWorld world, Identifier id, boolean workshop) {
        try {
            Optional<StructureTemplate> template = world.getStructureTemplateManager().getTemplate(id);
            if (template.isEmpty()) {
                return null;
            }
            Vec3i size = template.get().getSize();
            return new Plan(id, size.getX(), size.getY(), size.getZ(), workshop);
        } catch (RuntimeException e) {
            ArchMod.LOG.debug("[Village Architect] cannot read template {}", id, e);
            return null;
        }
    }

    /**
     * Places {@code plan} so that its lowest layer replaces the ground layer under {@code site}
     * (which is the air block above the ground). Returns the box of the house, or null.
     */
    public static HouseBox place(ServerWorld world, BlockPos site, Plan plan, Random random,
                                 List<HouseBox> occupied, int gap) {
        Optional<StructureTemplate> found = world.getStructureTemplateManager().getTemplate(plan.id());
        if (found.isEmpty()) {
            return null;
        }
        StructureTemplate template = found.get();
        Vec3i size = template.getSize();

        BlockPos origin = originOf(site, size);
        HouseBox box = boxOf(site, size);
        if (isTooClose(box, occupied, gap)) {
            return null;
        }
        if (!ringClear(world, box, gap)) {
            return null;
        }

        StructurePlacementData placement = new StructurePlacementData();
        placement.setMirror(BlockMirror.NONE);
        placement.setRotation(BlockRotation.NONE);
        placement.setIgnoreEntities(false);
        if (!template.place(world, origin, origin, placement, random, Block.NOTIFY_ALL)) {
            return null;
        }

        ArchMod.LOG.debug("[Village Architect] placed {} at {}", plan.id(), origin);
        cleanupStructureBlocks(world, origin, size);
        stripDirtFloor(world, origin, size, groundReplacement(world, box, gap));
        return box;
    }

    /** Lower corner of the template when it is placed for {@code site}. */
    public static BlockPos originOf(BlockPos site, Vec3i size) {
        return new BlockPos(
                site.getX() - (size.getX() - 1) / 2,
                site.getY() - 1,
                site.getZ() - (size.getZ() - 1) / 2);
    }

    /** Where the house for {@code site} would end up, so villagers can be moved out beforehand. */
    public static HouseBox boxOf(BlockPos site, Vec3i size) {
        BlockPos origin = originOf(site, size);
        return HouseBox.of(
                origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + size.getX() - 1,
                origin.getY() + size.getY() - 1,
                origin.getZ() + size.getZ() - 1);
    }

    /** Same box, sized from a plan. */
    public static HouseBox boxOf(BlockPos site, Plan plan) {
        return boxOf(site, new Vec3i(plan.sizeX(), plan.sizeY(), plan.sizeZ()));
    }

    /** Bounding box of the house a plan would build at {@code site}, or null when unknown. */    /** True when the candidate box would come closer than {@code gap} blocks to a built house. */
    public static boolean isTooClose(HouseBox candidate, List<HouseBox> occupied, int gap) {
        for (HouseBox other : occupied) {
            if (candidate.tooClose(other, gap)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when nothing solid stands in the volume of the house or in the {@code gap} wide ring
     * around it, so a new house can never grow into something that is already there.
     */
    private static boolean ringClear(ServerWorld world, HouseBox box, int gap) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = box.minX() - gap; x <= box.maxX() + gap; x++) {
            for (int z = box.minZ() - gap; z <= box.maxZ() + gap; z++) {
                for (int y = box.minY() + 1; y <= box.maxY(); y++) {
                    pos.set(x, y, z);
                    if (world.getBlockState(pos).isSolidBlock(world, pos)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Village house templates keep the jigsaw helper blocks the world generator uses for
     * connections. Left alone they stay behind as visible purple jigsaw blocks, but simply deleting
     * them punches holes into walls (a jigsaw often sits in the middle of a wall). So every jigsaw is
     * replaced by the block that surrounds it most - usually the wall material it was cut out of -
     * and only falls back to air when it is surrounded by air.
     */
    private static void cleanupStructureBlocks(ServerWorld world, BlockPos origin, Vec3i size) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        List<BlockPos> jigsaws = new ArrayList<>();
        for (int dx = 0; dx < size.getX(); dx++) {
            for (int dy = 0; dy < size.getY(); dy++) {
                for (int dz = 0; dz < size.getZ(); dz++) {
                    pos.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    BlockState state = world.getBlockState(pos);
                    if (state.isOf(Blocks.JIGSAW)) {
                        jigsaws.add(new BlockPos(pos));
                    } else if (state.isOf(Blocks.STRUCTURE_VOID) || state.isOf(Blocks.STRUCTURE_BLOCK)) {
                        world.setBlockState(pos, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                    }
                }
            }
        }
        for (BlockPos jigsaw : jigsaws) {
            BlockState patch = mostCommonNeighbor(world, jigsaw);
            world.setBlockState(jigsaw, patch == null ? Blocks.AIR.getDefaultState() : patch, Block.NOTIFY_ALL);
        }
    }

    private static BlockState mostCommonNeighbor(ServerWorld world, BlockPos pos) {
        Map<Block, Integer> counts = new HashMap<>();
        for (Direction direction : Direction.values()) {
            BlockPos side = pos.offset(direction);
            BlockState state = world.getBlockState(side);
            if (state.isAir() || state.isOf(Blocks.JIGSAW) || state.isOf(Blocks.STRUCTURE_VOID)
                    || state.isOf(Blocks.STRUCTURE_BLOCK) || !state.isSolidBlock(world, side)) {
                continue;
            }
            counts.merge(state.getBlock(), 1, Integer::sum);
        }
        Block best = null;
        int bestCount = 0;
        for (Map.Entry<Block, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best == null ? null : best.getDefaultState();
    }

    /**
     * Village house templates carry their own floor, which is mostly dirt / coarse dirt. On flat
     * grass that shows up as a brown pad under the house, so the bottom layer of the template is
     * replaced by the ground the village stands on. The same layer also needs the opposite
     * treatment: the template holds air wherever the bottom layer is neither floor nor wall, and
     * since the house overlaps the terrain by one block that air would leave a trench. Those spots
     * get the ground back.
     */
    private static void stripDirtFloor(ServerWorld world, BlockPos origin, Vec3i size, BlockState replacement) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        BlockPos.Mutable below = new BlockPos.Mutable();
        int replaced = 0;
        int filled = 0;
        for (int dx = 0; dx < size.getX(); dx++) {
            for (int dz = 0; dz < size.getZ(); dz++) {
                pos.set(origin.getX() + dx, origin.getY(), origin.getZ() + dz);
                BlockState state = world.getBlockState(pos);
                if (isDirtLike(state)) {
                    world.setBlockState(pos, replacement, Block.NOTIFY_ALL);
                    replaced++;
                    continue;
                }
                if (state.isAir()) {
                    below.set(pos.getX(), pos.getY() - 1, pos.getZ());
                    if (world.getBlockState(below).isSolidBlock(world, below)) {
                        world.setBlockState(pos, replacement, Block.NOTIFY_ALL);
                        filled++;
                    }
                }
            }
        }
        if (replaced > 0 || filled > 0) {
            ArchMod.LOG.debug("[Village Architect] ground layer: {} dirt replaced, {} holes filled", replaced, filled);
        }
    }

    /** Most common surface block right outside the house (grass on a plain, sand in a desert). */
    private static BlockState groundReplacement(ServerWorld world, HouseBox box, int gap) {
        Map<Block, Integer> counts = new HashMap<>();
        int y = box.minY();
        int reach = Math.max(gap, 2) + 2;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = box.minX() - reach; x <= box.maxX() + reach; x++) {
            for (int z = box.minZ() - reach; z <= box.maxZ() + reach; z++) {
                boolean inside = x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ();
                if (inside) {
                    continue;
                }
                pos.set(x, y, z);
                BlockState state = world.getBlockState(pos);
                if (state.isAir() || !state.isSolidBlock(world, pos)) {
                    continue;
                }
                counts.merge(state.getBlock(), 1, Integer::sum);
            }
        }
        Block best = null;
        int bestCount = 0;
        for (Map.Entry<Block, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best == null ? Blocks.GRASS_BLOCK.getDefaultState() : best.getDefaultState();
    }

    private static boolean isDirtLike(BlockState state) {
        return state.isOf(Blocks.DIRT) || state.isOf(Blocks.COARSE_DIRT) || state.isOf(Blocks.ROOTED_DIRT)
                || state.isOf(Blocks.PODZOL) || state.isOf(Blocks.DIRT_PATH) || state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.SAND) || state.isOf(Blocks.RED_SAND);
    }
}
