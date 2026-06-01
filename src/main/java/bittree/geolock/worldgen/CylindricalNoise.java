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
 * Maps Cartesian coordinate queries on X to cylindrical space
 * to enforce procedurally identical boundaries.
 */
public class CylindricalNoise implements DensityFunction {

    private final Holder<DensityFunction> originalNoise;
    private final double worldWidth;
    private final double radius;

    public static final MapCodec<CylindricalNoise> CODEC = RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            DensityFunction.CODEC.fieldOf("noise").forGetter(CylindricalNoise::getOriginalNoise),
            Codec.DOUBLE.fieldOf("world_width").forGetter(CylindricalNoise::getWorldWidth)
        ).apply(instance, CylindricalNoise::new)
    );

    public CylindricalNoise(Holder<DensityFunction> originalNoise, double worldWidth) {
        this.originalNoise = originalNoise;
        this.worldWidth = worldWidth;
        this.radius = (worldWidth / 2.0) / Math.PI;
    }

    @Override
    public double compute(FunctionContext context) {
        double x = context.blockX();
        double y = context.blockY();
        double z = context.blockZ();

        double currentWidth = GeolockServerConfig.enableWorldLooping ? GeolockServerConfig.worldBoundaryWidth : this.worldWidth;
        double halfWidth = currentWidth / 2.0;
        double currentRadius = (currentWidth / 2.0) / Math.PI;

        // Calculate the angular coordinate mapping
        double theta = ((x + halfWidth) / currentWidth) * 2.0 * Math.PI;

        // Project coordinate axis into wrapped cylindrical vectors
        double nx = currentRadius * Math.cos(theta);
        double nz = currentRadius * Math.sin(theta);

        // Retain standard elevation (y) and sample from the modified context
        return this.originalNoise.value().compute(new CylindricalContext(nx, y, nz));
    }

    @Override
    public void fillArray(double[] densities, ContextProvider provider) {
        provider.fillAllDirectly(densities, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return new CylindricalNoise(Holder.direct(this.originalNoise.value().mapAll(visitor)), this.worldWidth);
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

    private record CylindricalContext(double xDouble, double yDouble, double zDouble) implements FunctionContext {
        @Override public int blockX() { return (int) Math.round(xDouble); }
        @Override public int blockY() { return (int) Math.round(yDouble); }
        @Override public int blockZ() { return (int) Math.round(zDouble); }
    }
}
