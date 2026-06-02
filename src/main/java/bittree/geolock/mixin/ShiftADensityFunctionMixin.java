package bittree.geolock.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.level.levelgen.DensityFunction;
import bittree.geolock.GeolockServerConfig;
import bittree.geolock.worldgen.ToroidalNoise;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$ShiftA")
public class ShiftADensityFunctionMixin {
    @Shadow @org.spongepowered.asm.mixin.Final private net.minecraft.world.level.levelgen.DensityFunction.NoiseHolder offsetNoise;

    @Inject(method = "compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D", at = @At("HEAD"), cancellable = true)
    private void onCompute(DensityFunction.FunctionContext context, CallbackInfoReturnable<Double> cir) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return;
        }
        cir.setReturnValue(ToroidalNoise.remap(context, ctx -> {
            double x = ctx instanceof ToroidalNoise.ToroidalFunctionContext tc ? tc.x() : ctx.blockX();
            double z = ctx instanceof ToroidalNoise.ToroidalFunctionContext tc ? tc.z() : ctx.blockZ();
            return this.offsetNoise.getValue(x * 0.25D, 0.0D, z * 0.25D) * 4.0D;
        }));
    }
}
