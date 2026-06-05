package bittree.geolock.mixin;

import bittree.geolock.GeolockServerConfig;
import bittree.geolock.client.ClientLevelSelector;
import bittree.geolock.worldgen.CoordWrappingUtil;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderChunkRegion.class)
public class RenderChunkRegionMixin {

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void geolock$onGetBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return;
        }
        BlockPos wrapped = CoordWrappingUtil.wrapBlockPos(pos);
        if (wrapped != pos) {
            BlockGetter clientLevel = ClientLevelSelector.getClientLevel();
            if (clientLevel != null) {
                cir.setReturnValue(clientLevel.getBlockState(wrapped));
            }
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void geolock$onGetFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return;
        }
        BlockPos wrapped = CoordWrappingUtil.wrapBlockPos(pos);
        if (wrapped != pos) {
            BlockGetter clientLevel = ClientLevelSelector.getClientLevel();
            if (clientLevel != null) {
                cir.setReturnValue(clientLevel.getFluidState(wrapped));
            }
        }
    }
}
