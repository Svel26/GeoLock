package bittree.geolock.mixin;

import bittree.geolock.GeolockServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(Level.class)
public class LevelMixin {
    @Unique
    private static final ThreadLocal<Boolean> geolock$inSynchronization = ThreadLocal.withInitial(() -> false);

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"))
    private void geolock$onSetBlock(BlockPos pos, BlockState state, int flags, int recursionLimit, CallbackInfoReturnable<Boolean> cir) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return;
        }

        Level level = (Level) (Object) this;
        if (level.dimension() != Level.OVERWORLD) {
            return;
        }

        if (geolock$inSynchronization.get()) {
            return;
        }

        geolock$inSynchronization.set(true);
        try {
            double w = GeolockServerConfig.worldBoundaryWidth;
            double halfW = w / 2.0;
            List<BlockPos> syncedPosList = geolock$getSynchronizedPositions(pos, halfW, w);
            for (BlockPos targetPos : syncedPosList) {
                BlockState current = level.getBlockState(targetPos);
                if (current != state) {
                    level.setBlock(targetPos, state, flags, recursionLimit);
                }
            }
        } finally {
            geolock$inSynchronization.set(false);
        }
    }

    @Unique
    private List<BlockPos> geolock$getSynchronizedPositions(BlockPos pos, double halfW, double width) {
        int x = pos.getX();
        int z = pos.getZ();
        int y = pos.getY();
        
        int h = (int) halfW;
        int w = (int) width;
        
        List<Integer> targetXs = new ArrayList<>();
        if (x == -h) targetXs.add(x + w);
        else if (x == h) targetXs.add(x - w);
        else if (x == h - 1) targetXs.add(x - w);
        else if (x == -h - 1) targetXs.add(x + w);
        
        List<Integer> targetZs = new ArrayList<>();
        if (z == -h) targetZs.add(z + w);
        else if (z == h) targetZs.add(z - w);
        else if (z == h - 1) targetZs.add(z - w);
        else if (z == -h - 1) targetZs.add(z + w);
        
        List<BlockPos> results = new ArrayList<>();
        
        for (int tx : targetXs) {
            results.add(new BlockPos(tx, y, z));
        }
        for (int tz : targetZs) {
            results.add(new BlockPos(x, y, tz));
        }
        for (int tx : targetXs) {
            for (int tz : targetZs) {
                results.add(new BlockPos(tx, y, tz));
            }
        }
        
        return results;
    }
}
