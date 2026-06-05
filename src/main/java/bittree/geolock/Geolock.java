package bittree.geolock;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import bittree.geolock.registry.GeoNoiseRegistry;
import bittree.geolock.worldgen.PortalStitcher;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Geolock.MODID)
@EventBusSubscriber(modid = Geolock.MODID)
public class Geolock
{
    // Define mod id in a common place for everything to reference
    public static final String MODID = "geolock";
    // Track if Immersive Portals are active at runtime
    public static boolean portalsActive = false;
    // Directly reference a slf4j logger
    private static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "geolock" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "geolock" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold CreativeModeTabs which will all be registered under the "geolock" namespace
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    // Creates a new Block with the id "geolock:example_block", combining the namespace and path
    public static final DeferredBlock<Block> EXAMPLE_BLOCK = BLOCKS.registerSimpleBlock("example_block", BlockBehaviour.Properties.of().mapColor(MapColor.STONE));
    // Creates a new BlockItem with the id "geolock:example_block", combining the namespace and path
    public static final DeferredItem<BlockItem> EXAMPLE_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("example_block", EXAMPLE_BLOCK);

    // Creates a new food item with the id "geolock:example_id", nutrition 1 and saturation 2
    public static final DeferredItem<Item> EXAMPLE_ITEM = ITEMS.registerSimpleItem("example_item", new Item.Properties().food(new FoodProperties.Builder()
            .alwaysEdible().nutrition(1).saturationModifier(2f).build()));

