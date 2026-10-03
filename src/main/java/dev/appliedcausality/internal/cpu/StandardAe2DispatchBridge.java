package dev.appliedcausality.internal.cpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEKey;
import appeng.crafting.inv.ICraftingInventory;
import appeng.me.service.CraftingService;
import dev.appliedcausality.internal.provider.IBulkCraftingProvider;
import dev.appliedcausality.internal.budget.BulkDispatchReservation;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;

/** Per-executeCrafting context; no scheduler/service state is retained by the CPU. */
public final class StandardAe2DispatchBridge implements IBulkDispatchBridge {
    private final IBulkCpuBridge cpu;
    private final CraftingService craftingService;
    private final Supplier<IGrid> currentGrid;

    public StandardAe2DispatchBridge(
            IBulkCpuBridge cpu,
            CraftingService craftingService,
            Supplier<IGrid> currentGrid) {
        this.cpu = Objects.requireNonNull(cpu, "cpu");
        this.craftingService = Objects.requireNonNull(craftingService, "craftingService");
        this.currentGrid = Objects.requireNonNull(currentGrid, "currentGrid");
    }

    @Override
    public void bulk$beginCommit() {
        cpu.bulk$beginCommit();
    }

    @Override
    public void bulk$endCommit(boolean outcomeKnown) {
        cpu.bulk$endCommit(outcomeKnown);
    }

    private List<ICraftingProvider> providers(IPatternDetails pattern) {
        var result = new ArrayList<ICraftingProvider>();
        // AE2 returns a bounded view of a SHARED cycling iterator. Always walk
        // one full cycle, including normal providers, to preserve its position
        // when the Bulk attempt consumes zero operations.
        craftingService.getProviders(pattern).forEach(result::add);
        return result;
    }

    @Override
    public Iterable<? extends IBulkCraftingProvider> bulk$getProviders(IPatternDetails pattern) {
        var result = new ArrayList<IBulkCraftingProvider>();
        for (var provider : providers(pattern)) {
            if (provider instanceof IBulkCraftingProvider bulk) {
                result.add(new AvailableProvider(provider, bulk));
            }
        }
        return result;
    }

    @Override
    public boolean bulk$hasDispatchCapacity() {
        return CurrentGridDispatchBudget.hasCapacity(currentGrid);
    }

    @Override
    public BulkDispatchReservation bulk$tryReserveDispatch() {
        return CurrentGridDispatchBudget.tryReserve(currentGrid);
    }

    private final class AvailableProvider implements IBulkCraftingProvider {
        private final ICraftingProvider provider;
        private final IBulkCraftingProvider bulk;

        private AvailableProvider(ICraftingProvider provider, IBulkCraftingProvider bulk) {
            this.provider = provider;
            this.bulk = bulk;
        }

        @Override
        public Optional<VerifiedProcessModel> getVerifiedProcessModel(IPatternDetails pattern) {
            // Recheck membership, busy state, and the cached model after the
            // requester SIMULATE callback, as required by engine preflight.
            boolean registered = providers(pattern).stream().anyMatch(candidate -> candidate == provider);
            return registered && !provider.isBusy() ? bulk.getVerifiedProcessModel(pattern) : Optional.empty();
        }

        @Override
        public long getMaxBulkOperations() {
            return bulk.getMaxBulkOperations();
        }
    }

    @Override
    public long bulk$getServerConfigLimit() {
        return Long.MAX_VALUE;
    }

    @Override
    public IBulkJobBridge bulk$getJob() {
        return cpu.bulk$getJob();
    }

    @Override
    public ICraftingInventory bulk$getInventory() {
        return cpu.bulk$getInventory();
    }

    @Override
    public void bulk$markDirty() {
        cpu.bulk$markDirty();
    }

    @Override
    public long bulk$simulateFinalDelivery(AEKey key, long amount) {
        return cpu.bulk$simulateFinalDelivery(key, amount);
    }

    @Override
    public long bulk$deliverFinalFromEscrow(AEKey key, long amount) {
        return cpu.bulk$deliverFinalFromEscrow(key, amount);
    }

    @Override
    public void bulk$acceptIntermediateFromEscrow(AEKey key, long amount) {
        cpu.bulk$acceptIntermediateFromEscrow(key, amount);
    }

    @Override
    public boolean bulk$tryFinishJob() {
        return cpu.bulk$tryFinishJob();
    }

    @Override
    public void bulk$cleanCompletedTasks() {
        var job = bulk$getJob();
        if (job != null) {
            job.bulk$cleanCompletedTasks();
        }
    }
}
