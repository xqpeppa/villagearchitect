package io.github.xqpeppa.architect;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * The flat ground test, taken from the command block prototype: a villager may build when the four
 * corners (plus the middle of each edge) of the future footprint all stand on flat ground of the
 * same height, the ground surface under them is natural terrain, and nothing solid is standing in
 * the air above.
 *
 * <p>That is far cheaper than sampling a whole 16x16x16 volume and it matches the way the prototype
 * decided whether a spot was "open enough".</p>
 */
public final class GroundProbe {
    private static final int MAX_CANDIDATES = 10;

    private GroundProbe() {
    }

    /**
     * Returns the air block above a valid building site (so {@code site.getY() - 1} is the ground
     * layer), or null when nothing suitable is nearby.
     */
    public static BlockPos findAnchor(ServerWorld world, BlockPos center, int radius,
                                      int sizeX, int sizeY, int sizeZ,
                                      List<HouseBox> houses, int gap) {
        List<BlockPos> candidates = new ArrayList<>();
        int step = Math.max(2, radius / 6);
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                int ground = OpenSpaceFinder.findGroundY(world, x, z, center.getY() + 8, center.getY() - 16);
                if (ground != Integer.MIN_VALUE) {
                    candidates.add(new BlockPos(x, ground, z));
                }
            }
        }
        candidates.sort((a, b) -> Double.compare(dist2(a, center), dist2(b, center)));

        int tried = 0;
        for (BlockPos ground : candidates) {
            if (tried++ >= MAX_CANDIDATES) {
                break;
            }
            if (!cornersOk(world, ground, sizeX, sizeY, sizeZ)) {
                continue;
            }
            HouseBox box = boxOf(ground, sizeX, sizeY, sizeZ);
            if (StructurePlacer.isTooClose(box, houses, gap)) {
                continue;
            }
            return ground.up();
        }
        return null;
    }

    /** The corner + edge middle check: same height, natural surface, open air above. */
    public static boolean cornersOk(ServerWorld world, BlockPos ground, int sizeX, int sizeY, int sizeZ) {
        int x0 = ground.getX() - (sizeX - 1) / 2;
        int z0 = ground.getZ() - (sizeZ - 1) / 2;
        int x1 = x0 + sizeX - 1;
        int z1 = z0 + sizeZ - 1;
        int mx = (x0 + x1) / 2;
        int mz = (z0 + z1) / 2;

        int[][] checks = {
                {x0, z0}, {x1, z0}, {x0, z1}, {x1, z1},
                {mx, z0}, {mx, z1}, {x0, mz}, {x1, mz}, {mx, mz}
        };
        for (int[] check : checks) {
            int x = check[0];
            int z = check[1];
            int y = OpenSpaceFinder.findGroundY(world, x, z, ground.getY() + 2, ground.getY() - 3);
            if (y != ground.getY()) {
                return false;
            }
            BlockState surface = world.getBlockState(new BlockPos(x, y, z));
            if (!isNaturalSurface(surface)) {
                return false;
            }
            BlockPos.Mutable pos = new BlockPos.Mutable(x, y + 1, z);
            for (int dy = 1; dy <= sizeY; dy++) {
                pos.setY(y + dy);
                BlockState state = world.getBlockState(pos);
                if (state.isSolidBlock(world, pos)) {
                    return false;
                }
                if (!state.isAir() && !state.getFluidState().isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isNaturalSurface(BlockState state) {
        return state.isOf(Blocks.GRASS_BLOCK) || state.isOf(Blocks.DIRT) || state.isOf(Blocks.COARSE_DIRT)
                || state.isOf(Blocks.ROOTED_DIRT) || state.isOf(Blocks.PODZOL) || state.isOf(Blocks.MYCELIUM)
                || state.isOf(Blocks.SAND) || state.isOf(Blocks.RED_SAND) || state.isOf(Blocks.SNOW_BLOCK)
                || state.isOf(Blocks.STONE) || state.isOf(Blocks.ANDESITE) || state.isOf(Blocks.DIORITE)
                || state.isOf(Blocks.GRANITE) || state.isOf(Blocks.TUFF) || state.isOf(Blocks.CALCITE);
    }

    public static HouseBox boxOf(BlockPos ground, int sizeX, int sizeY, int sizeZ) {
        int x0 = ground.getX() - (sizeX - 1) / 2;
        int z0 = ground.getZ() - (sizeZ - 1) / 2;
        return HouseBox.of(x0, ground.getY(), z0, x0 + sizeX - 1, ground.getY() + sizeY - 1, z0 + sizeZ - 1);
    }

    private static double dist2(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
