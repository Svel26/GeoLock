package bittree.geolock.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.level.levelgen.WorldgenRandom;

@Mixin(WorldgenRandom.class)
public class CarverCheck {
    @Inject(method = "setLargeFeatureSeed(JII)V", at = @At("HEAD"))
    private void check2(long l, int i, int j, CallbackInfo ci) {}
    @Inject(method = "setDecorationSeed(JII)J", at = @At("HEAD"))
    private void check3(long l, int i, int j, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Long> cir) {}
}
