package dev.appliedcausality.recipe.mekanism;

import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.CombinerRecipe;
import mekanism.api.recipes.SawmillRecipe;
import mekanism.api.recipes.ingredients.ItemStackIngredient;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.impl.CrushingIRecipe;
import mekanism.common.recipe.impl.EnrichingIRecipe;
import mekanism.common.recipe.impl.SmeltingIRecipe;
import mekanism.common.recipe.impl.CombinerIRecipe;
import mekanism.common.recipe.impl.SawmillIRecipe;
import mekanism.common.registries.MekanismRecipeSerializers;
import org.junit.jupiter.api.Test;

import dev.appliedcausality.internal.compat.MekanismCompatibility;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MekanismRecipeDescriptorTest {
    @Test
    void exactItemToItemApiAndBuiltInRecipeClassesRemainAvailable() throws ReflectiveOperationException {
        assertEquals(ItemStackIngredient.class,
                ItemStackToItemStackRecipe.class.getDeclaredMethod("getInput").getReturnType());
        assertEquals(ItemStack.class,
                ItemStackToItemStackRecipe.class.getDeclaredMethod("getOutput", ItemStack.class).getReturnType());
        assertEquals(long.class,
                ItemStackIngredient.class.getMethod("getNeededAmount", Object.class).getReturnType());

        assertSame(ItemStackToItemStackRecipe.class, EnrichingIRecipe.class.getSuperclass());
        assertSame(ItemStackToItemStackRecipe.class, CrushingIRecipe.class.getSuperclass());
        assertSame(ItemStackToItemStackRecipe.class, SmeltingIRecipe.class.getSuperclass());
        for (Class<?> recipeClass : new Class<?>[]{
                EnrichingIRecipe.class, CrushingIRecipe.class, SmeltingIRecipe.class}) {
            recipeClass.getConstructor(ResourceLocation.class, ItemStackIngredient.class, ItemStack.class);
        }

        assertEquals("mekanism.common.registration.impl.RecipeTypeRegistryObject",
                MekanismRecipeType.class.getDeclaredField("ENRICHING").getType().getName());
        assertEquals("mekanism.common.registration.impl.RecipeTypeRegistryObject",
                MekanismRecipeType.class.getDeclaredField("CRUSHING").getType().getName());
        assertEquals("mekanism.common.registration.impl.RecipeTypeRegistryObject",
                MekanismRecipeType.class.getDeclaredField("SMELTING").getType().getName());
        assertEquals("mekanism.common.registration.impl.RecipeSerializerRegistryObject",
                MekanismRecipeSerializers.class.getDeclaredField("ENRICHING").getType().getName());
        assertEquals("mekanism.common.registration.impl.RecipeSerializerRegistryObject",
                MekanismRecipeSerializers.class.getDeclaredField("CRUSHING").getType().getName());
        assertEquals("mekanism.common.registration.impl.RecipeSerializerRegistryObject",
                MekanismRecipeSerializers.class.getDeclaredField("SMELTING").getType().getName());
    }

    @Test
    void optionalGateAcceptsOnlyTheInspectedMekanismVersion() {
        assertTrue(MekanismCompatibility.isCompatibleVersion("10.4.16"));
        assertFalse(MekanismCompatibility.isCompatibleVersion("10.4.15"));
        assertFalse(MekanismCompatibility.isCompatibleVersion("10.4.17"));
        assertFalse(MekanismCompatibility.isCompatibleVersion(null));
    }

    @Test
    void combiningAndSawingDescriptorsMatchAuditedApi() throws ReflectiveOperationException {
        assertSame(CombinerRecipe.class, CombinerIRecipe.class.getSuperclass());
        CombinerIRecipe.class.getConstructor(ResourceLocation.class, ItemStackIngredient.class,
                ItemStackIngredient.class, ItemStack.class);
        assertEquals(ItemStackIngredient.class, CombinerRecipe.class.getDeclaredMethod("getMainInput")
                .getReturnType());
        assertEquals(ItemStackIngredient.class, CombinerRecipe.class.getDeclaredMethod("getExtraInput")
                .getReturnType());
        assertEquals(ItemStack.class, CombinerRecipe.class.getDeclaredMethod("getOutput",
                ItemStack.class, ItemStack.class).getReturnType());
        assertSame(SawmillRecipe.class, SawmillIRecipe.class.getSuperclass());
        assertEquals(double.class, SawmillRecipe.class.getDeclaredMethod("getSecondaryChance")
                .getReturnType());
        assertEquals("mekanism.common.registration.impl.RecipeTypeRegistryObject",
                MekanismRecipeType.class.getDeclaredField("COMBINING").getType().getName());
        assertEquals("mekanism.common.registration.impl.RecipeSerializerRegistryObject",
                MekanismRecipeSerializers.class.getDeclaredField("COMBINING").getType().getName());
    }
}
