package bittree.geolock.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.BlockGetter;

public class ClientLevelSelector {
    public static BlockGetter getClientLevel() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level;
    }
}
