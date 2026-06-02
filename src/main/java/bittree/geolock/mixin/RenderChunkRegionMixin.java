package bittree.geolock.mixin;

import bittree.geolock.GeolockServerConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
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
    private void geolock$getBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return;
        }

        double w = GeolockServerConfig.worldBoundaryWidth;
        double halfW = w / 2.0;

        BlockPos wrapped = geolock$getWrappedNeighborPos(pos, halfW, w);
        if (wrapped != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                cir.setReturnValue(mc.level.getBlockState(wrapped));
            }
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void geolock$getFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return;
        }

        double w = GeolockServerConfig.worldBoundaryWidth;
        double halfW = w / 2.0;

        BlockPos wrapped = geolock$getWrappedNeighborPos(pos, halfW, w);
        if (wrapped != null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                cir.setReturnValue(mc.level.getFluidState(wrapped));
            }
        }
    }

    @Unique
    private BlockPos geolock$getWrappedNeighborPos(BlockPos pos, double halfW, double width) {
        int x = pos.getX();
        int z = pos.getZ();
        int y = pos.getY();
        int h = (int) halfW;
        int w = (int) width;

        boolean remapped = false;
        if (x == h) { x = -h; remapped = true; }
        else if (x == -h - 1) { x = h - 1; remapped = true; }

        if (z == h) { z = -h; remapped = true; }
        else if (z == -h - 1) { z = h - 1; remapped = true; }

        if (remapped) {
            return new BlockPos(x, y, z);
        }
        return null;
    }
}
