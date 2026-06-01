package bittree.geolock.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.level.levelgen.DensityFunction;
import bittree.geolock.GeolockServerConfig;
import bittree.geolock.worldgen.CylindricalNoise;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Noise")
public class NoiseDensityFunctionMixin {
    @Shadow @org.spongepowered.asm.mixin.Final private net.minecraft.world.level.levelgen.DensityFunction.NoiseHolder noise;
    @Shadow @org.spongepowered.asm.mixin.Final private double xzScale;
    @Shadow @org.spongepowered.asm.mixin.Final private double yScale;

    @Inject(method = "compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D", at = @At("HEAD"), cancellable = true)
    private void onCompute(DensityFunction.FunctionContext context, CallbackInfoReturnable<Double> cir) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return;
        }
        cir.setReturnValue(CylindricalNoise.blend(context, ctx -> 
            this.noise.getValue((double)ctx.blockX() * this.xzScale, (double)ctx.blockY() * this.yScale, (double)ctx.blockZ() * this.xzScale)
        ));
    }
}
