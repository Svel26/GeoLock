package bittree.geolock.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import net.minecraft.world.level.levelgen.DensityFunction;
import bittree.geolock.worldgen.CylindricalNoise;

@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Shift")
public class ShiftDensityFunctionMixin {

    @Redirect(method = "compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;blockX()I"))
    private int redirectBlockX(DensityFunction.FunctionContext context) {
        return CylindricalNoise.getWrappedX(context.blockX(), context.blockZ());
    }

    @Redirect(method = "compute(Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;)D",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/DensityFunction$FunctionContext;blockZ()I"))
    private int redirectBlockZ(DensityFunction.FunctionContext context) {
        return CylindricalNoise.getWrappedZ(context.blockX(), context.blockZ());
    }
}
