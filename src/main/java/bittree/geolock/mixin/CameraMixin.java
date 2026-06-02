package bittree.geolock.mixin;

import net.minecraft.client.Camera;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import bittree.geolock.client.PortalFogHelper;

@Mixin(value = Camera.class, priority = 2000)
public class CameraMixin {
    @Inject(method = "getFluidInCamera", at = @At("HEAD"), cancellable = true)
    private void geolock$getFluidInCamera(CallbackInfoReturnable<FogType> cir) {
        FogType overrideFluid = PortalFogHelper.getIPSubmergedFluidState((Camera) (Object) this);
        if (overrideFluid != null) {
            cir.setReturnValue(overrideFluid);
        }
    }
}
