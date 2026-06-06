package bittree.geolock.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

import bittree.geolock.GeolockServerConfig;

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

    private static double smoothstep(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return t * t * (3.0 - 2.0 * t);
    }

    private static final ThreadLocal<ReusableOffsetContext> REUSABLE_CONTEXT = ThreadLocal.withInitial(ReusableOffsetContext::new);

    public static double blend(FunctionContext context, java.util.function.ToDoubleFunction<FunctionContext> evaluator) {
        if (!GeolockServerConfig.enableWorldLooping || context instanceof OffsetContext) {
            return evaluator.applyAsDouble(context);
        }

        double w = GeolockServerConfig.worldBoundaryWidth;
        double halfW = w / 2.0;
        double blendW = GeolockServerConfig.blendZoneWidth;

        // 1. Calculate from the exact center of the block for perfect mathematical symmetry
        double x_center = context.blockX() + 0.5;
        double z_center = context.blockZ() + 0.5;

        // 2. Wrap continuous coordinate to local bounds
        double xLocal = ((x_center + halfW) % w + w) % w - halfW;
        double zLocal = ((z_center + halfW) % w + w) % w - halfW;

        // 3. We use (blendW - 0.5) so that 't' hits exactly 1.0 at the final boundary block faces (9999.5 / -9999.5).
        // This guarantees wx and wz lock to exactly 0.5, creating identical averages on both sides of the portal.
        double effectiveBlendW = blendW - 0.5;

        double wx = 0.0;
        double offsetX = 0.0;
        if (xLocal > halfW - blendW) {
            double t = (xLocal - (halfW - blendW)) / effectiveBlendW;
            wx = 0.5 * smoothstep(t);
            offsetX = -(w - 1.0); // Map block 9999 directly to -10000
        } else if (xLocal < -halfW + blendW) {
            double t = ((-halfW + blendW) - xLocal) / effectiveBlendW;
            wx = 0.5 * smoothstep(t);
            offsetX = w - 1.0;    // Map block -10000 directly to 9999
        }

        double wz = 0.0;
        double offsetZ = 0.0;
        if (zLocal > halfW - blendW) {
            double t = (zLocal - (halfW - blendW)) / effectiveBlendW;
            wz = 0.5 * smoothstep(t);
            offsetZ = -(w - 1.0);
        } else if (zLocal < -halfW + blendW) {
            double t = ((-halfW + blendW) - zLocal) / effectiveBlendW;
            wz = 0.5 * smoothstep(t);
            offsetZ = w - 1.0;
        }

        // Delta to correctly wrap out-of-bounds structure generation queries (like border trees)
        double dx = xLocal - x_center;
        double dz = zLocal - z_center;

        // Fast path for 98% of the world. Keeps performance maxed out.
        if (wx == 0.0 && wz == 0.0 && dx == 0.0 && dz == 0.0) {
            return evaluator.applyAsDouble(context);
        }

        ReusableOffsetContext reusable = REUSABLE_CONTEXT.get();

        // --- THE CACHE CONTINUITY FIX ---
        // v00 MUST use the original context if within bounds. This preserves the NoiseChunk 
        // interpolation cache and completely prevents the world from tearing at the 128-block line.
        double v00;
        if (dx == 0.0 && dz == 0.0) {
            v00 = evaluator.applyAsDouble(context);
        } else {
            reusable.set(context, dx, dz);
            v00 = evaluator.applyAsDouble(reusable);
        }

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

        return (1.0 - wx) * (1.0 - wz) * v00
             +        wx  * (1.0 - wz) * v10
             + (1.0 - wx) * wz  * v01
             +        wx  * wz  * v11;
    }

    public static double remap(FunctionContext context, java.util.function.ToDoubleFunction<FunctionContext> evaluator) {
        return blend(context, evaluator);
    }

    public interface ToroidalFunctionContext extends FunctionContext {
        double x();
        double y();
        double z();
    }

    public static class OffsetContext implements ToroidalFunctionContext {
        protected FunctionContext original;
        protected double offsetX;
        protected double offsetZ;

        public OffsetContext(FunctionContext original, double offsetX, double offsetZ) {
            this.original = original;
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
        }

        @Override
        public int blockX() { return (int) Math.round(original.blockX() + offsetX); }

        @Override
        public int blockY() { return original.blockY(); }

        @Override
        public int blockZ() { return (int) Math.round(original.blockZ() + offsetZ); }

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

    public static class ReusableOffsetContext extends OffsetContext {
        public ReusableOffsetContext() {
            super(null, 0.0, 0.0);
        }

        public void set(FunctionContext original, double offsetX, double offsetZ) {
            this.original = original;
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
        }
    }
}
