package bittree.geolock.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.world.level.levelgen.DensityFunction;
import bittree.geolock.GeolockServerConfig;
import bittree.geolock.worldgen.ToroidalNoise;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$ShiftedNoise")
public class ShiftedNoiseDensityFunctionMixin {
    @Shadow @org.spongepowered.asm.mixin.Final private net.minecraft.world.level.levelgen.DensityFunction shiftX;
    @Shadow @org.spongepowered.asm.mixin.Final private net.minecraft.world.level.levelgen.DensityFunction shiftY;
    @Shadow @org.spongepowered.asm.mixin.Final private net.minecraft.world.level.levelgen.DensityFunction shiftZ;
    @Shadow @org.spongepowered.asm.mixin.Final private double xzScale;
    @Shadow @org.spongepowered.asm.mixin.Final private double yScale;
    @Shadow @org.spongepowered.asm.mixin.Final private net.minecraft.world.level.levelgen.DensityFunction.NoiseHolder noise;

    @Inject(method = "compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D", at = @At("HEAD"), cancellable = true)
    private void onCompute(DensityFunction.FunctionContext context, CallbackInfoReturnable<Double> cir) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return;
        }
        cir.setReturnValue(ToroidalNoise.remap(context, ctx -> {
            double x = ctx instanceof ToroidalNoise.ToroidalFunctionContext tc ? tc.x() : ctx.blockX();
            double y = ctx instanceof ToroidalNoise.ToroidalFunctionContext tc ? tc.y() : ctx.blockY();
            double z = ctx instanceof ToroidalNoise.ToroidalFunctionContext tc ? tc.z() : ctx.blockZ();
            double sx = this.shiftX.compute(ctx);
            double sy = this.shiftY.compute(ctx);
            double sz = this.shiftZ.compute(ctx);
            return this.noise.getValue(
                x * this.xzScale + sx, 
                y * this.yScale + sy, 
                z * this.xzScale + sz
            );
        }));
    }

    @Inject(method = "fillArray([DLnet/minecraft/world/level/levelgen/DensityFunction$ContextProvider;)V", at = @At("HEAD"), cancellable = true)
    private void onFillArray(double[] densities, DensityFunction.ContextProvider provider, CallbackInfo ci) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return;
        }
        ci.cancel();
        provider.fillAllDirectly(densities, (DensityFunction) (Object) this);
    }
}

