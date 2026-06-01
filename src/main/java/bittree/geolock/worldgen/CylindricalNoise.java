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
 * Uses toroidal coordinate remapping to achieve perfectly seamless world looping.
 * Each linear coordinate axis is mapped to a 2D circle, so that coordinates at
 * +halfW and -halfW map to the same point → guaranteed seamless borders.
 *
 * The remapped coordinates for X are: (R*cos(θx), R*sin(θx))
 * The remapped coordinates for Z are: (R*cos(θz), R*sin(θz))
 *
 * For a seamless torus we need 4D noise (2D per axis). Since Minecraft noise is 3D,
 * we pack the 4 circular components into 3D using an orthogonal folding:
 *   noiseX = R*(cos(θx) + cos(θz))
 *   noiseY = blockY  (unchanged — no looping on Y)
 *   noiseZ = R*(sin(θx) + sin(θz))
 *
 * Both X and Z circular components contribute to both noiseX and noiseZ, ensuring
 * seamlessness on both axes without cross-axis interference patterns.
 *
 * VOID ZONE: finalDensity returns -1000 outside the world boundary.
 */
public class CylindricalNoise implements DensityFunction {

    // The radius of the "torus" circle.
    // Larger R = higher frequency variation per block, smaller = more stretched noise.
    // We choose R such that the circle circumference equals the world width:
    //   circumference = 2πR = worldWidth  →  R = worldWidth / (2π)
    // This preserves the approximate noise frequency compared to flat (linear) sampling.
    private static final double TWO_PI = 2.0 * Math.PI;

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
        if (this.isFinalDensity && !(context instanceof ToroidalContext) &&
                (Math.abs(context.blockX()) > this.halfWidth || Math.abs(context.blockZ()) > this.halfWidth)) {
            return -1000.0;
        }

        return remap(context, ctx -> this.originalNoise.value().compute(ctx));
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
     * Remaps block coordinates to toroidal (circular) coordinates before evaluating the noise.
     *
     * The toroidal remapping maps each linear axis to a circle:
     *   θx = (blockX / worldWidth) * 2π   (period = worldWidth blocks)
     *   θz = (blockZ / worldWidth) * 2π
     *
     * The 4 circular components (cos/sin for each axis) are packed into 3D noise coords:
     *   noiseX = R * (cos(θx) + cos(θz)) / 2
     *   noiseZ = R * (sin(θx) + sin(θz)) / 2
     *   noiseY = blockY (unchanged)
     *
     * Division by 2 keeps each individual axis's contribution in [-R, R],
     * so the combined result is in [-R, R] as well.
     *
     * Seamlessness proof:
     *   At blockX = +halfW and blockX = -halfW:
     *     θx(+halfW) = +π,  θx(-halfW) = -π
     *     cos(+π) = cos(-π) = -1  → same noiseX contribution
     *     sin(+π) = sin(-π) = 0   → same noiseZ contribution
     *   → Both border coordinates map to the exact same noise input → seamless!
     *
     * @param context  the original block coordinate context
     * @param evaluator  a function to evaluate the underlying noise at the remapped context
     * @return the noise value at the toroidally remapped position
     */
    public static double remap(FunctionContext context, java.util.function.ToDoubleFunction<FunctionContext> evaluator) {
        if (!GeolockServerConfig.enableWorldLooping || context instanceof ToroidalContext) {
            return evaluator.applyAsDouble(context);
        }

        double width = GeolockServerConfig.worldBoundaryWidth;
        // R = world_width / (2π) preserves approximate noise frequency
        double R = width / TWO_PI;

        double blockX = context.blockX();
        double blockZ = context.blockZ();

        // Map to angle in [-π, π]
        double thetaX = (blockX / width) * TWO_PI;
        double thetaZ = (blockZ / width) * TWO_PI;

        // Pack 4 circular components into 2 noise coordinates
        double remappedX = R * (Math.cos(thetaX) + Math.cos(thetaZ)) * 0.5;
        double remappedZ = R * (Math.sin(thetaX) + Math.sin(thetaZ)) * 0.5;

        return evaluator.applyAsDouble(new ToroidalContext(context, (int) Math.round(remappedX), (int) Math.round(remappedZ)));
    }

    /**
     * A FunctionContext that replaces blockX and blockZ with toroidally remapped values.
     * blockY is preserved so vertical noise layers remain unchanged.
     *
     * This marker class is also used to prevent recursive remapping inside nested evaluators.
     */
    public static class ToroidalContext implements FunctionContext {
        private final FunctionContext original;
        private final int remappedX;
        private final int remappedZ;

        public ToroidalContext(FunctionContext original, int remappedX, int remappedZ) {
            this.original = original;
            this.remappedX = remappedX;
            this.remappedZ = remappedZ;
        }

        @Override public int blockX() { return remappedX; }
        @Override public int blockY() { return original.blockY(); }
        @Override public int blockZ() { return remappedZ; }
    }

    // Legacy methods kept for potential external compatibility
    public static int getWrappedX(int x, int z) { return x; }
    public static int getWrappedZ(int x, int z) { return z; }

    /**
     * @deprecated Use remap() instead. Kept for API compatibility.
     */
    @Deprecated
    public static double blend(FunctionContext context, java.util.function.ToDoubleFunction<FunctionContext> evaluator) {
        return remap(context, evaluator);
    }

    /**
     * @deprecated Use ToroidalContext instead. Kept for API compatibility.
     */
    @Deprecated
    public static class OffsetContext extends ToroidalContext {
        public OffsetContext(FunctionContext original, double offsetX, double offsetZ) {
            super(original,
                (int) Math.round(original.blockX() + offsetX),
                (int) Math.round(original.blockZ() + offsetZ));
        }
    }
}
