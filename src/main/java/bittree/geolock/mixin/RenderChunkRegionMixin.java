package bittree.geolock.mixin;

import bittree.geolock.GeolockServerConfig;
import bittree.geolock.client.ClientLevelSelector;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
        BlockPos wrapped = geolock$getWrappedPos(pos);
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
        BlockPos wrapped = geolock$getWrappedPos(pos);
        if (wrapped != pos) {
            BlockGetter clientLevel = ClientLevelSelector.getClientLevel();
            if (clientLevel != null) {
                cir.setReturnValue(clientLevel.getFluidState(wrapped));
            }
        }
    }

    @Unique
    private static BlockPos geolock$getWrappedPos(BlockPos pos) {
        double w = GeolockServerConfig.worldBoundaryWidth;
        double halfW = w / 2.0;
        int x = pos.getX();
        int z = pos.getZ();

        boolean needsWrap = false;
        if (x < -halfW || x >= halfW) {
            x = (int) Math.floor(((x + halfW) % w + w) % w - halfW);
            needsWrap = true;
        }
        if (z < -halfW || z >= halfW) {
            z = (int) Math.floor(((z + halfW) % w + w) % w - halfW);
            needsWrap = true;
        }

        return needsWrap ? new BlockPos(x, pos.getY(), z) : pos;
    }
}
