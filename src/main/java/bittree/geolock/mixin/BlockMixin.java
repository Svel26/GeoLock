package bittree.geolock.mixin;

import bittree.geolock.GeolockServerConfig;
import bittree.geolock.worldgen.CoordWrappingUtil;
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

        BlockPos wrapped = CoordWrappingUtil.wrapBlockPos(adjacentPos);
        if (wrapped != adjacentPos) {
            // Recursively evaluate against the wrapped block using the full level to prevent Z-fighting
            boolean shouldRender = Block.shouldRenderFace(state, activeLevel, pos, face, wrapped);
            cir.setReturnValue(shouldRender);
        }
    }
}
