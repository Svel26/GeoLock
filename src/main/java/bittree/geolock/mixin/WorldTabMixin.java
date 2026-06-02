package bittree.geolock.mixin;

import bittree.geolock.client.GeolockTab;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "net.minecraft.client.gui.screens.worldselection.CreateWorldScreen$WorldTab")
public class WorldTabMixin implements GeolockTab {
    @Override
    public boolean geolock$isWorldTab() {
        return true;
    }
}
