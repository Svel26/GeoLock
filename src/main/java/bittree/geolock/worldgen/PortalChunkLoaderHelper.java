package bittree.geolock.worldgen;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import qouteall.imm_ptl.core.chunk_loading.ChunkLoader;
import qouteall.imm_ptl.core.api.PortalAPI;
import java.util.*;

public class PortalChunkLoaderHelper {
    private static final Map<UUID, List<ChunkLoader>> activeChunkLoaders = new HashMap<>();

    /**
     * Updates chunk loaders for a player near the world boundary.
     * Pre-loads chunks on the wrapped side of the boundary so that
     * portal transitions are seamless.
     *
     * @param player    The player to update loaders for
     * @param x         Player's X position
     * @param z         Player's Z position
     * @param w         World width
     * @param threshold Distance from boundary at which to start pre-loading
     */
    public static void updatePlayerChunkLoaders(ServerPlayer player, double x, double z, double w, double threshold) {
        UUID uuid = player.getUUID();
        List<ChunkLoader> currentLoaders = activeChunkLoaders.getOrDefault(uuid, Collections.emptyList());
        List<ChunkLoader> newLoaders = new ArrayList<>();

        double halfW = w / 2.0;
        boolean nearEast = (halfW - x) < threshold;
        boolean nearWest = (x + halfW) < threshold;
        boolean nearNorth = (halfW - z) < threshold;
        boolean nearSouth = (z + halfW) < threshold;

        List<ChunkPos> targets = new ArrayList<>();

        int px = (int) Math.floor(x / 16.0);
        int pz = (int) Math.floor(z / 16.0);
        int chunkWest = CoordWrappingUtil.wrapChunkCoord((int) Math.floor(-halfW / 16.0));
        int chunkEast = CoordWrappingUtil.wrapChunkCoord((int) Math.floor((halfW - 1.0) / 16.0));

        // Pre-load chunks on the opposite side of each boundary
        if (nearEast) targets.add(new ChunkPos(chunkWest, pz));
        if (nearWest) targets.add(new ChunkPos(chunkEast, pz));
        if (nearNorth) targets.add(new ChunkPos(px, chunkWest));
        if (nearSouth) targets.add(new ChunkPos(px, chunkEast));

        // Pre-load corner chunks for diagonal portal transitions
        if (nearEast && nearNorth) targets.add(new ChunkPos(chunkWest, chunkWest));
        if (nearEast && nearSouth) targets.add(new ChunkPos(chunkWest, chunkEast));
        if (nearWest && nearNorth) targets.add(new ChunkPos(chunkEast, chunkWest));
        if (nearWest && nearSouth) targets.add(new ChunkPos(chunkEast, chunkEast));

        // Use a larger radius to ensure chunks are fully loaded before the player crosses
        int radius = player.getServer().getPlayerList().getViewDistance() + 4;
        ResourceKey<Level> dim = player.level().dimension();

        for (ChunkPos pos : targets) {
            newLoaders.add(new ChunkLoader(dim, pos.x, pos.z, radius));
        }

        // Remove stale loaders
        for (ChunkLoader oldL : currentLoaders) {
            if (!newLoaders.contains(oldL)) {
                try {
                    PortalAPI.removeChunkLoaderForPlayer(player, oldL);
                } catch (Throwable t) {
                    // Ignore
                }
            }
        }

        // Add new loaders
        for (ChunkLoader newL : newLoaders) {
            if (!currentLoaders.contains(newL)) {
                try {
                    PortalAPI.addChunkLoaderForPlayer(player, newL);
                } catch (Throwable t) {
                    // Ignore
                }
            }
        }

        activeChunkLoaders.put(uuid, newLoaders);
    }

    public static void cleanupPlayer(ServerPlayer player) {
        UUID uuid = player.getUUID();
        List<ChunkLoader> loaders = activeChunkLoaders.remove(uuid);
        if (loaders != null) {
            for (ChunkLoader l : loaders) {
                try {
                    PortalAPI.removeChunkLoaderForPlayer(player, l);
                } catch (Throwable t) {
                    // Ignore
                }
            }
        }
    }
}
