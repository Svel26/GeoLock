package bittree.geolock.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

import bittree.geolock.GeolockServerConfig;

/**
 * Custom Density Function for NeoForge 1.21.1.
 *
 * Applies a cross-fade blend near world boundaries to create a smooth visual
 * transition at the portal seam, while leaving the center of the world as
 * completely normal Minecraft terrain.
 *
 * HOW THE BLEND WORKS:
 * --------------------
 * The world spans [-halfW, +halfW] on both X and Z axes.
 * The blend zone is the last BLEND_WIDTH blocks before each edge.
 *
 * In the blend zone on the +X side (x in [halfW - BLEND_WIDTH, halfW]):
 *   t = (x - (halfW - BLEND_WIDTH)) / BLEND_WIDTH   [0 → 1]
 *   value = lerp(noise(x, z), noise(x - worldWidth, z), t)
 *
 * At x = halfW (t=1):    value = noise(halfW - worldWidth, z) = noise(-halfW, z)
 * At x = -halfW (t=1):   value = noise(-halfW + worldWidth, z) = noise(+halfW, z)
 *
 * The seam that remains: noise(-halfW) ≠ noise(+halfW) in general. But this seam
 * is located exactly at the portal face and is hidden by the Immersive Portals
 * rendering. The center 80%+ of the world is completely unmodified.
 *
 * VOID ZONE:
 * ----------
 * For finalDensity, coordinates outside the world boundary return -1000 (void).
 */
public class CylindricalNoise implements DensityFunction {

    /** Width of the cross-fade blend zone in blocks on each side of the world. */
    private static final double BLEND_WIDTH = 1500.0;

    private final Holder<DensityFunction> originalNoise;
    private final double worldWidth;
    private final double halfWidth;
    private final boolean isFinalDensity;

