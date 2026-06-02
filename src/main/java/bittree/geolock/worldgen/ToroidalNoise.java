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
public class ToroidalNoise implements DensityFunction {

    private final Holder<DensityFunction> originalNoise;
    private final double worldWidth;
    private final double halfWidth;
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
        double width = GeolockServerConfig.enableWorldLooping ? GeolockServerConfig.worldBoundaryWidth : worldWidth;
        this.halfWidth = width / 2.0;
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
     * Classic smoothstep: 3t² - 2t³. Input and output in [0, 1].
     */
    private static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double modulo(double a, double b) {
        return ((a % b) + b) % b;
    }

    private static final ThreadLocal<ReusableOffsetContext> REUSABLE_CONTEXT = ThreadLocal.withInitial(ReusableOffsetContext::new);

    /**
     * Cross-fade blend: in the outer BLEND_WIDTH blocks on each axis, the noise
     * smoothly transitions from the local value to a 50/50 blend at the boundary,
     * matching the opposite side of the boundary perfectly.
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

        // 1. Maintain full floating-point precision for sampling symmetry
        double x = context.blockX() + 0.5;
        double z = context.blockZ() + 0.5;
        double w = GeolockServerConfig.worldBoundaryWidth;
        double halfW = w / 2.0;
        double blendW = GeolockServerConfig.blendZoneWidth;

        // 2. Map coordinates to local range [-halfW, halfW]
        double xLocal = ((x + halfW) % w + w) % w - halfW;
        double zLocal = ((z + halfW) % w + w) % w - halfW;

        // 3. Calculate continuous X weights up to exactly 0.5 at the border
        double wx = 0.0;
        double offsetX = 0.0;
        double visualOffset = w;
        double distEdgeXPos = halfW - xLocal;
        double distEdgeXNeg = xLocal - (-halfW);
        
        if (distEdgeXPos < 0.51) {
            wx = 0.5;
            offsetX = -visualOffset;
        } else if (distEdgeXNeg < 0.51) {
            wx = 0.5;
            offsetX = visualOffset;
        } else if (xLocal > halfW - blendW) {
            double t = (xLocal - (halfW - blendW)) / blendW;
            wx = 0.5 * smoothstep(Math.min(1.0, Math.max(0.0, t)));
            offsetX = -visualOffset;
        } else if (xLocal < -halfW + blendW) {
            double t = (-halfW + blendW - xLocal) / blendW;
            wx = 0.5 * smoothstep(Math.min(1.0, Math.max(0.0, t)));
            offsetX = visualOffset;
        }

        // 4. Calculate continuous Z weights up to exactly 0.5 at the border
        double wz = 0.0;
        double offsetZ = 0.0;
        double distEdgeZPos = halfW - zLocal;
        double distEdgeZNeg = zLocal - (-halfW);

        if (distEdgeZPos < 0.51) {
            wz = 0.5;
            offsetZ = -visualOffset;
        } else if (distEdgeZNeg < 0.51) {
            wz = 0.5;
            offsetZ = visualOffset;
        } else if (zLocal > halfW - blendW) {
            double t = (zLocal - (halfW - blendW)) / blendW;
            wz = 0.5 * smoothstep(Math.min(1.0, Math.max(0.0, t)));
            offsetZ = -visualOffset;
        } else if (zLocal < -halfW + blendW) {
            double t = (-halfW + blendW - zLocal) / blendW;
            wz = 0.5 * smoothstep(Math.min(1.0, Math.max(0.0, t)));
            offsetZ = visualOffset;
        }

        // Convert double deltas back to standard context offsets for evaluation
        double dx = xLocal - (context.blockX() + 0.5);
        double dz = zLocal - (context.blockZ() + 0.5);

        ReusableOffsetContext reusable = REUSABLE_CONTEXT.get();

        if (wx == 0.0 && wz == 0.0) {
            if (dx == 0.0 && dz == 0.0) {
                return evaluator.applyAsDouble(context);
            } else {
                reusable.set(context, dx, dz);
                return evaluator.applyAsDouble(reusable);
            }
        }

        FunctionContext localCtx;
        if (dx == 0.0 && dz == 0.0) {
            localCtx = context;
        } else {
            reusable.set(context, dx, dz);
            localCtx = reusable;
        }
        double v00 = evaluator.applyAsDouble(localCtx);

        double v10 = v00;
        if (wx > 0.0) {
            reusable.set(context, dx + offsetX, dz);
            v10 = evaluator.applyAsDouble(reusable);
        }

        double v01 = v00;
        if (wz > 0.0) {
            reusable.set(context, dx, dz + offsetZ);
            v01 = evaluator.applyAsDouble(reusable);
        }

        double v11 = v00;
        if (wx > 0.0 && wz > 0.0) {
            reusable.set(context, dx + offsetX, dz + offsetZ);
            v11 = evaluator.applyAsDouble(reusable);
        }

        // Bilinear interpolation mapping
        return (1.0 - wx) * (1.0 - wz) * v00
             +        wx   * (1.0 - wz) * v10
             + (1.0 - wx) * wz   * v01
             +        wx   * wz   * v11;
    }

    // Kept for external compatibility
    public static double remap(FunctionContext context, java.util.function.ToDoubleFunction<FunctionContext> evaluator) {
        return blend(context, evaluator);
    }

    public static int getWrappedX(int x, int z) { return x; }
    public static int getWrappedZ(int x, int z) { return z; }

    /**
     * Interface for high-precision double coordinate contexts.
     */
    public interface ToroidalFunctionContext extends FunctionContext {
        double x();
        double y();
        double z();
    }

    /**
     * A FunctionContext that offsets blockX and blockZ by a fixed amount.
     * Used to evaluate noise at the wrapped (opposite-side) position.
     * Also serves as a recursion guard — blend() skips re-processing OffsetContexts.
     */
    public static class OffsetContext implements FunctionContext, ToroidalFunctionContext {
        protected FunctionContext original;
        protected double offsetX;
        protected double offsetZ;

        public OffsetContext(FunctionContext original, double offsetX, double offsetZ) {
            this.original = original;
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
        }

        @Override public int blockX() { return (int) Math.round(original.blockX() + offsetX); }
        @Override public int blockY() { return original.blockY(); }
        @Override public int blockZ() { return (int) Math.round(original.blockZ() + offsetZ); }

        @Override
        public double x() {
            if (original instanceof ToroidalFunctionContext tc) {
                return tc.x() + offsetX;
            }
            return original.blockX() + offsetX;
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
            return original.blockZ() + offsetZ;
        }
    }

    /**
     * Reusable thread-local version of OffsetContext to completely eliminate garbage collection pressure
     * during high-frequency world generation density evaluation.
     */
    public static class ReusableOffsetContext extends OffsetContext {
        public ReusableOffsetContext() {
            super(null, 0, 0);
        }

        public void set(FunctionContext original, double offsetX, double offsetZ) {
            this.original = original;
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
        }
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
