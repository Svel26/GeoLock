package bittree.geolock.mixin;

import net.minecraft.world.level.levelgen.WorldgenRandom;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import bittree.geolock.GeolockServerConfig;

/**
 * Intercepts WorldgenRandom's chunk/block seeding methods so that feature
 * decoration (trees, rocks, flowers, ore veins) and structures (villages,
 * dungeons, etc.) tile seamlessly at the world boundary.
 *
 * HOW IT WORKS:
 * Minecraft seeds its world-gen random numbers using chunk coordinates:
 *   seed = worldSeed XOR (chunkX * constant1) XOR (chunkZ * constant2)
 *
 * For the world to tile, the chunk at +halfW and the chunk at -halfW must
 * produce the same seed. We achieve this by wrapping chunk coordinates
 * modulo worldWidthInChunks before they reach the seeding logic.
 *
 * Methods intercepted (from WorldgenRandom source):
 *
 *   setDecorationSeed(long, int, int) → long
 *       Uses block coords (chunkX*16, chunkZ*16). Drives all decoration placement.
 *
 *   setFeatureSeed(long, int, int) → void
 *       Uses feature index, not chunk coords. We intercept it only for the
 *       worldSeed argument (already correct since worldSeed is global).
 *       Actually: args are (decorationSeed, featureIndex, step) — NOT chunk coords,
 *       so no wrapping needed here.
 *
 *   setLargeFeatureSeed(long, int, int) → void
 *       Uses chunk coords. Drives large feature placement.
 *
 *   setLargeFeatureWithSalt(long, int, int, int) → void
 *       Uses chunk coords + salt. Drives structure placement.
 */
@Mixin(WorldgenRandom.class)
public abstract class WorldgenRandomMixin {

    /** Shadow setSeed so we can call it when replicating seeding logic. */
    @Shadow public abstract void setSeed(long seed);
    @Shadow public abstract long nextLong();

    /** ThreadLocal guard to prevent re-entrant wrapping. */
    private static final ThreadLocal<Boolean> IN_WRAP = ThreadLocal.withInitial(() -> false);

    // -----------------------------------------------------------------------
    // setDecorationSeed — returns long, uses BLOCK coordinates (chunkX*16, chunkZ*16)
    // -----------------------------------------------------------------------

    @Inject(
        method = "setDecorationSeed(JII)J",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onSetDecorationSeed(long worldSeed, int x, int z, CallbackInfoReturnable<Long> cir) {
        if (!GeolockServerConfig.enableWorldLooping || IN_WRAP.get()) return;

        int wx = wrapBlockCoord(x);
        int wz = wrapBlockCoord(z);
        if (wx == x && wz == z) return; // Already in canonical range, nothing to do

        IN_WRAP.set(true);
        try {
            // Replicate the vanilla logic with wrapped coords:
            //   this.setSeed(worldSeed);
            //   long i = this.nextLong() | 1L;
            //   long j = this.nextLong() | 1L;
            //   long k = (long)x * i + (long)z * j ^ worldSeed;
            //   this.setSeed(k);
            //   return k;
            // We call the real method with wrapped coords to keep parity.
            cir.setReturnValue(((WorldgenRandom)(Object)this).setDecorationSeed(worldSeed, wx, wz));
        } finally {
            IN_WRAP.set(false);
        }
    }

    // -----------------------------------------------------------------------
    // setLargeFeatureSeed — returns void, uses CHUNK coordinates
    // -----------------------------------------------------------------------

    @Inject(
        method = "setLargeFeatureSeed(JII)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onSetLargeFeatureSeed(long worldSeed, int chunkX, int chunkZ, CallbackInfo ci) {
        if (!GeolockServerConfig.enableWorldLooping || IN_WRAP.get()) return;

        int wx = wrapChunkCoord(chunkX);
        int wz = wrapChunkCoord(chunkZ);
        if (wx == chunkX && wz == chunkZ) return;

        ci.cancel();
        IN_WRAP.set(true);
        try {
            // Replicate vanilla logic:
            //   this.setSeed(worldSeed);
            //   long i = this.nextLong();
            //   long j = this.nextLong();
            //   long k = (long)chunkX * i ^ (long)chunkZ * j ^ worldSeed;
            //   this.setSeed(k);
            this.setSeed(worldSeed);
            long i = this.nextLong();
            long j = this.nextLong();
            long k = (long)wx * i ^ (long)wz * j ^ worldSeed;
            this.setSeed(k);
        } finally {
            IN_WRAP.set(false);
        }
    }

    // -----------------------------------------------------------------------
    // setLargeFeatureWithSalt — returns void, uses CHUNK coordinates + salt
    // Used for structure placement (villages, temples, strongholds, etc.)
    // -----------------------------------------------------------------------

    @Inject(
        method = "setLargeFeatureWithSalt(JIII)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onSetLargeFeatureWithSalt(long worldSeed, int chunkX, int chunkZ, int salt, CallbackInfo ci) {
        if (!GeolockServerConfig.enableWorldLooping || IN_WRAP.get()) return;

        int wx = wrapChunkCoord(chunkX);
        int wz = wrapChunkCoord(chunkZ);
        if (wx == chunkX && wz == chunkZ) return;

        ci.cancel();
        IN_WRAP.set(true);
        try {
            // Replicate vanilla logic:
            //   long i = (long)chunkX * 341873128712L + (long)chunkZ * 132897987541L + worldSeed + (long)salt;
            //   this.setSeed(i);
            long seed = (long)wx * 341873128712L + (long)wz * 132897987541L + worldSeed + (long)salt;
            this.setSeed(seed);
        } finally {
            IN_WRAP.set(false);
        }
    }

    // -----------------------------------------------------------------------
    // Coordinate wrapping helpers
    // -----------------------------------------------------------------------

    /**
     * Wraps a CHUNK coordinate into the canonical [-halfWidthInChunks, halfWidthInChunks)
     * range, so that the chunk at +halfW and the chunk at -halfW produce the same seed.
     *
     * Example (worldWidth=20000, worldWidthInChunks=1250, half=625):
     *   wrapChunkCoord(-625) = -625   (left edge, identity)
     *   wrapChunkCoord(  0 ) =    0   (center, identity)
     *   wrapChunkCoord( 624) =  624   (one chunk before right edge)
     *   wrapChunkCoord( 625) = -625   (right edge → left edge, seamless!)
     *   wrapChunkCoord( 626) = -624   (outside right → inside left)
     */
    private static int wrapChunkCoord(int coord) {
        double width = GeolockServerConfig.worldBoundaryWidth;
        int worldWidthInChunks = (int) Math.round(width / 16.0);
        int half = worldWidthInChunks / 2;
        return Math.floorMod(coord + half, worldWidthInChunks) - half;
    }

    /**
     * Wraps a BLOCK coordinate (as received by setDecorationSeed, which is called
     * with chunkX*16 and chunkZ*16) by rounding down to chunk space, wrapping,
     * then multiplying back.
     */
    private static int wrapBlockCoord(int blockCoord) {
        int chunkCoord = Math.floorDiv(blockCoord, 16);
        return wrapChunkCoord(chunkCoord) * 16;
    }
}