    public static final MapCodec<CylindricalNoise> CODEC = RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            DensityFunction.CODEC.fieldOf("noise").forGetter(CylindricalNoise::getOriginalNoise),
            Codec.DOUBLE.fieldOf("world_width").forGetter(CylindricalNoise::getWorldWidth),
            Codec.BOOL.optionalFieldOf("is_final_density", false).forGetter(CylindricalNoise::isFinalDensity)
        ).apply(instance, CylindricalNoise::new)
    );

    public CylindricalNoise(Holder<DensityFunction> originalNoise, double worldWidth) {
        this(originalNoise, worldWidth, false);
    }

    public CylindricalNoise(Holder<DensityFunction> originalNoise, double worldWidth, boolean isFinalDensity) {
        this.originalNoise = originalNoise;
        this.worldWidth = worldWidth;
        double width = GeolockServerConfig.enableWorldLooping ? GeolockServerConfig.worldBoundaryWidth : worldWidth;
        this.halfWidth = width / 2.0;
        this.isFinalDensity = isFinalDensity;
    }

    @Override
    public double compute(FunctionContext context) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return this.originalNoise.value().compute(context);
        }

        // Outside boundary returns void for final density pass
        if (this.isFinalDensity && !(context instanceof OffsetContext) &&
                (Math.abs(context.blockX()) > this.halfWidth || Math.abs(context.blockZ()) > this.halfWidth)) {
            return -1000.0;
        }

        return blend(context, ctx -> this.originalNoise.value().compute(ctx));
    }

    @Override
    public void fillArray(double[] densities, ContextProvider provider) {
        provider.fillAllDirectly(densities, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new CylindricalNoise(Holder.direct(this.originalNoise.value().mapAll(visitor)), this.worldWidth, this.isFinalDensity);
    }

    @Override
    public double minValue() {
        return this.originalNoise.value().minValue();
    }

    @Override
    public double maxValue() {
        return this.originalNoise.value().maxValue();
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return KeyDispatchDataCodec.of(CODEC);
    }

    public Holder<DensityFunction> getOriginalNoise() { return this.originalNoise; }
    public double getWorldWidth() { return this.worldWidth; }
    public boolean isFinalDensity() { return this.isFinalDensity; }

    /**
     * Cross-fade blend: in the outer BLEND_WIDTH blocks on each axis, the noise
     * smoothly transitions from the local value (t=0) to the wrapped value (t=1).
     *
     * t is computed using a smoothstep curve for a more natural-looking transition.
     *
     * Bilinear blending handles corners (both X and Z in their blend zones) correctly.
     *
     * @param context   the block coordinate context
     * @param evaluator the underlying noise evaluator
     * @return blended noise value
     */
    public static double blend(FunctionContext context, java.util.function.ToDoubleFunction<FunctionContext> evaluator) {
        if (!GeolockServerConfig.enableWorldLooping || context instanceof OffsetContext) {
            return evaluator.applyAsDouble(context);
        }

        double x = context.blockX();
        double z = context.blockZ();
        double width = GeolockServerConfig.worldBoundaryWidth;
        double halfW = width / 2.0;
        double blendW = BLEND_WIDTH;

        // Compute raw blend factors [0, 1] for each axis.
        // t=0 means "fully local noise", t=1 means "fully wrapped noise".
        double tx = 0.0;
        double tz = 0.0;

        if (x > halfW - blendW) {
            tx = (x - (halfW - blendW)) / blendW;
        } else if (x < -halfW + blendW) {
            tx = ((-halfW + blendW) - x) / blendW;
        }

        if (z > halfW - blendW) {
            tz = (z - (halfW - blendW)) / blendW;
        } else if (z < -halfW + blendW) {
            tz = ((-halfW + blendW) - z) / blendW;
        }

        // Clamp to [0, 1] and apply smoothstep for a more natural-looking blend
        tx = smoothstep(Math.max(0.0, Math.min(1.0, tx)));
        tz = smoothstep(Math.max(0.0, Math.min(1.0, tz)));

        // No blending needed in the center
        if (tx == 0.0 && tz == 0.0) {
            return evaluator.applyAsDouble(context);
        }

        // Direction of the wrap offset: positive side wraps to negative and vice versa
        double offsetX = (x >= 0) ? -width : width;
        double offsetZ = (z >= 0) ? -width : width;

        // Evaluate at the four corners of the bilinear blend:
        // v00 = local position
        // v10 = X-wrapped position (only if tx > 0)
        // v01 = Z-wrapped position (only if tz > 0)
        // v11 = XZ-wrapped position (only if both > 0)
        double v00 = evaluator.applyAsDouble(context);

        double v10 = v00;
        if (tx > 0.0) {
            v10 = evaluator.applyAsDouble(new OffsetContext(context, offsetX, 0));
        }

        double v01 = v00;
        if (tz > 0.0) {
            v01 = evaluator.applyAsDouble(new OffsetContext(context, 0, offsetZ));
        }

        double v11 = v00;
        if (tx > 0.0 && tz > 0.0) {
            v11 = evaluator.applyAsDouble(new OffsetContext(context, offsetX, offsetZ));
        }

        // Bilinear interpolation
        return (1.0 - tx) * (1.0 - tz) * v00
             +        tx  * (1.0 - tz) * v10
             + (1.0 - tx) *        tz  * v01
             +        tx  *        tz  * v11;
    }

    /** Classic smoothstep: 3t² - 2t³. Input and output in [0, 1]. */
    private static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    // Kept for external compatibility
    public static double remap(FunctionContext context, java.util.function.ToDoubleFunction<FunctionContext> evaluator) {
        return blend(context, evaluator);
    }

    public static int getWrappedX(int x, int z) { return x; }
    public static int getWrappedZ(int x, int z) { return z; }

    /**
     * A FunctionContext that offsets blockX and blockZ by a fixed amount.
     * Used to evaluate noise at the wrapped (opposite-side) position.
     * Also serves as a recursion guard — blend() skips re-processing OffsetContexts.
     */
    public static class OffsetContext implements FunctionContext {
        private final FunctionContext original;
        private final double offsetX;
        private final double offsetZ;

        public OffsetContext(FunctionContext original, double offsetX, double offsetZ) {
            this.original = original;
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
        }

        @Override public int blockX() { return (int) Math.round(original.blockX() + offsetX); }
        @Override public int blockY() { return original.blockY(); }
        @Override public int blockZ() { return (int) Math.round(original.blockZ() + offsetZ); }
    }

    /**
     * Alias for OffsetContext, kept for API compatibility.
     */
    public static class ToroidalContext extends OffsetContext {
        public ToroidalContext(FunctionContext original, int remappedX, int remappedZ) {
            super(original,
                remappedX - original.blockX(),
                remappedZ - original.blockZ());
        }
    }
}
