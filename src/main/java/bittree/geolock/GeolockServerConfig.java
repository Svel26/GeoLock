package bittree.geolock;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;

public class GeolockServerConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static boolean enableWorldLooping = true;
    public static double worldBoundaryWidth = 20000.0;
    public static double teleportBufferZone = 5.0;
    public static double blendZoneWidth = 128.0;
    public static boolean logVehicleTeleports = true;
    public static boolean debugResourcePlacement = false;

    public static void load() {
        try {
            Path configPath = FMLPaths.CONFIGDIR.get().resolve("geolock-server.json");
            File file = configPath.toFile();
            if (file.exists()) {
                try (FileReader reader = new FileReader(file)) {
                    RawConfig raw = GSON.fromJson(reader, RawConfig.class);
                    if (raw != null) {
                        enableWorldLooping = raw.enableWorldLooping;
                        worldBoundaryWidth = raw.worldBoundaryWidth;
                        teleportBufferZone = raw.teleportBufferZone;
                        blendZoneWidth = raw.blendZoneWidth > 0.0 ? raw.blendZoneWidth : 128.0;
                        if (raw.logging != null) {
                            logVehicleTeleports = raw.logging.logVehicleTeleports;
                            debugResourcePlacement = raw.logging.debugResourcePlacement;
                        }
                        LOGGER.info("[GeoLock] Loaded geolock-server.json: enableWorldLooping={}, worldBoundaryWidth={}, teleportBufferZone={}, blendZoneWidth={}", 
                                    enableWorldLooping, worldBoundaryWidth, teleportBufferZone, blendZoneWidth);
                    }
                }
            } else {
                LOGGER.warn("[GeoLock] geolock-server.json not found in config directory, creating default configuration file.");
                saveDefault(file);
            }
        } catch (Exception e) {
            LOGGER.error("[GeoLock] Error loading geolock-server.json config", e);
        }
    }

    private static void saveDefault(File file) {
        try {
            RawConfig raw = new RawConfig();
            raw.enableWorldLooping = enableWorldLooping;
            raw.worldBoundaryWidth = worldBoundaryWidth;
            raw.teleportBufferZone = teleportBufferZone;
            raw.blendZoneWidth = blendZoneWidth;
            raw.logging = new RawConfig.Logging();
            raw.logging.logVehicleTeleports = logVehicleTeleports;
            raw.logging.debugResourcePlacement = debugResourcePlacement;

            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(raw, writer);
            }
        } catch (Exception e) {
            LOGGER.error("[GeoLock] Error saving default config", e);
        }
    }

    public static void loadOrInitializeWorldSize(File worldDir, double defaultWidth) {
        try {
            File file = new File(worldDir, "geolock-world.json");
            if (file.exists()) {
                try (FileReader reader = new FileReader(file)) {
                    WorldSizeConfig config = GSON.fromJson(reader, WorldSizeConfig.class);
                    if (config != null && config.worldBoundaryWidth >= 512.0) {
                        worldBoundaryWidth = config.worldBoundaryWidth;
                        LOGGER.info("[GeoLock] Loaded worldBoundaryWidth from save folder: {}", worldBoundaryWidth);
                    } else {
                        worldBoundaryWidth = Math.max(512.0, config != null ? config.worldBoundaryWidth : 512.0);
                        LOGGER.warn("[GeoLock] Loaded worldBoundaryWidth was invalid, enforcing minimum of: {}", worldBoundaryWidth);
                    }
                }
            } else {
                double size = Math.max(512.0, defaultWidth);
                LOGGER.info("[GeoLock] geolock-world.json not found in world directory, initializing with width: {}", size);
                WorldSizeConfig config = new WorldSizeConfig();
                config.worldBoundaryWidth = size;
                worldBoundaryWidth = size;
                try (FileWriter writer = new FileWriter(file)) {
                    GSON.toJson(config, writer);
                }
            }
        } catch (Exception e) {
            LOGGER.error("[GeoLock] Error loading/initializing geolock-world.json", e);
        }
    }

    private static class WorldSizeConfig {
        double worldBoundaryWidth;
    }

    private static class RawConfig {
        boolean enableWorldLooping;
        double worldBoundaryWidth;
        double teleportBufferZone;
        double blendZoneWidth;
        Logging logging;

        private static class Logging {
            boolean logVehicleTeleports;
            boolean debugResourcePlacement;
        }
    }
}
