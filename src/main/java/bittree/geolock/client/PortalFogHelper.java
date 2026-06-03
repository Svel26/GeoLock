package bittree.geolock.client;

import net.minecraft.client.Camera;
import net.minecraft.world.level.material.FogType;

public class PortalFogHelper {
    private static Boolean cachedPresent = null;
    private static final ThreadLocal<Boolean> IN_FOG_CHECK = ThreadLocal.withInitial(() -> false);

    public static boolean isIPPresent() {
        if (cachedPresent == null) {
            try {
                Class.forName("qouteall.imm_ptl.core.render.context_management.PortalRendering");
                cachedPresent = true;
            } catch (ClassNotFoundException e) {
                cachedPresent = false;
            }
        }
        return cachedPresent;
    }

    public static FogType getIPSubmergedFluidState(Camera camera) {
        if (!isIPPresent()) {
            return null;
        }
        if (IN_FOG_CHECK.get()) {
            return null;
        }
        try {
            return IPSafeAccess.getSubmergedFluidState(camera);
        } catch (Throwable t) {
            return null;
        }
    }

    // Isolated inner class to avoid loading Immersive Portals classes unless IP is present
    private static class IPSafeAccess {
        public static FogType getSubmergedFluidState(Camera camera) {
            if (qouteall.imm_ptl.core.render.context_management.PortalRendering.isRendering()) {
                // If the player's eyes are in water or lava, we force that fluid fog
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.player != null) {
                    if (mc.player.isEyeInFluidType(net.minecraft.world.level.material.Fluids.WATER.getFluidType())) {
                        return FogType.WATER;
                    }
                    if (mc.player.isEyeInFluidType(net.minecraft.world.level.material.Fluids.LAVA.getFluidType())) {
                        return FogType.LAVA;
                    }
                }

                IN_FOG_CHECK.set(true);
                try {
                    // Fall back to checking the current transformed camera's fluid status
                    FogType currentFluid = camera.getFluidInCamera();
                    if (currentFluid != FogType.NONE) {
                        return currentFluid;
                    }
                } finally {
                    IN_FOG_CHECK.set(false);
                }
            }
            return null;
        }
    }
}
