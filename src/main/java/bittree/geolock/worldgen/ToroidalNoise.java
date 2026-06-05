package bittree.geolock.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

import bittree.geolock.GeolockServerConfig;

/**
 * ToroidalNoise provides seamless world wrapping for Minecraft's noise-based terrain generation.
 *
 * This class serves two purposes:
 * 1. As a DensityFunction wrapper (for NoiseRouter-level wrapping, currently disabled)
 * 2. As a utility providing the blend()/remap() methods used by leaf-level mixins
 *
 * The core algorithm:
 * - Near the world boundary (±halfW), noise values are bilinearly interpolated
 *   between the original position and the wrapped-around position
 * - A smoothstep function provides C1-continuous blending over the blend zone
 * - The OffsetContext allows evaluating noise at shifted positions while
 *   preserving the continuous coordinate system needed by noise samplers
 */
public class ToroidalNoise implements DensityFunction {

    private final Holder<DensityFunction> originalNoise;
    private final double worldWidth;
    private final boolean isFinalDensity;

    public static final MapCodec<ToroidalNoise> CODEC = RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            DensityFunction.CODEC.fieldOf("noise").forGetter(ToroidalNoise::getOriginalNoise),
            Codec.DOUBLE.fieldOf("world_width").forGetter(ToroidalNoise::getWorldWidth),
            Codec.BOOL.optionalFieldOf("is_final_density", false).forGetter(ToroidalNoise::isFinalDensity)
        ).apply(instance, ToroidalNoise::new)
    );

    public ToroidalNoise(Holder<DensityFunction> originalNoise, double worldWidth) {
        this(originalNoise, worldWidth, false);
    }

    public ToroidalNoise(Holder<DensityFunction> originalNoise, double worldWidth, boolean isFinalDensity) {
        this.originalNoise = originalNoise;
        this.worldWidth = worldWidth;
        this.isFinalDensity = isFinalDensity;
    }

    @Override
    public double compute(FunctionContext context) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return this.originalNoise.value().compute(context);
        }
        return blend(context, ctx -> this.originalNoise.value().compute(ctx));
    }

    @Override
    public void fillArray(double[] densities, ContextProvider provider) {
        provider.fillAllDirectly(densities, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new ToroidalNoise(Holder.direct(this.originalNoise.value().mapAll(visitor)), this.worldWidth, this.isFinalDensity);
    }

    @Override
    public double minValue() { return this.originalNoise.value().minValue(); }

    @Override
    public double maxValue() { return this.originalNoise.value().maxValue(); }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() { return KeyDispatchDataCodec.of(CODEC); }

    public Holder<DensityFunction> getOriginalNoise() { return this.originalNoise; }
    public double getWorldWidth() { return this.worldWidth; }
    public boolean isFinalDensity() { return this.isFinalDensity; }

    /**
     * C1-continuous smoothstep function. Clamps t to [0,1] and returns 3t² - 2t³.
     */
    private static double smoothstep(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return t * t * (3.0 - 2.0 * t);
    }

    private static final ThreadLocal<ReusableOffsetContext> REUSABLE_CONTEXT = ThreadLocal.withInitial(ReusableOffsetContext::new);

    /**
     * Thread-local counter used to generate unique cache-busting tokens.
     * Each call to blend() increments this, ensuring the NoiseChunk trilinear
     * cache sees a different context and cannot return stale values.
     */
    private static final ThreadLocal<Integer> CACHE_BUSTER = ThreadLocal.withInitial(() -> 0);

    /**
     * Core blending function. Evaluates noise at the original position and,
     * if within the blend zone, at wrapped positions, then bilinearly interpolates.
     *
     * @param context  The original FunctionContext (block coordinates)
     * @param evaluator  A function that evaluates noise given a FunctionContext
     * @return The blended noise value
     */
    public static double blend(FunctionContext context, java.util.function.ToDoubleFunction<FunctionContext> evaluator) {
        if (!GeolockServerConfig.enableWorldLooping || context instanceof OffsetContext) {
            return evaluator.applyAsDouble(context);
        }

        double w = GeolockServerConfig.worldBoundaryWidth;
        double halfW = w / 2.0;
        double blendW = GeolockServerConfig.blendZoneWidth;

        // Use center-of-block coordinates for noise evaluation symmetry
        double x = context.blockX() + 0.5;
        double z = context.blockZ() + 0.5;

        // Wrap to local boundary coordinates: range [-halfW, halfW)
        double xLocal = ((x + halfW) % w + w) % w - halfW;
        double zLocal = ((z + halfW) % w + w) % w - halfW;

        // Compute blend weights and wrap offsets for X axis
        double wx = 0.0;
        double offsetX = 0.0;
        if (xLocal > halfW - blendW) {
            wx = 0.5 * smoothstep((xLocal - (halfW - blendW)) / blendW);
            offsetX = -w;
        } else if (xLocal < -halfW + blendW) {
            wx = 0.5 * smoothstep((-halfW + blendW - xLocal) / blendW);
            offsetX = w;
        }

        // Compute blend weights and wrap offsets for Z axis
        double wz = 0.0;
        double offsetZ = 0.0;
        if (zLocal > halfW - blendW) {
            wz = 0.5 * smoothstep((zLocal - (halfW - blendW)) / blendW);
            offsetZ = -w;
        } else if (zLocal < -halfW + blendW) {
            wz = 0.5 * smoothstep((-halfW + blendW - zLocal) / blendW);
            offsetZ = w;
        }

        // Delta between wrapped center and original center
        double dx = xLocal - (context.blockX() + 0.5);
        double dz = zLocal - (context.blockZ() + 0.5);

        // Generate a unique cache-busting token for this blend evaluation
        int cacheToken = CACHE_BUSTER.get();
        CACHE_BUSTER.set(cacheToken + 1);

        ReusableOffsetContext reusable = REUSABLE_CONTEXT.get();

        // Outside blend zone: just evaluate at the (possibly wrapped) position
        if (wx == 0.0 && wz == 0.0) {
            if (dx == 0.0 && dz == 0.0) {
                return evaluator.applyAsDouble(context);
            } else {
                reusable.set(context, dx, dz, cacheToken);
                return evaluator.applyAsDouble(reusable);
            }
        }

        // --- BLEND ZONE: Evaluate all 4 corners with cache busting ---
        // All samples go through OffsetContext to ensure the NoiseChunk
        // trilinear cache cannot return stale unwrapped values.

        reusable.set(context, dx, dz, cacheToken);
        double v00 = evaluator.applyAsDouble(reusable);

        double v10 = v00;
        if (wx > 0.0) {
            reusable.set(context, dx + offsetX, dz, cacheToken + 1);
            v10 = evaluator.applyAsDouble(reusable);
        }

        double v01 = v00;
        if (wz > 0.0) {
            reusable.set(context, dx, dz + offsetZ, cacheToken + 2);
            v01 = evaluator.applyAsDouble(reusable);
        }

        double v11 = v00;
        if (wx > 0.0 && wz > 0.0) {
            reusable.set(context, dx + offsetX, dz + offsetZ, cacheToken + 3);
            v11 = evaluator.applyAsDouble(reusable);
        }

        // Bilinear interpolation
        return (1.0 - wx) * (1.0 - wz) * v00
             +        wx  * (1.0 - wz) * v10
             + (1.0 - wx) * wz  * v01
             +        wx  * wz  * v11;
    }

    /**
     * Alias for blend(). Used by mixins for clarity.
     */
    public static double remap(FunctionContext context, java.util.function.ToDoubleFunction<FunctionContext> evaluator) {
        return blend(context, evaluator);
    }

    public interface ToroidalFunctionContext extends FunctionContext {
        double x();
        double y();
        double z();
    }

    /**
     * A FunctionContext that applies an offset to the original context's coordinates.
     * This is used to evaluate noise at wrapped-around positions.
     *
     * The cacheBuster field ensures each evaluation creates a unique context identity,
     * preventing the NoiseChunk trilinear cache from returning stale values.
     */
    public static class OffsetContext implements ToroidalFunctionContext {
        protected FunctionContext original;
        protected double offsetX;
        protected double offsetZ;
        protected int cacheBuster;

        public OffsetContext(FunctionContext original, double offsetX, double offsetZ, int cacheBuster) {
            this.original = original;
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
            this.cacheBuster = cacheBuster;
        }

        /**
         * Returns the wrapped block X coordinate using floor-based conversion.
         * For large offsets (world-scale wrapping), uses exact integer arithmetic
         * to avoid floating-point rounding errors at the boundary.
         */
        @Override
        public int blockX() {
            double raw = (double)original.blockX() + offsetX;
            // For world-scale offsets, use exact integer arithmetic
            if (Math.abs(offsetX) > 1000.0) {
                return (int) Math.round(raw);
            }
            return (int) Math.floor(raw + 0.5);
        }

        @Override
        public int blockY() { return original.blockY(); }

        /**
         * Returns the wrapped block Z coordinate using floor-based conversion.
         */
        @Override
        public int blockZ() {
            double raw = (double)original.blockZ() + offsetZ;
            if (Math.abs(offsetZ) > 1000.0) {
                return (int) Math.round(raw);
            }
            return (int) Math.floor(raw + 0.5);
        }

        @Override
        public double x() {
            if (original instanceof ToroidalFunctionContext tc) {
                return tc.x() + offsetX;
            }
            return (double)original.blockX() + offsetX;
        }

        @Override
        public double y() {
            if (original instanceof ToroidalFunctionContext tc) {
                return tc.y();
            }
            return original.blockY();
        }

        @Override
        public double z() {
            if (original instanceof ToroidalFunctionContext tc) {
                return tc.z() + offsetZ;
            }
            return (double)original.blockZ() + offsetZ;
        }
    }

    /**
     * Reusable OffsetContext for thread-local use, avoiding allocation in hot paths.
     */
    public static class ReusableOffsetContext extends OffsetContext {
        public ReusableOffsetContext() {
            super(null, 0, 0, 0);
        }

        public void set(FunctionContext original, double offsetX, double offsetZ, int cacheBuster) {
            this.original = original;
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
            this.cacheBuster = cacheBuster;
        }
    }
}
