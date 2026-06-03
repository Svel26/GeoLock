package bittree.geolock.mixin;

import bittree.geolock.GeolockServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Block.class)
public class BlockMixin {
    @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
    private static void geolock$shouldRenderFace(BlockState state, BlockGetter level, BlockPos pos, Direction face, BlockPos adjacentPos, CallbackInfoReturnable<Boolean> cir) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return;
        }

        BlockGetter activeLevel = level;
        if (!(level instanceof Level)) {
            BlockGetter clientLvl = bittree.geolock.client.ClientLevelSelector.getClientLevel();
            if (clientLvl != null) {
                activeLevel = clientLvl;
            }
        }

        if (activeLevel instanceof Level lvl && lvl.dimension() != Level.OVERWORLD) {
            return;
        }

        double w = GeolockServerConfig.worldBoundaryWidth;
        double halfW = w / 2.0;

        BlockPos wrapped = geolock$getWrappedNeighborPos(adjacentPos, halfW, w);
        if (wrapped != null) {
            // Recursively evaluate against the wrapped block using the full level to prevent Z-fighting
            boolean shouldRender = Block.shouldRenderFace(state, activeLevel, pos, face, wrapped);
            cir.setReturnValue(shouldRender);
        }
    }


    @Unique
    private static BlockPos geolock$getWrappedNeighborPos(BlockPos pos, double halfW, double width) {
        int x = pos.getX();
        int z = pos.getZ();
        int y = pos.getY();
        int h = (int) halfW;

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
