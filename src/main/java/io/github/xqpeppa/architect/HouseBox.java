package io.github.xqpeppa.architect;

/**
 * Axis aligned box of a house that was already built by the mod.
 * Two boxes are "too close" when the number of empty blocks between them is smaller
 * than the configured gap, i.e. {@code gap == 2} keeps exactly two air blocks between walls.
 */
public record HouseBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static HouseBox of(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return new HouseBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** True when the two boxes are closer than {@code gap} empty blocks (horizontally). */
    public boolean tooClose(HouseBox other, int gap) {
        if (maxY + gap < other.minY || other.maxY + gap < minY) {
            return false;
        }
        return maxX + gap >= other.minX
                && other.maxX + gap >= minX
                && maxZ + gap >= other.minZ
                && other.maxZ + gap >= minZ;
    }
}
