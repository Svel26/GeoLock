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
    private final double halfWidth;
    private final double currentRadius;
    private final double twoPiOverWidth;

    public static final MapCodec<CylindricalNoise> CODEC = RecordCodecBuilder.mapCodec(instance ->
        instance.group(
            DensityFunction.CODEC.fieldOf("noise").forGetter(CylindricalNoise::getOriginalNoise),
            Codec.DOUBLE.fieldOf("world_width").forGetter(CylindricalNoise::getWorldWidth)
        ).apply(instance, CylindricalNoise::new)
    );

    public CylindricalNoise(Holder<DensityFunction> originalNoise, double worldWidth) {
        this.originalNoise = originalNoise;
        this.worldWidth = worldWidth;
        double width = GeolockServerConfig.enableWorldLooping ? GeolockServerConfig.worldBoundaryWidth : worldWidth;
        this.halfWidth = width / 2.0;
        this.currentRadius = this.halfWidth / Math.PI;
        this.twoPiOverWidth = 2.0 * Math.PI / width;
    }

    @Override
    public double compute(FunctionContext context) {
        double x = context.blockX();
        double y = context.blockY();
        double z = context.blockZ();

        if (!GeolockServerConfig.enableWorldLooping) {
            return this.originalNoise.value().compute(context);
        }

        // Calculate the angular coordinate mappings
        double thetaX = (x + this.halfWidth) * this.twoPiOverWidth;
        double thetaZ = (z + this.halfWidth) * this.twoPiOverWidth;

        // Project coordinate axis into wrapped cylindrical/toroidal vectors
        double nx = this.currentRadius * net.minecraft.util.Mth.cos((float) thetaX);
        double nz = this.currentRadius * net.minecraft.util.Mth.sin((float) thetaX) 
                  + this.currentRadius * net.minecraft.util.Mth.cos((float) thetaZ);

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

    private static boolean staticInitialized = false;
    private static double staticHalfWidth;
    private static double staticCurrentRadius;
    private static double staticTwoPiOverWidth;

    private static void initStatic() {
        if (!staticInitialized) {
            double width = GeolockServerConfig.worldBoundaryWidth;
            staticHalfWidth = width / 2.0;
            staticCurrentRadius = staticHalfWidth / Math.PI;
            staticTwoPiOverWidth = 2.0 * Math.PI / width;
            staticInitialized = true;
        }
    }

    public static int getWrappedX(int x, int z) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return x;
        }
        initStatic();
        double thetaX = (x + staticHalfWidth) * staticTwoPiOverWidth;
        double nx = staticCurrentRadius * net.minecraft.util.Mth.cos((float) thetaX);
        return (int) Math.round(nx);
    }

    public static int getWrappedZ(int x, int z) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return z;
        }
        initStatic();
        double thetaX = (x + staticHalfWidth) * staticTwoPiOverWidth;
        double thetaZ = (z + staticHalfWidth) * staticTwoPiOverWidth;
        double nz = staticCurrentRadius * net.minecraft.util.Mth.sin((float) thetaX) 
                  + staticCurrentRadius * net.minecraft.util.Mth.cos((float) thetaZ);
        return (int) Math.round(nz);
    }

    private record CylindricalContext(double xDouble, double yDouble, double zDouble) implements FunctionContext {
        @Override public int blockX() { return (int) Math.round(xDouble); }
        @Override public int blockY() { return (int) Math.round(yDouble); }
        @Override public int blockZ() { return (int) Math.round(zDouble); }
    }
}
