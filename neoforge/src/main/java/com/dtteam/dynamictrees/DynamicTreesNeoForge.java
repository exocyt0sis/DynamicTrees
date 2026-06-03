package com.dtteam.dynamictrees;


import com.dtteam.dynamictrees.block.leaves.LeavesProperties;
import com.dtteam.dynamictrees.block.soil.SoilProperties;
import com.dtteam.dynamictrees.client.BlockColorMultipliers;
import com.dtteam.dynamictrees.config.*;
import com.dtteam.dynamictrees.event.handler.OptionalHandlers;
import com.dtteam.dynamictrees.api.registry.Registry;
import com.dtteam.dynamictrees.registry.NeoForgeRegistryHandler;
import com.dtteam.dynamictrees.registry.NeoForgeRegistryLoader;
import com.dtteam.dynamictrees.tree.family.Family;
import com.dtteam.dynamictrees.tree.species.Species;
import com.dtteam.dynamictrees.treepack.Resources;
import net.neoforged.fml.ModList;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;

import java.util.Optional;

import java.lang.reflect.Method;

@Mod(DynamicTrees.MOD_ID)
public class DynamicTreesNeoForge {

    private static final String VANILLA_BACKPORT_MOD_ID = "vanillabackport";
    private static final DefaultArtifactVersion MIN_VANILLA_BACKPORT_VERSION = new DefaultArtifactVersion("1.1.7.6");

    public DynamicTreesNeoForge(IEventBus eventBus, ModContainer container) {
        this.warnVanillaBackportCompatibility();

        eventBus.addListener(this::clientSetup);
        eventBus.addListener(this::onCommonSetup);
        eventBus.addListener(this::gatherData);

        container.registerConfig(ModConfig.Type.SERVER, DTConfigs.SERVER_CONFIG);
        container.registerConfig(ModConfig.Type.COMMON, DTConfigs.COMMON_CONFIG);
        container.registerConfig(ModConfig.Type.CLIENT, DTConfigs.CLIENT_CONFIG);

        NeoForgeRegistryHandler.setup(DynamicTrees.MOD_ID, eventBus);

        DynamicTrees.init();

        NeoForgeRegistryLoader.setup(eventBus);

        OptionalHandlers.registerHandlers();

        this.bootstrapDatagenGenerators();
    }

    private void clientSetup(final FMLClientSetupEvent event) {
        LeavesProperties.postInitClient();
        BlockColorMultipliers.cleanUp();
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        DynamicTrees.commonSetup();
    }

    private void gatherData(final GatherDataEvent event) {
        Resources.MANAGER.gatherData();
        this.invokeGatherDataHelper(event);
    }

    private void bootstrapDatagenGenerators() {
        this.invokeOptionalStatic("com.dtteam.dynamictrees.data.generator.DataGenerators", "register");
    }

    private void invokeGatherDataHelper(final GatherDataEvent event) {
        try {
            final Class<?> helperClass = Class.forName("com.dtteam.dynamictrees.data.GatherDataHelper");
            final Class<?> generatorClass = Class.forName("com.dtteam.dynamictrees.data.Generator");
            final Class<?> extraLangGeneratorClass = Class.forName("com.dtteam.dynamictrees.data.generator.DTExtraLangGenerator");
            final Method gatherAllData = helperClass.getMethod(
                    "gatherAllData",
                    String.class,
                    GatherDataEvent.class,
                    generatorClass,
                    Registry[].class
            );

            gatherAllData.invoke(
                    null,
                    DynamicTrees.MOD_ID,
                    event,
                    extraLangGeneratorClass.getConstructor().newInstance(),
                    new Registry[]{
                            SoilProperties.REGISTRY,
                            Family.REGISTRY,
                            Species.REGISTRY,
                            LeavesProperties.REGISTRY
                    }
            );
        } catch (ClassNotFoundException ignored) {
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException("Failed to run datagen helper.", exception);
        }
    }

    private void invokeOptionalStatic(final String className, final String methodName) {
        try {
            Class.forName(className).getMethod(methodName).invoke(null);
        } catch (ClassNotFoundException ignored) {
        } catch (ReflectiveOperationException exception) {
            throw new RuntimeException("Failed to invoke optional datagen bootstrap.", exception);
        }
    }

    // The Pale Garden bridge depends on Vanilla Backport staying aligned with the 1.1.7.6 behavior that this
    // backport was validated against. Anything else is allowed to load, but compatibility is no longer guaranteed.
    private void warnVanillaBackportCompatibility() {
        final Optional<? extends ModContainer> maybeContainer = ModList.get().getModContainerById(VANILLA_BACKPORT_MOD_ID);
        if (maybeContainer.isEmpty()) {
            DynamicTrees.LOG.warn("Pale Garden compatibility has only been validated with Vanilla Backport 1.1.7.6, " +
                    "but Vanilla Backport is not installed. Full compatibility with The Pale Garden update cannot be guaranteed.");
            return;
        }

        final DefaultArtifactVersion installedVersion = new DefaultArtifactVersion(
                maybeContainer.get().getModInfo().getVersion().toString()
        );
        if (installedVersion.compareTo(MIN_VANILLA_BACKPORT_VERSION) != 0) {
            DynamicTrees.LOG.warn("Pale Garden compatibility has only been validated with Vanilla Backport 1.1.7.6, " +
                    "but version {} is installed. Full compatibility with The Pale Garden update cannot be guaranteed.",
                    installedVersion);
        }
    }

}