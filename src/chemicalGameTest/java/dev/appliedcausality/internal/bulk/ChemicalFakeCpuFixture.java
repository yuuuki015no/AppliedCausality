package dev.appliedcausality.internal.bulk;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.crafting.inv.ICraftingInventory;
import dev.appliedcausality.api.process.ProcessProperties;
import dev.appliedcausality.api.process.ProcessShapeId;
import dev.appliedcausality.internal.cpu.BulkOutputEscrow;
import dev.appliedcausality.internal.cpu.IBulkDispatchBridge;
import dev.appliedcausality.internal.cpu.IBulkJobBridge;
import dev.appliedcausality.internal.cpu.IBulkOutputEscrow;
import dev.appliedcausality.internal.cpu.IBulkTaskView;
import dev.appliedcausality.internal.provider.IBulkCraftingProvider;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** One-key in-memory CPU; Chemical keys come from a fully initialized GameTest registry. */
final class ChemicalFakeCpuFixture implements IBulkDispatchBridge, IBulkJobBridge,
        IBulkTaskView, ICraftingInventory {
    private final AEKey input;
    private final AEKey output;
    private final IPatternDetails pattern;
    private final IBulkCraftingProvider provider;
    private final BulkOutputEscrow escrow = new BulkOutputEscrow();
    private final Map<AEKey, Long> inventory = new HashMap<>();
    private long taskRemaining;
    private long remainingFinal;
    private long waiting;
    private long completedProgress;
    private long requesterReceived;
    private boolean taskRemoved;
    private boolean committing;

    ChemicalFakeCpuFixture(Level level, AEKey input, AEKey output, long multiplier) {
        this.input = input;
        this.output = output;
        this.taskRemaining = multiplier;
        this.remainingFinal = Math.multiplyExact(multiplier, 3L);
        inventory.put(input, Math.multiplyExact(multiplier, 2L));
        pattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(input, 2L)},
                new GenericStack[]{new GenericStack(output, 3L)}), level);
        require(pattern != null, "Chemical fake Pattern did not decode");
        var model = verifiedModel(input, output);
        provider = new IBulkCraftingProvider() {
            @Override
            public Optional<VerifiedProcessModel> getVerifiedProcessModel(IPatternDetails details) {
                return details == pattern ? Optional.of(model) : Optional.empty();
            }

            @Override
            public long getMaxBulkOperations() {
                return Long.MAX_VALUE;
            }
        };
    }

    long taskRemaining() {
        return taskRemaining;
    }

    long inputRemaining() {
        return inventory.getOrDefault(input, 0L);
    }

    long requesterReceived() {
        return requesterReceived;
    }

    long escrowAmount() {
        return escrow.get(output);
    }

    long remainingFinal() {
        return remainingFinal;
    }

    long completedProgress() {
        return completedProgress;
    }

    long waitingAmount() {
        return waiting;
    }

    @Override
    public void bulk$beginCommit() {
        require(!committing, "nested Chemical fake commit");
        committing = true;
    }

    @Override
    public void bulk$endCommit(boolean outcomeKnown) {
        require(committing && outcomeKnown, "unknown Chemical fake commit outcome");
        committing = false;
    }

    @Override
    public IBulkJobBridge bulk$getJob() {
        return this;
    }

    @Override
    public ICraftingInventory bulk$getInventory() {
        return this;
    }

    @Override
    public void bulk$markDirty() {
    }

    @Override
    public long bulk$simulateFinalDelivery(AEKey key, long amount) {
        require(output.equals(key), "unexpected final key");
        return amount;
    }

    @Override
    public long bulk$deliverFinalFromEscrow(AEKey key, long amount) {
        require(output.equals(key) && escrow.get(key) >= amount, "unowned final output");
        long accepted = bulk$deliverToRequester(key, Math.min(amount, remainingFinal));
        escrow.remove(key, accepted);
        bulk$decrementRemainingFinal(accepted);
        return accepted;
    }

    @Override
    public void bulk$acceptIntermediateFromEscrow(AEKey key, long amount) {
        throw new AssertionError("one-output Chemical model produced intermediate output");
    }

    @Override
    public boolean bulk$tryFinishJob() {
        return taskRemaining == 0L && remainingFinal == 0L && escrow.isEmpty();
    }

    @Override
    public Iterable<? extends IBulkCraftingProvider> bulk$getProviders(IPatternDetails details) {
        return details == pattern ? List.of(provider) : List.of();
    }

    @Override
    public long bulk$getServerConfigLimit() {
        return Long.MAX_VALUE;
    }

    @Override
    public void bulk$cleanCompletedTasks() {
        taskRemoved = taskRemaining == 0L;
    }

    @Override
    public Iterable<IBulkTaskView> bulk$tasks() {
        return taskRemoved ? List.of() : List.of(this);
    }

    @Override
    public long bulk$getWaitingFor(AEKey key) {
        return output.equals(key) ? waiting : 0L;
    }

    @Override
    public void bulk$addWaitingFor(AEKey key, long amount) {
        require(output.equals(key), "unexpected waiting key");
        waiting = BulkLongMath.checkedAdd(waiting, amount);
    }

    @Override
    public void bulk$materializeWaitingToEscrow(AEKey key, long amount) {
        require(output.equals(key) && waiting >= amount, "unowned waiting output");
        waiting = BulkLongMath.checkedSubtract(waiting, amount);
        completedProgress = BulkLongMath.checkedAdd(completedProgress, amount);
        escrow.add(key, amount);
    }

    @Override
    public IBulkOutputEscrow bulk$outputEscrow() {
        return escrow;
    }

    @Override
    public boolean bulk$isFinalOutput(AEKey key) {
        return output.equals(key);
    }

    @Override
    public AEKey bulk$getFinalOutputKey() {
        return output;
    }

    @Override
    public boolean bulk$isStandalone() {
        return false;
    }

    @Override
    public boolean bulk$isCanceled() {
        return false;
    }

    @Override
    public long bulk$getRemainingFinalAmount() {
        return remainingFinal;
    }

    @Override
    public void bulk$decrementRemainingFinal(long amount) {
        remainingFinal = BulkLongMath.checkedSubtract(remainingFinal, amount);
    }

    @Override
    public long bulk$simulateRequesterDelivery(AEKey key, long amount) {
        require(output.equals(key), "unexpected requester key");
        return amount;
    }

    @Override
    public long bulk$deliverToRequester(AEKey key, long amount) {
        require(output.equals(key), "unexpected requester key");
        long accepted = Math.min(amount, 7L);
        requesterReceived = BulkLongMath.checkedAdd(requesterReceived, accepted);
        return accepted;
    }

    @Override
    public void bulk$readOutputEscrow(CompoundTag jobData) {
        escrow.readFromNBT(jobData);
    }

    @Override
    public void bulk$writeOutputEscrow(CompoundTag jobData) {
        CompoundTag saved = escrow.writeToNBT();
        for (String key : saved.getAllKeys()) {
            jobData.put(key, saved.get(key));
        }
    }

    @Override
    public IPatternDetails pattern() {
        return pattern;
    }

    @Override
    public long remaining() {
        return taskRemaining;
    }

    @Override
    public void decrement(long amount) {
        taskRemaining = BulkLongMath.checkedSubtract(taskRemaining, amount);
    }

    @Override
    public void insert(AEKey key, long amount, Actionable mode) {
        if (mode == Actionable.MODULATE) {
            inventory.put(key, BulkLongMath.checkedAdd(inventory.getOrDefault(key, 0L), amount));
        }
    }

    @Override
    public long extract(AEKey key, long amount, Actionable mode) {
        long extracted = Math.min(inventory.getOrDefault(key, 0L), amount);
        if (mode == Actionable.MODULATE) {
            long remaining = BulkLongMath.checkedSubtract(inventory.getOrDefault(key, 0L), extracted);
            if (remaining == 0L) {
                inventory.remove(key);
            } else {
                inventory.put(key, remaining);
            }
        }
        return extracted;
    }

    @Override
    public Iterable<AEKey> findFuzzyTemplates(AEKey key) {
        return List.of();
    }

    private static VerifiedProcessModel verifiedModel(AEKey input, AEKey output) {
        try {
            Constructor<VerifiedProcessModel> constructor = VerifiedProcessModel.class.getDeclaredConstructor(
                    ResourceLocation.class, ResourceLocation.class, ResourceLocation.class,
                    ProcessShapeId.class, ResourceLocation.class, ResourceLocation.class,
                    Map.class, Map.class, Map.class, ProcessProperties.class);
            constructor.setAccessible(true);
            return constructor.newInstance(
                    id("chemical_fake_adapter"), id("chemical_fake_recipe"), id("chemical_fake_type"),
                    ProcessShapeId.of("applied_causality", "chemical_to_chemical"),
                    id("chemical_fake_family"), null,
                    Map.of(input, 2L), Map.of(output, 3L), Map.of(),
                    new ProcessProperties(true, true, true, true));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("could not create test-only Chemical model", exception);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("applied_causality", path);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
