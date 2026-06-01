package bittree.geolock.mixin;

import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
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
 * Minecraft seeds its world-gen random numbers using chunk coordinates.
 * For the world to tile, the chunk at +halfW and the chunk at -halfW must
 * produce the same seed. We achieve this by wrapping chunk/block coordinates
 * modulo worldWidthInChunks before the seeding logic runs.
 *
 * Vanilla method signatures (from decompiled 1.21.1 source):
 *
 *   long setDecorationSeed(long worldSeed, int blockX, int blockZ)
 *       → blockX = chunkX*16, blockZ = chunkZ*16
 *       → seeds decoration (trees, grass, ore, rocks, etc.)
 *
 *   void setLargeFeatureSeed(long worldSeed, int chunkX, int chunkZ)
 *       → seeds large features
 *
 *   void setLargeFeatureWithSalt(long worldSeed, int chunkX, int chunkZ, int salt)
 *       → seeds structures (villages, temples, dungeons, etc.)
 *
 * Note: WorldgenRandom extends LegacyRandomSource. We avoid @Shadow of inherited
 * methods (nextLong, setSeed) since Mixin can't find them without a refmap.
 * Instead, we cast to RandomSource and replicate the seeding math directly.
 */
@Mixin(WorldgenRandom.class)
public class WorldgenRandomMixin {

    /** Recursion guard so our re-invocation of setDecorationSeed doesn't loop. */
    private static final ThreadLocal<Boolean> IN_WRAP = ThreadLocal.withInitial(() -> false);

    // -----------------------------------------------------------------------
    // setDecorationSeed — returns long, called with BLOCK coordinates
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
        if (wx == x && wz == z) return;

        IN_WRAP.set(true);
        try {
            cir.setReturnValue(((WorldgenRandom)(Object)this).setDecorationSeed(worldSeed, wx, wz));
        } finally {
            IN_WRAP.set(false);
        }
    }

    // -----------------------------------------------------------------------
    // setLargeFeatureSeed — returns void, called with CHUNK coordinates
    //
    // Vanilla logic:
    //   this.setSeed(worldSeed);
    //   long i = this.nextLong();
    //   long j = this.nextLong();
    //   long k = (long)chunkX * i ^ (long)chunkZ * j ^ worldSeed;
    //   this.setSeed(k);
    //
    // We replicate this using a fresh LegacyRandomSource seeded at worldSeed,
    // then compute the same XOR formula with wrapped coords.
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

        // Replicate vanilla seeding logic with wrapped coords.
        // We create a temp random source seeded at worldSeed to generate i and j.
        RandomSource temp = WorldgenRandom.Algorithm.LEGACY.newInstance(worldSeed);
        long i = temp.nextLong();
        long j = temp.nextLong();
        long k = (long)wx * i ^ (long)wz * j ^ worldSeed;
        ((RandomSource)(Object)this).setSeed(k);
    }

    // -----------------------------------------------------------------------
    // setLargeFeatureWithSalt — returns void, called with CHUNK coordinates + salt
    //
    // Vanilla logic:
    //   long i = (long)chunkX * 341873128712L + (long)chunkZ * 132897987541L
    //            + worldSeed + (long)salt;
    //   this.setSeed(i);
    //
    // Simple arithmetic — no RNG calls needed.
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
        long seed = (long)wx * 341873128712L + (long)wz * 132897987541L + worldSeed + (long)salt;
        ((RandomSource)(Object)this).setSeed(seed);
    }

    // -----------------------------------------------------------------------
    // Coordinate wrapping helpers
    // -----------------------------------------------------------------------

    /**
     * Wraps a CHUNK coordinate so that chunks at ±halfWidthInChunks share a seed.
     *
     * Example (worldWidth=20000, worldWidthInChunks=1250, half=625):
     *   wrapChunkCoord(-625) = -625   (left edge, identity)
     *   wrapChunkCoord(  0 ) =    0   (center, identity)
     *   wrapChunkCoord( 624) =  624   (one chunk before right edge)
     *   wrapChunkCoord( 625) = -625   (right edge → same as left edge, seamless!)
     */
    private static int wrapChunkCoord(int coord) {
        double width = GeolockServerConfig.worldBoundaryWidth;
        int worldWidthInChunks = (int) Math.round(width / 16.0);
        int half = worldWidthInChunks / 2;
        return Math.floorMod(coord + half, worldWidthInChunks) - half;
    }

    /**
     * Wraps a BLOCK coordinate (chunkX*16 style) through chunk-space wrapping.
     */
    private static int wrapBlockCoord(int blockCoord) {
        int chunkCoord = Math.floorDiv(blockCoord, 16);
        return wrapChunkCoord(chunkCoord) * 16;
    }
}
