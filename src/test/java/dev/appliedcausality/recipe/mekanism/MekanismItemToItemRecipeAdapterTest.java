package dev.appliedcausality.recipe.mekanism;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.appliedcausality.api.process.ResolvedProcess;
import dev.appliedcausality.internal.validation.ProcessCandidate;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import dev.appliedcausality.internal.validation.ValidationTestSupport;
import dev.appliedcausality.test.MinecraftTestBootstrap;
import dev.appliedcausality.test.TestRecipeLevel;
import dev.appliedcausality.test.TestProcessingPattern;
import dev.appliedcausality.api.process.PatternContext;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.ingredients.ItemStackIngredient;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.common.crafting.conditions.ICondition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MekanismItemToItemRecipeAdapterTest {
    private static final ResourceLocation TYPE_ID = id("enriching");
    private static final RecipeType<ItemStackToItemStackRecipe> TYPE = new TestRecipeType();
    private static final TestSerializer SERIALIZER = new TestSerializer();

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void validConcreteRecipePromotesToVerifiedModelWithExactPerOperationAmounts() {
        var recipe = recipe(new TestIngredient(Items.IRON_ORE.getDefaultInstance(), 2, IngredientShape.ITEM),
                new ItemStack(Items.IRON_INGOT, 3));
        var pattern = pattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_ORE), 2)},
                1,
                new ItemStack(Items.IRON_INGOT),
                3);
        var adapter = adapterFor(TestRecipe.class, TestIngredient.class);

        ResolvedProcess resolved = adapter.resolveRecipe(recipe, RegistryAccess.EMPTY, pattern).orElseThrow();
        var validation = ValidationTestSupport.validate(
                new ProcessCandidate(MekanismRecipeAdapter.ID, 0, resolved),
                pattern,
                ignored -> Optional.of(TYPE_ID));

        assertEquals(ValidationDiagnostic.VALID, validation.diagnostic());
        var model = validation.model().orElseThrow();
        assertEquals(2L, model.inputsPerOperation().get(AEItemKey.of(Items.IRON_ORE)));
        assertEquals(3L, model.outputsPerOperation().get(AEItemKey.of(Items.IRON_INGOT)));
        assertEquals(TYPE_ID, model.processFamily());
        assertEquals(id("enrichment_chamber"), model.machineProfile());
    }

    @Test
    void wrongOutputIsRejectedBeforeCandidatePromotion() {
        var recipe = recipe(new TestIngredient(Items.IRON_ORE.getDefaultInstance(), 1, IngredientShape.ITEM),
                new ItemStack(Items.IRON_INGOT));
        var pattern = pattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_ORE), 1)},
                1,
                new ItemStack(Items.GOLD_INGOT),
                1);
        var adapter = adapterFor(TestRecipe.class, TestIngredient.class);

        assertTrue(adapter.resolveRecipe(recipe, RegistryAccess.EMPTY, pattern).isEmpty());
    }

    @Test
    void arbitraryInputAndPatternSubstitutionFailClosed() {
        var recipe = recipe(new TestIngredient(Items.IRON_ORE.getDefaultInstance(), 1, IngredientShape.ITEM),
                new ItemStack(Items.IRON_INGOT));
        var adapter = adapterFor(TestRecipe.class, TestIngredient.class);
        var arbitrary = pattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.COBBLESTONE), 1)},
                1,
                new ItemStack(Items.NETHER_STAR),
                64);
        var substituting = pattern(
                new GenericStack[]{
                        new GenericStack(AEItemKey.of(Items.IRON_ORE), 1),
                        new GenericStack(AEItemKey.of(Items.GOLD_ORE), 1)},
                1,
                new ItemStack(Items.IRON_INGOT),
                1);

        assertTrue(adapter.resolveRecipe(recipe, RegistryAccess.EMPTY, arbitrary).isEmpty());
        assertTrue(adapter.resolveRecipe(recipe, RegistryAccess.EMPTY, substituting).isEmpty());
    }

    @Test
    void customRecipeIngredientAndOutputSemanticsFailClosed() {
        var itemInput = new TestIngredient(Items.IRON_ORE.getDefaultInstance(), 1, IngredientShape.ITEM);
        var pattern = pattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_ORE), 1)},
                1,
                new ItemStack(Items.IRON_INGOT),
                1);
        var adapter = adapterFor(TestRecipe.class, TestIngredient.class);

        assertTrue(adapter.resolveRecipe(
                recipe(new TestIngredient(Items.IRON_ORE.getDefaultInstance(), 1, IngredientShape.ARRAY),
                        new ItemStack(Items.IRON_INGOT)),
                RegistryAccess.EMPTY,
                pattern).isEmpty());
        assertTrue(adapter.resolveRecipe(
                recipe(new TestIngredient(Items.IRON_ORE.getDefaultInstance(), 1, IngredientShape.CUSTOM),
                        new ItemStack(Items.IRON_INGOT)),
                RegistryAccess.EMPTY,
                pattern).isEmpty());

        var inputDependent = new InputDependentRecipe(id("dynamic"), itemInput, new ItemStack(Items.IRON_INGOT));
        assertTrue(adapter.resolveRecipe(inputDependent, RegistryAccess.EMPTY, pattern).isEmpty());

        var dynamicAdapter = adapterFor(InputDependentRecipe.class, TestIngredient.class);
        assertTrue(dynamicAdapter.resolveRecipe(inputDependent, RegistryAccess.EMPTY, pattern).isEmpty());
    }

    @Test
    void wrongTypeSerializerAndSpecialItemStateFailClosed() {
        var ingredient = new TestIngredient(Items.IRON_ORE.getDefaultInstance(), 1, IngredientShape.ITEM);
        var pattern = pattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_ORE), 1)},
                1,
                new ItemStack(Items.IRON_INGOT),
                1);
        var adapter = adapterFor(TestRecipe.class, TestIngredient.class);
        var wrongType = new TestRecipe(id("wrong_type"), ingredient, new ItemStack(Items.IRON_INGOT),
                new OtherRecipeType(), SERIALIZER);
        var wrongSerializer = new TestRecipe(id("wrong_serializer"), ingredient, new ItemStack(Items.IRON_INGOT),
                TYPE, new TestSerializer());
        var taggedInput = Items.IRON_ORE.getDefaultInstance();
        taggedInput.getOrCreateTag().putBoolean("special", true);
        var taggedPattern = pattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(taggedInput), 1)},
                1,
                new ItemStack(Items.IRON_INGOT),
                1);
        var taggedOutput = Items.IRON_INGOT.getDefaultInstance();
        taggedOutput.getOrCreateTag().putBoolean("special", true);

        assertTrue(adapter.resolveRecipe(wrongType, RegistryAccess.EMPTY, pattern).isEmpty());
        assertTrue(adapter.resolveRecipe(wrongSerializer, RegistryAccess.EMPTY, pattern).isEmpty());
        assertTrue(adapter.resolveRecipe(recipe(ingredient, new ItemStack(Items.IRON_INGOT)),
                RegistryAccess.EMPTY, taggedPattern).isEmpty());
        assertTrue(adapter.resolveRecipe(recipe(ingredient, taggedOutput),
                RegistryAccess.EMPTY, pattern).isEmpty());
    }

    @Test
    void oneGroupedAdapterRoutesOnlyItsExactDeclaredTypes() {
        RecipeType<ItemStackToItemStackRecipe> secondType = new TestRecipeType();
        RecipeType<ItemStackToItemStackRecipe> unknownType = new TestRecipeType();
        var adapter = new MekanismRecipeAdapter(List.of(
                adapterFor(TestRecipe.class, TestIngredient.class),
                new MekanismItemToItemRecipeAdapter(id("crushing"), secondType, SERIALIZER,
                        TestRecipe.class.getName(), TestIngredient.class.getName(), id("crusher"))));
        var ingredient = new TestIngredient(Items.IRON_ORE.getDefaultInstance(), 1, IngredientShape.ITEM);
        var context = new PatternContext(TestRecipeLevel.create(new RecipeManager(ICondition.IContext.EMPTY)),
                pattern(new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_ORE), 1)},
                        1, new ItemStack(Items.IRON_INGOT), 1));

        assertEquals(MekanismRecipeAdapter.ID, adapter.id());
        assertEquals(List.of(TYPE, secondType), adapter.supportedRecipeTypes());
        assertEquals(TYPE_ID, adapter.resolve(
                new TestRecipe(id("first"), ingredient, new ItemStack(Items.IRON_INGOT), TYPE, SERIALIZER),
                context).orElseThrow().recipeTypeId());
        assertEquals(id("crushing"), adapter.resolve(
                new TestRecipe(id("second"), ingredient, new ItemStack(Items.IRON_INGOT), secondType, SERIALIZER),
                context).orElseThrow().recipeTypeId());
        assertTrue(adapter.resolve(
                new TestRecipe(id("unknown"), ingredient, new ItemStack(Items.IRON_INGOT), unknownType, SERIALIZER),
                context).isEmpty());
        assertTrue(adapter.resolve(
                new TestRecipe(id("wrong_serializer"), ingredient, new ItemStack(Items.IRON_INGOT),
                        secondType, new TestSerializer()), context).isEmpty());
    }

    private static MekanismItemToItemRecipeAdapter adapterFor(
            Class<? extends ItemStackToItemStackRecipe> recipeClass,
            Class<? extends ItemStackIngredient> ingredientClass) {
        return new MekanismItemToItemRecipeAdapter(
                TYPE_ID, TYPE, SERIALIZER,
                recipeClass.getName(), ingredientClass.getName(), id("enrichment_chamber"));
    }

    private static TestRecipe recipe(ItemStackIngredient input, ItemStack output) {
        return new TestRecipe(id("recipe"), input, output, TYPE, SERIALIZER);
    }

    private static IPatternDetails pattern(
            GenericStack[] possibleInputs,
            long multiplier,
            ItemStack output,
            long outputAmount) {
        return TestProcessingPattern.create(
                new IPatternDetails.IInput[]{new TestInput(possibleInputs, multiplier)},
                new GenericStack[]{new GenericStack(AEItemKey.of(output), outputAmount)});
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("mekanism", path);
    }

    private enum IngredientShape {
        ITEM,
        ARRAY,
        CUSTOM
    }

    private static final class TestIngredient extends ItemStackIngredient {
        private final ItemStack input;
        private final int amount;
        private final IngredientShape shape;

        private TestIngredient(ItemStack input, int amount, IngredientShape shape) {
            this.input = input.copy();
            this.amount = amount;
            this.shape = shape;
        }

        @Override
        public boolean test(ItemStack stack) {
            return testType(stack) && stack.getCount() >= amount;
        }

        @Override
        public boolean testType(ItemStack stack) {
            return ItemStack.isSameItemSameTags(input, stack);
        }

        @Override
        public ItemStack getMatchingInstance(ItemStack stack) {
            return test(stack) ? stack.copyWithCount(amount) : ItemStack.EMPTY;
        }

        @Override
        public long getNeededAmount(ItemStack stack) {
            return testType(stack) ? amount : 0;
        }

        @Override
        public List<ItemStack> getRepresentations() {
            return List.of(input.copyWithCount(amount));
        }

        @Override
        public void write(FriendlyByteBuf buffer) {
            throw new UnsupportedOperationException("not used by adapter test");
        }

        @Override
        public JsonElement serialize() {
            if (shape == IngredientShape.ARRAY) {
                return new JsonArray();
            }
            var rawIngredient = new JsonObject();
            rawIngredient.addProperty(shape == IngredientShape.ITEM ? "item" : "type", "minecraft:iron_ore");
            var result = new JsonObject();
            result.add("ingredient", rawIngredient);
            if (amount != 1) {
                result.addProperty("amount", amount);
            }
            return result;
        }
    }

    private static class TestRecipe extends ItemStackToItemStackRecipe {
        private final RecipeType<ItemStackToItemStackRecipe> type;
        private final RecipeSerializer<ItemStackToItemStackRecipe> serializer;

        @SuppressWarnings("unchecked")
        private TestRecipe(
                ResourceLocation id,
                ItemStackIngredient input,
                ItemStack output,
                RecipeType<? extends ItemStackToItemStackRecipe> type,
                RecipeSerializer<? extends ItemStackToItemStackRecipe> serializer) {
            super(id, input, output);
            this.type = (RecipeType<ItemStackToItemStackRecipe>) type;
            this.serializer = (RecipeSerializer<ItemStackToItemStackRecipe>) serializer;
        }

        @Override
        public RecipeType<ItemStackToItemStackRecipe> getType() {
            return type;
        }

        @Override
        public RecipeSerializer<ItemStackToItemStackRecipe> getSerializer() {
            return serializer;
        }

        @Override
        public ItemStack getToastSymbol() {
            return Items.FURNACE.getDefaultInstance();
        }
    }

    private static final class InputDependentRecipe extends TestRecipe {
        private InputDependentRecipe(ResourceLocation id, ItemStackIngredient input, ItemStack output) {
            super(id, input, output, TYPE, SERIALIZER);
        }

        @Override
        public ItemStack getOutput(ItemStack input) {
            return new ItemStack(Items.GOLD_INGOT);
        }
    }

    private static final class TestSerializer implements RecipeSerializer<ItemStackToItemStackRecipe> {
        @Override
        public ItemStackToItemStackRecipe fromJson(ResourceLocation id, JsonObject json) {
            throw new UnsupportedOperationException("not used by adapter test");
        }

        @Override
        public ItemStackToItemStackRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer) {
            throw new UnsupportedOperationException("not used by adapter test");
        }

        @Override
        public void toNetwork(FriendlyByteBuf buffer, ItemStackToItemStackRecipe recipe) {
            throw new UnsupportedOperationException("not used by adapter test");
        }
    }

    private static final class TestRecipeType implements RecipeType<ItemStackToItemStackRecipe> {
        @Override
        public String toString() {
            return TYPE_ID.toString();
        }
    }

    private static final class OtherRecipeType implements RecipeType<ItemStackToItemStackRecipe> {
        @Override
        public String toString() {
            return id("other").toString();
        }
    }

    private record TestInput(GenericStack[] possibleInputs, long multiplier) implements IPatternDetails.IInput {
        @Override
        public GenericStack[] getPossibleInputs() {
            return possibleInputs;
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, net.minecraft.world.level.Level level) {
            return possibleInputs.length > 0 && input.equals(possibleInputs[0].what());
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
