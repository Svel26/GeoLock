package bittree.geolock.worldgen;

import bittree.geolock.GeolockServerConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import net.minecraft.world.phys.AABB;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.imm_ptl.core.portal.global_portals.WorldWrappingPortal;

import java.util.ArrayList;
import java.util.List;

public class PortalStitcher {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static void stitchOverworld(ServerLevel level) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return;
        }

        try {
            double w = GeolockServerConfig.worldBoundaryWidth;
            double halfW = w / 2.0;

            LOGGER.info("[GeoLock] Ensuring correct world wrapping portals at X = +-{}", halfW);

            // 1. Clean up any legacy standard Portal entities created at the boundaries
            List<Portal> globalPortals = GlobalPortalStorage.getGlobalPortals(level);
            if (globalPortals != null) {
                List<Portal> toRemove = new ArrayList<>();
                for (Portal portal : globalPortals) {
                    if (portal == null) continue;
                    // Remove if it's a standard Portal (not a WorldWrappingPortal subclass) and matches boundary coordinates
                    if (portal.getClass() == Portal.class) {
                        double ox = portal.getX();
                        if (Math.abs(ox - halfW) < 10.0 || Math.abs(ox - (-halfW)) < 10.0) {
                            toRemove.add(portal);
                        }
                    }
                }
                for (Portal portal : toRemove) {
                    LOGGER.info("[GeoLock] Cleaning up legacy standard portal at X = {}", portal.getX());
                    PortalAPI.removeGlobalPortal(level, portal);
                }
            }

            // 2. Manage the native Immersive Portals WrappingZone
            List<?> wrappingZones = WorldWrappingPortal.getWrappingZones(level);
            boolean zoneMatches = false;

            if (wrappingZones != null && !wrappingZones.isEmpty()) {
                Object zoneObj = wrappingZones.get(0);
                if (zoneObj instanceof WorldWrappingPortal.WrappingZone existingZone) {
                    AABB area = existingZone.getArea();
                    if (area != null) {
                        double currentMinX = area.minX;
                        double currentMaxX = area.maxX;
                        if (Math.abs(currentMinX - (-halfW)) < 2.0 && Math.abs(currentMaxX - halfW) < 2.0) {
                            zoneMatches = true;
                        } else {
                            LOGGER.info("[GeoLock] Existing wrapping zone boundary mismatch (expected +-{}, got [{}, {}]). Removing old zone.", 
                                        halfW, currentMinX, currentMaxX);
                            existingZone.removeFromWorld();
                        }
                    }
                }
            }

            if (!zoneMatches) {
                LOGGER.info("[GeoLock] Creating native inward wrapping zone at X = +-{}", halfW);
                int xMin = (int) -halfW;
                int xMax = (int) halfW;
                // We wrap coordinate space between X = -10000 and X = 10000.
                // We set Z boundary to be extremely large (20,000,000) so it's virtually endless.
                int zMin = -20000000;
                int zMax = 20000000;
                WorldWrappingPortal.invokeAddWrappingZone(level, xMin, xMax, zMin, zMax, true, component -> {
                    LOGGER.info("[GeoLock] invokeAddWrappingZone feedback: {}", component.getString());
                });
            } else {
                LOGGER.info("[GeoLock] Valid native wrapping zone already exists.");
            }

        } catch (Exception e) {
            LOGGER.error("[GeoLock] Failed to stitch overworld boundaries", e);
        }
    }
}
