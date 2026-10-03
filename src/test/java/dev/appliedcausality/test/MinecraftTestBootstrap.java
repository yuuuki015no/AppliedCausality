package dev.appliedcausality.test;

import java.lang.reflect.InvocationTargetException;

import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.AEKeyTypes;
import appeng.api.stacks.AEKeyTypesInternal;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.RegistryBuilder;

public final class MinecraftTestBootstrap {
    private MinecraftTestBootstrap() {
    }

    @SuppressWarnings("deprecation")
    public static synchronized void initialize() {
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (ExceptionInInitializerError error) {
            if (!isUntransformedForgeNetworkBootstrapFailure(error)
                    || BuiltInRegistries.RECIPE_TYPE.getKey(RecipeType.SMELTING) == null) {
                throw error;
            }
        }
        if (!ResourceLocation.withDefaultNamespace("smelting")
                .equals(BuiltInRegistries.RECIPE_TYPE.getKey(RecipeType.SMELTING))) {
            throw new IllegalStateException("Vanilla recipe types were not bootstrapped");
        }
        initializeAeKeyTypes();
    }

    private static boolean isUntransformedForgeNetworkBootstrapFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof NoSuchMethodException
                    && "net.minecraftforge.network.NetworkEvent.<init>()".equals(cause.getMessage())) {
                return true;
            }
        }
        return false;
    }

    private static void initializeAeKeyTypes() {
        try {
            AEKeyTypes.get(AEKeyType.items().getId());
            return;
        } catch (NullPointerException ignored) {
            // Plain JUnit does not run AE2's NewRegistryEvent lifecycle.
        }

        try {
            var builder = new RegistryBuilder<AEKeyType>()
                    .setMaxID(127)
                    .setName(ResourceLocation.fromNamespaceAndPath("ae2", "keytypes"));
            var create = RegistryBuilder.class.getDeclaredMethod("create");
            create.setAccessible(true);
            @SuppressWarnings("unchecked")
            var registry = (IForgeRegistry<AEKeyType>) create.invoke(builder);
            AEKeyTypesInternal.setRegistry(() -> registry);
            AEKeyTypes.register(AEKeyType.items());
            AEKeyTypes.register(AEKeyType.fluids());
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException exception) {
            throw new IllegalStateException("Could not initialize the AE2 key type registry for tests", exception);
        }
    }
}
