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
 * Applies bilinear blending near world boundaries to create a seamless loop
 * without coordinate stretching, and enforces a void outside the boundary.
 */
public class CylindricalNoise implements DensityFunction {

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

        // Outside boundary is a void for finalDensity
        if (this.isFinalDensity && (Math.abs(context.blockX()) > this.halfWidth || Math.abs(context.blockZ()) > this.halfWidth)) {
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

    public static double blend(FunctionContext context, java.util.function.ToDoubleFunction<FunctionContext> evaluator) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return evaluator.applyAsDouble(context);
        }

        double x = context.blockX();
        double z = context.blockZ();
        double width = GeolockServerConfig.worldBoundaryWidth;
        double halfW = width / 2.0;
        double blendW = 1000.0;

        double tx = 0.0;
        double tz = 0.0;

        if (x > halfW - blendW) {
            tx = (x - (halfW - blendW)) / blendW;
        } else if (x < -halfW + blendW) {
            tx = (-halfW + blendW - x) / blendW;
        }

        if (z > halfW - blendW) {
            tz = (z - (halfW - blendW)) / blendW;
        } else if (z < -halfW + blendW) {
            tz = (-halfW + blendW - z) / blendW;
        }

        if (tx == 0.0 && tz == 0.0) {
            return evaluator.applyAsDouble(context);
        }

        double offsetX = (x > 0) ? -width : width;
        double offsetZ = (z > 0) ? -width : width;

        FunctionContext context00 = context;
        FunctionContext context10 = tx > 0.0 ? new OffsetContext(context, offsetX, 0) : context;
        FunctionContext context01 = tz > 0.0 ? new OffsetContext(context, 0, offsetZ) : context;
        FunctionContext context11 = (tx > 0.0 && tz > 0.0) ? new OffsetContext(context, offsetX, offsetZ) : context;

        double v00 = evaluator.applyAsDouble(context00);

        double v10 = v00;
        if (tx > 0.0) {
            v10 = evaluator.applyAsDouble(context10);
        }

        double v01 = v00;
        if (tz > 0.0) {
            v01 = evaluator.applyAsDouble(context01);
        }

        double v11 = v00;
        if (tx > 0.0 && tz > 0.0) {
            v11 = evaluator.applyAsDouble(context11);
        }

        return (1.0 - tx) * (1.0 - tz) * v00 
             + tx * (1.0 - tz) * v10 
             + (1.0 - tx) * tz * v01 
             + tx * tz * v11;
    }

    public static int getWrappedX(int x, int z) {
        return x;
    }

    public static int getWrappedZ(int x, int z) {
        return z;
    }

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
}
