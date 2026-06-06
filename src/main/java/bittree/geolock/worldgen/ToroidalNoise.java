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

    @Override public double minValue() { return this.originalNoise.value().minValue(); }
    @Override public double maxValue() { return this.originalNoise.value().maxValue(); }
    @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return KeyDispatchDataCodec.of(CODEC); }

    public Holder<DensityFunction> getOriginalNoise() { return this.originalNoise; }
    public double getWorldWidth() { return this.worldWidth; }
    public boolean isFinalDensity() { return this.isFinalDensity; }

    private static double smoothstep(double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        return t * t * (3.0 - 2.0 * t);
    }

    private static final ThreadLocal<ReusableOffsetContext> REUSABLE_CONTEXT = ThreadLocal.withInitial(ReusableOffsetContext::new);

    public static double blend(FunctionContext context, java.util.function.ToDoubleFunction<FunctionContext> evaluator) {
        if (context instanceof OffsetContext) {
            return evaluator.applyAsDouble(context);
        }

        int x = context.blockX();
        int z = context.blockZ();

        int w = (int) GeolockServerConfig.worldBoundaryWidth;
        int halfW = w / 2;
        int blendW = (int) GeolockServerConfig.blendZoneWidth;

        // 1. Establish the true asymmetric block boundaries (e.g., 9999 and -10000)
        int xMax = halfW - 1;
        int xMin = -halfW;
        
        int startPos = xMax - blendW + 1;
        int startNeg = xMin + blendW - 1;

        double wx = 0.0;
        int offsetX = 0;

        // Calculate X edge weights mapping exactly to 0.5 at the border faces
        if (x >= startPos) {
            double t = (double) (x - startPos) / (blendW - 1);
            wx = 0.5 * smoothstep(t);
            offsetX = -(w - 1); // e.g., 9999 becomes -10000
        } else if (x <= startNeg) {
            double t = (double) (startNeg - x) / (blendW - 1);
            wx = 0.5 * smoothstep(t);
            offsetX = w - 1;    // e.g., -10000 becomes 9999
        }

        double wz = 0.0;
        int offsetZ = 0;

        // Calculate Z edge weights mapping exactly to 0.5 at the border faces
        if (z >= startPos) {
            double t = (double) (z - startPos) / (blendW - 1);
            wz = 0.5 * smoothstep(t);
            offsetZ = -(w - 1);
        } else if (z <= startNeg) {
            double t = (double) (startNeg - z) / (blendW - 1);
            wz = 0.5 * smoothstep(t);
            offsetZ = w - 1;
        }

        // Fast path for 98% of the world. Keeps performance maxed out.
        if (wx == 0.0 && wz == 0.0) {
            return evaluator.applyAsDouble(context);
        }

        // --- THE MATRIX FADE IMPLEMENTATION ---
        
        // Step A: Calculate the cached, interpolated vanilla value
        double v00_cache = evaluator.applyAsDouble(context);

        ReusableOffsetContext reusable = REUSABLE_CONTEXT.get();

        // Step B: Calculate the exact, non-cached matrix values for all 4 quadrants
        reusable.set(context, 0, 0);
        double v00_exact = evaluator.applyAsDouble(reusable);

        double v10_exact = v00_exact;
        if (wx > 0.0) {
            reusable.set(context, offsetX, 0);
            v10_exact = evaluator.applyAsDouble(reusable);
        }

        double v01_exact = v00_exact;
        if (wz > 0.0) {
            reusable.set(context, 0, offsetZ);
            v01_exact = evaluator.applyAsDouble(reusable);
        }

        double v11_exact = v00_exact;
        if (wx > 0.0 && wz > 0.0) {
            reusable.set(context, offsetX, offsetZ);
            v11_exact = evaluator.applyAsDouble(reusable);
        }

        // Step C: Compile the exact bilinear blend
        double exact_blend = (1.0 - wx) * (1.0 - wz) * v00_exact
                           +        wx  * (1.0 - wz) * v10_exact
                           + (1.0 - wx) * wz  * v01_exact
                           +        wx  * wz  * v11_exact;

        // Step D: Morph from cached (inner edge) to exact (portal face)
        // fade becomes 0.0 at the inner boundary, 1.0 at the portal edges and corners
        double fade = Math.max(wx, wz) * 2.0;
        
        return (1.0 - fade) * v00_cache + fade * exact_blend;
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
        protected int offsetX;
        protected int offsetZ;

        public OffsetContext(FunctionContext original, int offsetX, int offsetZ) {
            this.original = original;
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
        }

        @Override public int blockX() { return original.blockX() + offsetX; }
        @Override public int blockY() { return original.blockY(); }
        @Override public int blockZ() { return original.blockZ() + offsetZ; }

        @Override public double x() { return (double) blockX(); }
        @Override public double y() { return (double) blockY(); }
        @Override public double z() { return (double) blockZ(); }
        
        @Override
        public net.minecraft.world.level.levelgen.blending.Blender getBlender() {
            return original != null ? original.getBlender() : net.minecraft.world.level.levelgen.blending.Blender.empty();
        }
    }

    public static class ReusableOffsetContext extends OffsetContext {
        public ReusableOffsetContext() {
            super(null, 0, 0);
        }

        public void set(FunctionContext original, int offsetX, int offsetZ) {
            this.original = original;
            this.offsetX = offsetX;
            this.offsetZ = offsetZ;
        }
    }
}
