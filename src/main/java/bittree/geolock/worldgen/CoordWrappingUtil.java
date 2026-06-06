package bittree.geolock.worldgen;

import bittree.geolock.GeolockServerConfig;
import net.minecraft.core.BlockPos;

/**
 * Unified coordinate wrapping utility for GeoLock's infinite world looping.
 * 
 * All systems (noise blending, block access, chunk loading, portal stitching,
 * player teleportation) must use these same wrapping functions to ensure
 * consistent behavior at the world boundary.
 * 
 * The world is defined as a torus of size {@code worldBoundaryWidth} ×
 * {@code worldBoundaryWidth}, centered at (0,0). Coordinates are wrapped
 * into the range [-halfW, halfW) using modular arithmetic.
 */
public class CoordWrappingUtil {

    private CoordWrappingUtil() {}

    // -----------------------------------------------------------------------
    // Configuration access (cached for performance)
    // -----------------------------------------------------------------------

    public static double worldWidth() {
        return GeolockServerConfig.worldBoundaryWidth;
    }

    public static double halfWidth() {
        return GeolockServerConfig.worldBoundaryWidth / 2.0;
    }

    public static int worldWidthInChunks() {
        return (int) Math.round(GeolockServerConfig.worldBoundaryWidth / 16.0);
    }

    public static int halfWidthInChunks() {
        return worldWidthInChunks() / 2;
    }

    // -----------------------------------------------------------------------
    // Double-precision block coordinate wrapping
    // -----------------------------------------------------------------------

    /**
     * Wraps a double-precision block coordinate into the range [-halfW, halfW).
     */
    public static double wrapBlockCoord(double coord) {
        double w = worldWidth();
        double halfW = halfWidth();
        return ((coord + halfW) % w + w) % w - halfW;
    }

    /**
     * Wraps a double-precision block coordinate, returning the result as an
     * integer via floor conversion. This is the canonical way to convert a
     * wrapped double coordinate to a block position.
     */
    public static int wrapBlockCoordFloor(double coord) {
        return (int) Math.floor(wrapBlockCoord(coord) + 0.5);
    }

    // -----------------------------------------------------------------------
    // Integer block coordinate wrapping
    // -----------------------------------------------------------------------

    /**
     * Wraps an integer block coordinate into the range [-halfW, halfW).
     * Uses chunk-based wrapping to ensure alignment with chunk boundaries.
     */
    public static int wrapBlockCoord(int blockCoord) {
        int chunkCoord = Math.floorDiv(blockCoord, 16);
        int remainder = Math.floorMod(blockCoord, 16);
        return wrapChunkCoord(chunkCoord) * 16 + remainder;
    }

    // -----------------------------------------------------------------------
    // Chunk coordinate wrapping
    // -----------------------------------------------------------------------

    /**
     * Wraps a chunk coordinate so that chunks at ±halfWidthInChunks
     * map to the same chunk, enabling seamless feature/structure placement.
     */
    public static int wrapChunkCoord(int chunkCoord) {
        int half = halfWidthInChunks();
        int total = worldWidthInChunks();
        return Math.floorMod(chunkCoord + half, total) - half;
    }

    // -----------------------------------------------------------------------
    // BlockPos wrapping
    // -----------------------------------------------------------------------

    /**
     * Wraps a BlockPos into the world boundary range. Returns the same
     * BlockPos if no wrapping is needed, or a new BlockPos with wrapped
     * X/Z coordinates if the position is outside the boundary.
     */
    public static BlockPos wrapBlockPos(BlockPos pos) {
        double halfW = halfWidth();
        int x = pos.getX();
        int z = pos.getZ();

        boolean needsWrap = false;
        if (x < -halfW || x >= halfW) {
            x = wrapBlockCoordFloor((double) x);
            needsWrap = true;
        }
        if (z < -halfW || z >= halfW) {
            z = wrapBlockCoordFloor((double) z);
            needsWrap = true;
        }

        return needsWrap ? new BlockPos(x, pos.getY(), z) : pos;
    }

    // -----------------------------------------------------------------------
    // Boundary checks
    // -----------------------------------------------------------------------

    /**
     * Returns true if the given block coordinate is outside the world boundary.
     */
    public static boolean isOutsideBoundary(double coord) {
        double halfW = halfWidth();
        return coord < -halfW || coord >= halfW;
    }

    /**
     * Returns true if the given block coordinate is within the blend zone.
     */
    public static boolean isInBlendZone(double coord) {
        double halfW = halfWidth();
        double blendW = GeolockServerConfig.blendZoneWidth;
        double absCoord = Math.abs(coord);
        return absCoord > halfW - blendW;
    }

    // -----------------------------------------------------------------------
    // Mirror position calculation (for portal/teleport targeting)
    // -----------------------------------------------------------------------

    /**
     * Computes the wrapped-around position for a coordinate that has crossed
     * the boundary. Used for teleportation and portal destination calculation.
     */
    public static double mirrorCoord(double coord) {
        double w = worldWidth();
        double halfW = halfWidth();
        if (coord > halfW) {
            return coord - w;
        } else if (coord < -halfW) {
            return coord + w;
        }
        return coord;
    }
}
