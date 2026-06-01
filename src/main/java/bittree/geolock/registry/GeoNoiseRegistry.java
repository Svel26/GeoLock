package bittree.geolock.registry;

import bittree.geolock.Geolock;
import bittree.geolock.worldgen.CylindricalNoise;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class GeoNoiseRegistry {
    public static final DeferredRegister<MapCodec<? extends DensityFunction>> DENSITY_FUNCTION_TYPES = 
            DeferredRegister.create(Registries.DENSITY_FUNCTION_TYPE, Geolock.MODID);

    public static final Supplier<MapCodec<CylindricalNoise>> CYLINDRICAL = 
            DENSITY_FUNCTION_TYPES.register("cylindrical", () -> CylindricalNoise.CODEC);

    public static void register(IEventBus modEventBus) {
        DENSITY_FUNCTION_TYPES.register(modEventBus);
    }
}
