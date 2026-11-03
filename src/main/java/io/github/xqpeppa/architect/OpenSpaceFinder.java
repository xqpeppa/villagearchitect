package io.github.xqpeppa.architect;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds flat, open (roof-free) spots that are large enough for a village house.
 * A spot is accepted when every column of the footprint has solid ground at (almost)
 * the same height and the whole 16 block volume above it is air.
 */
public final class OpenSpaceFinder {
    private static final int MAX_CANDIDATES = 6;

    private OpenSpaceFinder() {
    }

    /** Returns the first air block of a valid site, or null when nothing suitable is nearby. */
    public static BlockPos findOpenSpaceNear(ServerWorld world, BlockPos center, int radius, int size) {
        return findOpenSpaceNear(world, center, radius, size, List.of(), 2);
    }

    /**
     * Same as above, but a spot is rejected when it would come closer than {@code gap} blocks to a
     * house the mod has already built, so villagers look for fresh ground instead of squeezing a
     * second house right next to the first one.
     */
    public static BlockPos findOpenSpaceNear(ServerWorld world, BlockPos center, int radius, int size,
                                            List<HouseBox> houses, int gap) {
        List<BlockPos> candidates = new ArrayList<>();
        int step = Math.max(3, radius / 5);
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                int ground = findGroundY(world, x, z, center.getY() + 6, center.getY() - 12);
                if (ground != Integer.MIN_VALUE) {
                    candidates.add(new BlockPos(x, ground, z));
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(dist2(a, center), dist2(b, center)));

        int tried = 0;
        for (BlockPos candidate : candidates) {
            if (tried++ >= MAX_CANDIDATES) {
                break;
            }
            if (areaOk(world, candidate, size) && !tooCloseToHouses(candidate, size, houses, gap)) {
                return candidate.up();
            }
        }
        return null;
    }

    private static boolean tooCloseToHouses(BlockPos ground, int size, List<HouseBox> houses, int gap) {
        if (houses.isEmpty()) {
            return false;
        }
        int half = size / 2;
        HouseBox square = HouseBox.of(
                ground.getX() - half, ground.getY(), ground.getZ() - half,
                ground.getX() - half + size - 1, ground.getY() + size - 1,
                ground.getZ() - half + size - 1);
        return StructurePlacer.isTooClose(square, houses, gap);
    }

    private static double dist2(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Scans downwards from startY for the first solid, non-fluid block.
     * Returns Integer.MIN_VALUE when the column has nothing usable in range.
     */
    public static int findGroundY(ServerWorld world, int x, int z, int startY, int minY) {
        BlockPos.Mutable pos = new BlockPos.Mutable(x, startY, z);
        for (int y = startY; y >= minY; y--) {
            pos.setY(y);
            BlockState state = world.getBlockState(pos);
            if (!state.isAir() && state.getFluidState().isEmpty() && state.isSolidBlock(world, pos)) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** Checks a square footprint of `size` blocks centred on the given ground block. */
    public static boolean areaOk(ServerWorld world, BlockPos center, int size) {
        int half = size / 2;
        int baseY = center.getY();

        for (int dx = -half; dx < size - half; dx++) {
            for (int dz = -half; dz < size - half; dz++) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                int ground = findGroundY(world, x, z, baseY + 2, baseY - 3);
                if (ground == Integer.MIN_VALUE || Math.abs(ground - baseY) > 1) {
                    return false;
                }
            }
        }

        // Sample the footprint every 2 blocks so that a wall of a house that is already standing
        // cannot slip between two samples (that used to let two houses grow into each other).
        int[] samples = new int[size / 2 + 1];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = -half + Math.min(i * 2, size - 1);
        }
        BlockPos.Mutable pos = new BlockPos.Mutable(0, 0, 0);
        for (int dx : samples) {
            for (int dz : samples) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                int ground = findGroundY(world, x, z, baseY + 2, baseY - 3);
                if (ground == Integer.MIN_VALUE) {
                    return false;
                }
                pos.set(x, ground + 1, z);
                for (int y = ground + 1; y <= baseY + size; y++) {
                    pos.setY(y);
                    if (!world.getBlockState(pos).isAir()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** True when nothing solid covers the block within 16 blocks above it. */
    public static boolean isOutdoor(ServerWorld world, BlockPos pos) {
        BlockPos.Mutable probe = new BlockPos.Mutable(pos.getX(), pos.getY(), pos.getZ());
        for (int y = pos.getY() + 1; y <= pos.getY() + 16; y++) {
            probe.setY(y);
            BlockState state = world.getBlockState(probe);
            if (!state.isAir() && state.isSolidBlock(world, probe)) {
                return false;
            }
        }
        return true;
    }
}
