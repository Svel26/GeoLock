package bittree.geolock.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.level.levelgen.DensityFunction;
import bittree.geolock.GeolockServerConfig;
import bittree.geolock.worldgen.CylindricalNoise;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$ShiftA")
public class ShiftADensityFunctionMixin {
    @Shadow @org.spongepowered.asm.mixin.Final private net.minecraft.world.level.levelgen.DensityFunction.NoiseHolder offsetNoise;

    @Inject(method = "compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D", at = @At("HEAD"), cancellable = true)
    private void onCompute(DensityFunction.FunctionContext context, CallbackInfoReturnable<Double> cir) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return;
        }
        cir.setReturnValue(CylindricalNoise.remap(context, ctx -> 
            this.offsetNoise.getValue((double)ctx.blockX() * 0.25D, 0.0D, (double)ctx.blockZ() * 0.25D) * 4.0D
        ));
    }
}
