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
        // Clamp bounds to prevent weight over-scaling
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

        // Use absolute center of the voxel column to enforce grid symmetry
        double x = context.blockX() + 0.5;
        double z = context.blockZ() + 0.5;

        // Wrap to local boundary coordinates
        double xLocal = ((x + halfW) % w + w) % w - halfW;
        double zLocal = ((z + halfW) % w + w) % w - halfW;

        double wx = 0.0;
        double offsetX = 0.0;
        if (xLocal > halfW - blendW) {
            wx = 0.5 * smoothstep((xLocal - (halfW - blendW)) / blendW);
            offsetX = -w;
        } else if (xLocal < -halfW + blendW) {
            wx = 0.5 * smoothstep((-halfW + blendW - xLocal) / blendW);
            offsetX = w;
        }

        double wz = 0.0;
        double offsetZ = 0.0;
        if (zLocal > halfW - blendW) {
            wz = 0.5 * smoothstep((zLocal - (halfW - blendW)) / blendW);
            offsetZ = -w;
        } else if (zLocal < -halfW + blendW) {
            wz = 0.5 * smoothstep((-halfW + blendW - zLocal) / blendW);
            offsetZ = w;
        }

        // Convert double deltas back to standard context offsets for evaluation
        double dx = xLocal - (context.blockX() + 0.5);
        double dz = zLocal - (context.blockZ() + 0.5);

        ReusableOffsetContext reusable = REUSABLE_CONTEXT.get();

        // If we are completely outside the blend zone, return raw cached noise for high performance
        if (wx == 0.0 && wz == 0.0) {
            if (dx == 0.0 && dz == 0.0) {
                return evaluator.applyAsDouble(context);
            } else {
                reusable.set(context, dx, dz);
                return evaluator.applyAsDouble(reusable);
            }
        }

        // --- CRITICAL CACHE ISOLATION FIX ---
        // We are in the blend zone. We MUST bypass the NoiseChunk trilinear cache for ALL samples
        // (including the local base sample v00) to ensure mathematical alignment.
        reusable.set(context, dx, dz);
        double v00 = evaluator.applyAsDouble(reusable); // Cache forcefully bypassed

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

        // Resolve bilinear interpolation array
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
}