    // Creates a creative tab with the id "geolock:example_tab" for the example item, that is placed after the combat tab
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EXAMPLE_TAB = CREATIVE_MODE_TABS.register("example_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.geolock"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> EXAMPLE_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(EXAMPLE_ITEM.get()); // Add the example item to the tab. For your own tabs, this method is preferred over the event
            }).build());

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public Geolock(IEventBus modEventBus, ModContainer modContainer)
    {
        // Load server configuration
        GeolockServerConfig.load();

        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(Config::onLoad);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so tabs get registered
        CREATIVE_MODE_TABS.register(modEventBus);
        
        // Register custom density functions
        GeoNoiseRegistry.register(modEventBus);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::registerPayloads);

        // Register client events cleanly if physical client to avoid deprecation warnings
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(ClientModEvents::onClientSetup);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(bittree.geolock.client.ClientScreenEvents.class);
        }

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void registerPayloads(final net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        final net.neoforged.neoforge.network.registration.PayloadRegistrar registrar = event.registrar(MODID);
        registrar.playToClient(
                SyncWorldSizePayload.TYPE,
                SyncWorldSizePayload.STREAM_CODEC,
                (payload, context) -> {
                    context.enqueueWork(() -> {
                        GeolockServerConfig.worldBoundaryWidth = payload.width();
                        LOGGER.info("[GeoLock] Synced world boundary width from server: {}", payload.width());
                    });
                }
        );
    }

    private void commonSetup(final FMLCommonSetupEvent event)
    {
        // Some common setup code
        LOGGER.info("HELLO FROM COMMON SETUP");

        try {
            LOGGER.info("=== Methods of DensityFunction.FunctionContext ===");
            for (java.lang.reflect.Method m : net.minecraft.world.level.levelgen.DensityFunction.FunctionContext.class.getDeclaredMethods()) {
                LOGGER.info("Method: {} {}({})", m.getReturnType().getSimpleName(), m.getName(), 
                            java.util.Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName).collect(java.util.stream.Collectors.joining(", ")));
            }
        } catch (Exception e) {
            LOGGER.error("Failed to inspect FunctionContext", e);
        }

        if (Config.logDirtBlock)
            LOGGER.info("DIRT BLOCK >> {}", BuiltInRegistries.BLOCK.getKey(Blocks.DIRT));

        LOGGER.info(Config.magicNumberIntroduction + Config.magicNumber);

        Config.items.forEach((item) -> LOGGER.info("ITEM >> {}", item.toString()));
    }

    // Add the example block item to the building blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event)
    {
        if (event.getTabKey() == CreativeModeTabs.BUILDING_BLOCKS)
            event.accept(EXAMPLE_BLOCK_ITEM);
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event)
    {
        LOGGER.info("[GeoLock] Server is starting, loading world size config...");
        java.nio.file.Path savePath = event.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        java.io.File worldDir = savePath.toFile();

        double sizeToUse = GeolockServerConfig.worldBoundaryWidth;
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient() && bittree.geolock.client.ClientScreenEvents.hasTempNewWorldSize) {
            sizeToUse = bittree.geolock.client.ClientScreenEvents.tempNewWorldSize;
            bittree.geolock.client.ClientScreenEvents.hasTempNewWorldSize = false; // Reset
            LOGGER.info("[GeoLock] Found temp custom size from client UI: {}", sizeToUse);
        }

        GeolockServerConfig.loadOrInitializeWorldSize(worldDir, sizeToUse);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event)
    {
        ServerLevel serverLevel = event.getServer().overworld();
        if (serverLevel != null) {
            portalsActive = PortalStitcher.stitchOverworld(serverLevel);
            LOGGER.info("[GeoLock] Immersive Portals wrapping zone initialization status: {}", portalsActive);

            try {
                net.minecraft.world.level.chunk.ChunkGenerator generator = serverLevel.getChunkSource().getGenerator();
                LOGGER.info("[GeoLock] Active ChunkGenerator class: {}", generator.getClass().getName());
                if (generator instanceof NoiseBasedChunkGenerator noiseGen) {
                    Holder<NoiseGeneratorSettings> settingsHolder = noiseGen.generatorSettings();
                    LOGGER.info("[GeoLock] Active GeneratorSettings key: {}", settingsHolder.unwrapKey().map(Object::toString).orElse("direct/unbound"));
                    NoiseRouter router = settingsHolder.value().noiseRouter();
                    LOGGER.info("[GeoLock] Active NoiseRouter fields check: finalDensity class: {}", 
                                router.finalDensity() != null ? router.finalDensity().getClass().getName() : "null");
                    LOGGER.info("[GeoLock] Active NoiseRouter fields check: temperature class: {}", 
                                router.temperature() != null ? router.temperature().getClass().getName() : "null");
                }
            } catch (Exception e) {
                LOGGER.error("[GeoLock] Failed to inspect active chunk generator", e);
            }
        }
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event)
    {
        // NoiseRouter wrapping is handled by leaf-level mixins.
        // See NoiseDensityFunctionMixin, ShiftedNoiseDensityFunctionMixin, etc.
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event)
    {
        if (!GeolockServerConfig.enableWorldLooping) {
            return;
        }

        net.minecraft.world.entity.player.Player player = event.getEntity();
        if (player.level().isClientSide() || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }

        if (serverPlayer.level().dimension() != Level.OVERWORLD) {
            return;
        }

        // When Immersive Portals are active, pre-load chunks at boundaries
        // for smooth portal transitions. Refresh every 10 ticks for responsiveness.
        if (portalsActive) {
            if (serverPlayer.tickCount % 10 == 0) {
                double w = GeolockServerConfig.worldBoundaryWidth;
                // Use a generous threshold: view distance + blend zone + buffer
                double threshold = serverPlayer.getServer().getPlayerList().getViewDistance() * 16.0
                                 + GeolockServerConfig.blendZoneWidth + 64.0;
                bittree.geolock.worldgen.PortalChunkLoaderHelper.updatePlayerChunkLoaders(
                    serverPlayer, serverPlayer.getX(), serverPlayer.getZ(), w, threshold
                );
            }
            return;
        }

        // Fallback: if portals are not active, log a warning periodically
        if (serverPlayer.tickCount % 200 == 0) {
            LOGGER.warn("[GeoLock] Portals not active for player {} at X={}, Z={}. World looping may not be seamless.",
                        serverPlayer.getName().getString(), serverPlayer.getX(), serverPlayer.getZ());
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            LOGGER.info("[GeoLock] Syncing world boundary width ({}) to player {}", 
                        GeolockServerConfig.worldBoundaryWidth, serverPlayer.getName().getString());
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                serverPlayer, 
                new SyncWorldSizePayload(GeolockServerConfig.worldBoundaryWidth)
            );
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (portalsActive && event.getEntity() instanceof ServerPlayer serverPlayer) {
            bittree.geolock.worldgen.PortalChunkLoaderHelper.cleanupPlayer(serverPlayer);
        }
    }

    // Client-side initialization class (no longer annotated with deprecated @EventBusSubscriber)
    public static class ClientModEvents
    {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event)
        {
            // Some client setup code
            LOGGER.info("HELLO FROM CLIENT SETUP");
            LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
        }
    }
}
