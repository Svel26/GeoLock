package bittree.geolock.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.Holder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

public class ToroidalNoise implements DensityFunction {
    private final Holder<DensityFunction> originalNoise;
    private final double worldWidth;
    private final double blendZoneWidth;

    public static final MapCodec<ToroidalNoise> CODEC = RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            DensityFunction.CODEC.fieldOf("noise").forGetter(ToroidalNoise::getOriginalNoise),
            Codec.DOUBLE.fieldOf("world_width").forGetter(ToroidalNoise::getWorldWidth),
            Codec.DOUBLE.fieldOf("blend_zone_width").forGetter(ToroidalNoise::getBlendZoneWidth)
        ).apply(instance, ToroidalNoise::new)
    );

    public ToroidalNoise(Holder<DensityFunction> originalNoise, double worldWidth, double blendZoneWidth) {
        this.originalNoise = originalNoise;
        this.worldWidth = worldWidth;
        this.blendZoneWidth = blendZoneWidth;
    }

    public Holder<DensityFunction> getOriginalNoise() {
        return this.originalNoise;
    }

    public double getWorldWidth() {
        return this.worldWidth;
    }

    public double getBlendZoneWidth() {
        return this.blendZoneWidth;
    }

    @Override
    public double compute(FunctionContext context) {
        return blend(context, ctx -> this.originalNoise.value().compute(ctx));
    }

    @Override
    public void fillArray(double[] array, ContextProvider contextProvider) {
        contextProvider.fillAllDirectly(array, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new ToroidalNoise(
            Holder.direct(this.originalNoise.value().mapAll(visitor)),
            this.worldWidth,
            this.blendZoneWidth
        ));
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

    private static double smoothstep(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return t * t * (3.0 - 2.0 * t);
    }

    private static final ThreadLocal<ReusableOffsetContext> REUSABLE_CONTEXT = 
        ThreadLocal.withInitial(ReusableOffsetContext::new);

    private double blend(FunctionContext context, java.util.function.ToDoubleFunction<FunctionContext> evaluator) {
        // If the context is already an OffsetContext (to prevent infinite loops) or if world looping is disabled, return directly
        if (context instanceof ReusableOffsetContext) {
            return evaluator.applyAsDouble(context);
        }

        double w = this.worldWidth;
        double halfW = w / 2.0;
        double blendW = this.blendZoneWidth;

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
            offsetX = -w;
        } else if (xLocal < -halfW + blendW) {
            double t = ((-halfW + blendW) - xLocal) / effectiveBlendW;
            wx = 0.5 * smoothstep(t);
            offsetX = w;
        }

        double wz = 0.0;
        double offsetZ = 0.0;
        if (zLocal > halfW - blendW) {
            double t = (zLocal - (halfW - blendW)) / effectiveBlendW;
            wz = 0.5 * smoothstep(t);
            offsetZ = -w;
        } else if (zLocal < -halfW + blendW) {
            double t = ((-halfW + blendW) - zLocal) / effectiveBlendW;
            wz = 0.5 * smoothstep(t);
            offsetZ = w;
        }

        // Fast path for 98% of the world. Keeps performance maxed out.
        if (wx == 0.0 && wz == 0.0) {
            return evaluator.applyAsDouble(context);
        }

        // --- THE CACHE CONTINUITY FIX ---
        // v00 MUST use the original context directly. This preserves the NoiseChunk
        // interpolation cache and completely prevents the world from tearing at the 128-block line.
        double v00 = evaluator.applyAsDouble(context);
        double v10 = v00;
        double v01 = v00;
        double v11 = v00;

        ReusableOffsetContext reusable = REUSABLE_CONTEXT.get();

        if (wx > 0.0) {
            reusable.set(context, offsetX, 0.0);
            v10 = evaluator.applyAsDouble(reusable);
        }

        if (wz > 0.0) {
            reusable.set(context, 0.0, offsetZ);
            v01 = evaluator.applyAsDouble(reusable);
        }

        if (wx > 0.0 && wz > 0.0) {
            reusable.set(context, offsetX, offsetZ);
            v11 = evaluator.applyAsDouble(reusable);
        }

        return (1.0 - wx) * (1.0 - wz) * v00
             + wx * (1.0 - wz) * v10
             + (1.0 - wx) * wz * v01
             + wx * wz * v11;
    }

    public interface ToroidalFunctionContext extends FunctionContext {
        double x();
        double y();
        double z();
    }

    public static class ReusableOffsetContext implements ToroidalFunctionContext {
        private FunctionContext original;
        private double offsetX;
        private double offsetZ;

        public void set(FunctionContext original, double offsetX, double offsetZ) {
            this.original = original;
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
        }

        @Override
        public int blockX() {
            return (int) Math.round(x());
        }

        @Override
        public int blockY() {
            return (int) Math.round(y());
        }

        @Override
        public int blockZ() {
            return (int) Math.round(z());
        }

        @Override
        public double x() {
            if (original instanceof ToroidalFunctionContext tc) {
                return tc.x() + offsetX;
            }
            return (double) original.blockX() + offsetX;
        }

        @Override
        public double y() {
            if (original instanceof ToroidalFunctionContext tc) {
                return tc.y();
            }
            return (double) original.blockY();
        }

        @Override
        public double z() {
            if (original instanceof ToroidalFunctionContext tc) {
                return tc.z() + offsetZ;
            }
            return (double) original.blockZ() + offsetZ;
        }

        @Override
        public net.minecraft.world.level.levelgen.blending.Blender getBlender() {
            return original != null ? original.getBlender() : net.minecraft.world.level.levelgen.blending.Blender.empty();
        }
    }
}
