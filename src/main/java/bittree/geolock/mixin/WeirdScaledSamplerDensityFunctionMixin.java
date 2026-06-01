package bittree.geolock.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.level.levelgen.DensityFunction;
import bittree.geolock.GeolockServerConfig;
import bittree.geolock.worldgen.CylindricalNoise;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$WeirdScaledSampler")
public class WeirdScaledSamplerDensityFunctionMixin {

    private static final ThreadLocal<Boolean> IN_COMPUTE = ThreadLocal.withInitial(() -> false);

    @Shadow
    public double transform(DensityFunction.FunctionContext context, double value) {
        throw new AssertionError();
    }

    @Inject(method = "transform(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;D)D", at = @At("HEAD"), cancellable = true)
    private void onTransform(DensityFunction.FunctionContext context, double inputVal, CallbackInfoReturnable<Double> cir) {
        if (!GeolockServerConfig.enableWorldLooping || IN_COMPUTE.get()) {
            return;
        }

        IN_COMPUTE.set(true);
        try {
            cir.setReturnValue(CylindricalNoise.blend(context, ctx -> 
                this.transform(ctx, inputVal)
            ));
        } finally {
            IN_COMPUTE.set(false);
        }
    }
}
