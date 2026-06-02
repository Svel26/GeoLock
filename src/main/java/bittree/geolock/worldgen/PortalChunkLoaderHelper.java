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
        int chunkWest = (int) Math.floor(-halfW / 16.0);
        int chunkEast = (int) Math.floor((halfW - 1.0) / 16.0);

        if (nearEast) targets.add(new ChunkPos(chunkWest, pz));
        if (nearWest) targets.add(new ChunkPos(chunkEast, pz));
        if (nearNorth) targets.add(new ChunkPos(px, chunkWest));
        if (nearSouth) targets.add(new ChunkPos(px, chunkEast));

        if (nearEast && nearNorth) targets.add(new ChunkPos(chunkWest, chunkWest));
        if (nearEast && nearSouth) targets.add(new ChunkPos(chunkWest, chunkEast));
        if (nearWest && nearNorth) targets.add(new ChunkPos(chunkEast, chunkWest));
        if (nearWest && nearSouth) targets.add(new ChunkPos(chunkEast, chunkEast));

        int radius = player.getServer().getPlayerList().getViewDistance() + 2;
        ResourceKey<Level> dim = player.level().dimension();

        for (ChunkPos pos : targets) {
            newLoaders.add(new ChunkLoader(dim, pos.x, pos.z, radius));
        }

        for (ChunkLoader oldL : currentLoaders) {
            if (!newLoaders.contains(oldL)) {
                try {
                    PortalAPI.removeChunkLoaderForPlayer(player, oldL);
                } catch (Throwable t) {
                    // Ignore or log
                }
            }
        }

        for (ChunkLoader newL : newLoaders) {
            if (!currentLoaders.contains(newL)) {
                try {
                    PortalAPI.addChunkLoaderForPlayer(player, newL);
                } catch (Throwable t) {
                    // Ignore or log
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
