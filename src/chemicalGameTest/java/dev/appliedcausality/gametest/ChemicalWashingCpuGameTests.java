package dev.appliedcausality.gametest;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.MEStorage;
import appeng.crafting.CraftingLink;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.internal.validation.ProcessCandidate;
import dev.appliedcausality.internal.validation.ProcessModelValidator;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import dev.appliedcausality.recipe.mekanism.MekanismRecipeAdapter;
import me.ramidzkh.mekae2.ae2.MekanismKey;
import me.ramidzkh.mekae2.ae2.MekanismKeyType;
import mekanism.api.MekanismAPI;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Real washing recipe through stock AE2 CPU and the appmek Slurry bridge. */
@GameTestHolder(AppliedCausality.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ChemicalWashingCpuGameTests {
    private static final AEFluidKey WATER = AEFluidKey.of(Fluids.WATER);

    private ChemicalWashingCpuGameTests() {
    }

    @GameTest(template = "empty")
    public static void fluidAndSlurryInputsOwnOutputAcrossRestartAndCancel(GameTestHelper helper)
            throws Exception {
        var dirty = slurry("dirty_iron");
        var clean = slurry("clean_iron");
        var pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(WATER, 7), new GenericStack(dirty, 11)},
                new GenericStack[]{new GenericStack(clean, 13)}), helper.getLevel());
        require(pattern != null, "washing Pattern did not decode");
        var context = new PatternContext(helper.getLevel(), pattern);
        var recipe = helper.getLevel().getRecipeManager()
                .byKey(ResourceLocation.fromNamespaceAndPath("pack_test", "washing")).orElseThrow();
        var candidate = MekanismRecipeAdapter.createBundled().resolve(recipe, context).orElseThrow();
        var validation = ProcessModelValidator.validate(new ProcessCandidate(
                MekanismRecipeAdapter.ID, 0, candidate), context);
        require(validation.diagnostic() == ValidationDiagnostic.VALID,
                "real Fluid/Slurry recipe did not produce a Core-verified model");

        var fixture = new Phase11StandardAe2GameTests.Fixture(helper);
        fixture.register(new ChemicalFoundationGameTests.ChemicalTestProvider(
                pattern, validation.model().orElseThrow()));
        fixture.storage.items.set(WATER, 700);
        var dirtyStore = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.SLURRY, 1_100);
        dirtyStore.inventory().setStack(0, new GenericStack(dirty, 1_100));
        var cleanStore = ChemicalFoundationGameTests.bridgedStorage(MekanismKey.SLURRY, 0);
        dirtyStore.network().getAvailableStacks(fixture.storage.items);
        fixture.networkStorage = new MixedStorage(fixture.storage,
                dirty, dirtyStore.network(), clean, cleanStore.network());
        fixture.requester.limit = 100;
        var plan = fixture.calculate(clean, 1_300);
        require(!plan.simulation(), "AE2 did not plan washing: missing=" + plan.missingItems()
                + ", used=" + plan.usedItems() + ", patterns=" + plan.patternTimes());
        fixture.submit(plan, fixture.requester);
        require(fixture.execute() == 1 && fixture.storage.items.get(WATER) == 0
                        && dirtyStore.inventory().getAmount(0) == 0
                        && fixture.requester.accepted == 100,
                "one washing dispatch did not consume exact Fluid and Slurry inputs");
        var job = fixture.job();
        require(job.bulk$outputEscrow().get(clean) == 1_200
                        && job.bulk$getRemainingFinalAmount() == 1_200
                        && job.bulk$getWaitingFor(clean) == 0,
                "washing output was not owned once by escrow");

        var saved = new CompoundTag();
        fixture.logic.writeToNBT(saved);
        var savedRequesterLink = new CompoundTag();
        fixture.requesterLink.writeToNBT(savedRequesterLink);
        var restored = new Phase11StandardAe2GameTests.Fixture(helper);
        restored.logic.readFromNBT(saved);
        require(restored.job().bulk$outputEscrow().get(clean) == 1_200,
                "washing restart changed escrow");
        restored.networkStorage = new MixedStorage(restored.storage,
                dirty, dirtyStore.network(), clean, cleanStore.network());
        restored.logic.cancel();
        require(restored.cpu.bulk$getJob() == null
                        && restored.logic.getInventory().list.get(clean) == 1_200
                        && cleanStore.inventory().getAmount(0) == 0,
                "cancel lost washing output rejected by appmek storage");
        cleanStore.inventory().setCapacity(MekanismKeyType.TYPE, 500);
        restored.logic.storeItems();
        require(cleanStore.inventory().getAmount(0) == 500
                        && restored.logic.getInventory().list.get(clean) == 700,
                "partial Slurry return changed output ownership");
        cleanStore.inventory().setCapacity(MekanismKeyType.TYPE, 1_200);
        restored.logic.storeItems();
        require(cleanStore.inventory().getAmount(0) == 1_200
                        && restored.logic.getInventory().list.get(clean) == 0,
                "retry duplicated or lost washing output");

        var reconnected = new Phase11StandardAe2GameTests.Fixture(helper);
        reconnected.logic.readFromNBT(saved);
        var reconnectJob = reconnected.job();
        var tracker = reconnected.logic.getElapsedTimeTracker();
        var completedWork = tracker.writeToNBT().getCompound("completedWork").copy();
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(clean) == 1_200,
                "disconnected washing requester lost Slurry escrow");
        var requesterLink = new CraftingLink(savedRequesterLink, reconnected.requester);
        var cpuLink = (CraftingLink) reconnected.logic.getLastLink();
        require(cpuLink.getCraftingID().equals(requesterLink.getCraftingID()),
                "washing requester identity changed on restart");
        reconnected.service.addLink(cpuLink);
        reconnected.service.addLink(requesterLink);
        reconnected.requester.limit = 0;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnectJob.bulk$outputEscrow().get(clean) == 1_200,
                "zero acceptance lost washing output");
        reconnected.requester.limit = Long.MAX_VALUE;
        reconnected.logic.tickCraftingLogic(reconnected.energy, reconnected.service);
        require(reconnected.requester.accepted == 1_200
                        && fixture.requester.accepted + reconnected.requester.accepted == 1_300
                        && reconnected.cpu.bulk$getJob() == null && requesterLink.isDone()
                        && reconnectJob.bulk$outputEscrow().isEmpty()
                        && completedWork.equals(tracker.writeToNBT().getCompound("completedWork")),
                "washing requester reconnect duplicated output or progress");
        helper.succeed();
    }

    private static MekanismKey slurry(String path) {
        return MekanismKey.of(MekanismAPI.slurryRegistry().getValue(
                ResourceLocation.fromNamespaceAndPath("mekanism", path)).getStack(1));
    }

    private record MixedStorage(Phase11StandardAe2GameTests.TestStorage fluid,
            AEKey input, MEStorage inputStorage, AEKey output, MEStorage outputStorage)
            implements MEStorage {
        private MEStorage forKey(AEKey key) {
            return key.equals(input) ? inputStorage : key.equals(output) ? outputStorage : fluid;
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            return forKey(key).extract(key, amount, mode, source);
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            return forKey(key).insert(key, amount, mode, source);
        }

        @Override
        public Component getDescription() {
            return Component.literal("Fluid and two appmek Slurry stores");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
