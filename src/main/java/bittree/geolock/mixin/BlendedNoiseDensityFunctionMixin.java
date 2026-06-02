package bittree.geolock.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import bittree.geolock.GeolockServerConfig;
import bittree.geolock.worldgen.ToroidalNoise;

@Mixin(BlendedNoise.class)
public class BlendedNoiseDensityFunctionMixin {

    private static final ThreadLocal<Boolean> IN_COMPUTE = ThreadLocal.withInitial(() -> false);

    @Inject(method = "compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D", at = @At("HEAD"), cancellable = true)
    private void onCompute(DensityFunction.FunctionContext context, CallbackInfoReturnable<Double> cir) {
        if (!GeolockServerConfig.enableWorldLooping || IN_COMPUTE.get()) {
            return;
        }

        IN_COMPUTE.set(true);
        try {
            cir.setReturnValue(ToroidalNoise.remap(context, ctx -> 
                ((BlendedNoise) (Object) this).compute(ctx)
            ));
        } finally {
            IN_COMPUTE.set(false);
        }
    }
}
