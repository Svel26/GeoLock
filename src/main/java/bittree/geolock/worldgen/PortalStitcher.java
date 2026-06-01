package bittree.geolock.worldgen;

import bittree.geolock.GeolockServerConfig;
import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

public class PortalStitcher {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static java.lang.reflect.Method findMethod(Class<?> clazz, String name, int paramCount) {
        for (java.lang.reflect.Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                return m;
            }
        }
        for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /**
     * Stitches the overworld boundaries using Immersive Portals.
     * @return true if Immersive Portals wrapping zone was successfully verified or created; false otherwise.
     */
    public static boolean stitchOverworld(ServerLevel level) {
        if (!GeolockServerConfig.enableWorldLooping) {
            return false;
        }

        try {
            double w = GeolockServerConfig.worldBoundaryWidth;
            double halfW = w / 2.0;

            LOGGER.info("[GeoLock] Ensuring correct world wrapping portals at X = +-{}", halfW);

            // Reflection-based classes for Immersive Portals
            Class<?> portalClass = Class.forName("qouteall.imm_ptl.core.portal.Portal");
            Class<?> portalApiClass = Class.forName("qouteall.imm_ptl.core.api.PortalAPI");
            Class<?> globalPortalStorageClass = Class.forName("qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage");
            Class<?> worldWrappingPortalClass = Class.forName("qouteall.imm_ptl.core.portal.global_portals.WorldWrappingPortal");
            Class<?> wrappingZoneClass = Class.forName("qouteall.imm_ptl.core.portal.global_portals.WorldWrappingPortal$WrappingZone");

            // 1. Clean up any legacy standard Portal entities created at the boundaries
            java.lang.reflect.Method getGlobalPortalsMethod = findMethod(globalPortalStorageClass, "getGlobalPortals", 1);
            if (getGlobalPortalsMethod == null) {
                throw new NoSuchMethodException("getGlobalPortals method not found");
            }
            List<?> globalPortals = (List<?>) getGlobalPortalsMethod.invoke(null, level);
            if (globalPortals != null) {
                java.lang.reflect.Method removeGlobalPortalMethod = findMethod(portalApiClass, "removeGlobalPortal", 2);
                if (removeGlobalPortalMethod == null) {
                    throw new NoSuchMethodException("removeGlobalPortal method not found");
                }
                List<net.minecraft.world.entity.Entity> toRemove = new ArrayList<>();
                for (Object obj : globalPortals) {
                    if (obj == null) continue;
                    // Remove if it's a standard Portal (not a WorldWrappingPortal subclass) and matches boundary coordinates
                    if (obj.getClass() == portalClass && obj instanceof net.minecraft.world.entity.Entity entity) {
                        double ox = entity.getX();
                        if (Math.abs(ox - halfW) < 10.0 || Math.abs(ox - (-halfW)) < 10.0) {
                            toRemove.add(entity);
                        }
                    }
                }
                for (net.minecraft.world.entity.Entity entity : toRemove) {
                    LOGGER.info("[GeoLock] Cleaning up legacy standard portal at X = {}", entity.getX());
                    removeGlobalPortalMethod.invoke(null, level, entity);
                }
            }

            // 2. Manage the native Immersive Portals WrappingZone
            java.lang.reflect.Method getWrappingZonesMethod = findMethod(worldWrappingPortalClass, "getWrappingZones", 1);
            if (getWrappingZonesMethod == null) {
                throw new NoSuchMethodException("getWrappingZones method not found");
            }
            List<?> wrappingZones = (List<?>) getWrappingZonesMethod.invoke(null, level);
            boolean zoneMatches = false;

            if (wrappingZones != null && !wrappingZones.isEmpty()) {
                Object zoneObj = wrappingZones.get(0);
                if (wrappingZoneClass.isInstance(zoneObj)) {
                    java.lang.reflect.Method getAreaMethod = findMethod(wrappingZoneClass, "getArea", 0);
                    if (getAreaMethod == null) {
                        throw new NoSuchMethodException("getArea method not found");
                    }
                    AABB area = (AABB) getAreaMethod.invoke(zoneObj);
                    if (area != null) {
                        double currentMinX = area.minX;
                        double currentMaxX = area.maxX;
                        if (Math.abs(currentMinX - (-halfW)) < 2.0 && Math.abs(currentMaxX - halfW) < 2.0) {
                            zoneMatches = true;
                        } else {
                            LOGGER.info("[GeoLock] Existing wrapping zone boundary mismatch (expected +-{}, got [{}, {}]). Removing old zone.", 
                                        halfW, currentMinX, currentMaxX);
                            java.lang.reflect.Method removeFromWorldMethod = findMethod(wrappingZoneClass, "removeFromWorld", 0);
                            if (removeFromWorldMethod == null) {
                                throw new NoSuchMethodException("removeFromWorld method not found");
                            }
                            removeFromWorldMethod.invoke(zoneObj);
                        }
                    }
                }
            }

            if (!zoneMatches) {
                LOGGER.info("[GeoLock] Creating native inward wrapping zone at X = +-{}", halfW);
                int xMin = (int) -halfW;
                int xMax = (int) halfW;
                int zMin = -20000000;
                int zMax = 20000000;
                
                java.lang.reflect.Method invokeAddWrappingZoneMethod = findMethod(worldWrappingPortalClass, "invokeAddWrappingZone", 7);
                if (invokeAddWrappingZoneMethod == null) {
                    throw new NoSuchMethodException("invokeAddWrappingZone method not found");
                }
                
                java.util.function.Consumer<net.minecraft.network.chat.Component> feedbackConsumer = component -> {
                    LOGGER.info("[GeoLock] invokeAddWrappingZone feedback: {}", component.getString());
                };
                
                invokeAddWrappingZoneMethod.invoke(null, level, xMin, xMax, zMin, zMax, true, feedbackConsumer);
            } else {
                LOGGER.info("[GeoLock] Valid native wrapping zone already exists.");
            }

            return true;
        } catch (Exception e) {
            LOGGER.warn("[GeoLock] Immersive Portals integration is not active or failed to load: {}", e.toString());
            return false;
        }
    }
}
