package bittree.geolock.client;

import bittree.geolock.Geolock;
import bittree.geolock.GeolockServerConfig;
import bittree.geolock.mixin.CreateWorldScreenAccessor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = Geolock.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class ClientScreenEvents {
    public static double tempNewWorldSize = 20000.0;
    public static boolean hasTempNewWorldSize = false;

    private static StringWidget labelWidget;
    private static EditBox sizeEditBox;

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof CreateWorldScreen screen) {
            int editBoxWidth = 150;
            int editBoxHeight = 20;
            int rightAlignX = screen.width / 2 + 5;
            int leftAlignX = screen.width / 2 - 155;
            int rowY = 175;

            // 1. Label Widget
            labelWidget = new StringWidget(leftAlignX, rowY + 5, 150, 10, Component.literal("World Loop Width (Blocks):"), screen.getMinecraft().font);
            labelWidget.alignLeft(); // Align text left

            // 2. Input EditBox Widget
            sizeEditBox = new EditBox(screen.getMinecraft().font, rightAlignX, rowY, editBoxWidth, editBoxHeight, Component.literal("World Size"));

            // Initialize value to current config value or default
            sizeEditBox.setValue(String.valueOf((int) GeolockServerConfig.worldBoundaryWidth));
            sizeEditBox.setMaxLength(10);
            
            // Set responder to update the client-side temp value when typed
            sizeEditBox.setResponder(val -> {
                try {
                    double parsed = Double.parseDouble(val);
                    if (parsed >= 512) {
                        tempNewWorldSize = parsed;
                        hasTempNewWorldSize = true;
                    }
                } catch (NumberFormatException ignored) {
                }
            });

            // Add widgets to screen
            event.addListener(labelWidget);
            event.addListener(sizeEditBox);
            
            // Initially set visibility
            updateVisibility(screen);
        }
    }

    @SubscribeEvent
    public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
        if (event.getScreen() instanceof CreateWorldScreen screen) {
            updateVisibility(screen);
        }
    }

    private static void updateVisibility(CreateWorldScreen screen) {
        TabManager manager = ((CreateWorldScreenAccessor) screen).geolock$getTabManager();
        boolean shouldBeVisible = false;
        if (manager != null && manager.getCurrentTab() instanceof GeolockTab geolockTab) {
            shouldBeVisible = geolockTab.geolock$isWorldTab();
        }
        if (labelWidget != null) {
            labelWidget.visible = shouldBeVisible;
            labelWidget.active = shouldBeVisible;
        }
        if (sizeEditBox != null) {
            sizeEditBox.visible = shouldBeVisible;
            sizeEditBox.active = shouldBeVisible;
        }
    }
}
