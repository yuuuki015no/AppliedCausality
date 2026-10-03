package dev.appliedcausality.blockentity;

import appeng.api.crafting.IPatternDetails;
import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.GridFlags;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.KeyCounter;
import appeng.blockentity.grid.AENetworkInvBlockEntity;
import appeng.util.inv.AppEngInternalInventory;
import appeng.util.Platform;
import com.mojang.logging.LogUtils;
import dev.appliedcausality.api.process.PatternContext;
import dev.appliedcausality.content.AppliedCausalityContent;
import dev.appliedcausality.internal.provider.IBulkCraftingProvider;
import dev.appliedcausality.internal.provider.FallbackTransaction;
import dev.appliedcausality.internal.provider.ProviderPatternCache;
import dev.appliedcausality.internal.provider.ProviderPatternStatus;
import dev.appliedcausality.internal.provider.ProviderReloadCoordinator;
import dev.appliedcausality.internal.provider.ProcessingPatternItemFilter;
import dev.appliedcausality.internal.provider.ReloadAwareProvider;
import dev.appliedcausality.internal.provider.RetainedInstallerInventory;
import dev.appliedcausality.internal.binding.PatternFingerprint;
import dev.appliedcausality.internal.binding.ProcessBinding;
import dev.appliedcausality.internal.binding.ProcessBindingTarget;
import dev.appliedcausality.internal.binding.ProviderBindingStore;
import dev.appliedcausality.internal.bulk.BulkExecutionMetrics;
import dev.appliedcausality.internal.config.ConfiguredRuleService;
import dev.appliedcausality.internal.config.ConfiguredRuleSnapshot;
import dev.appliedcausality.internal.config.ConfiguredUnlockPolicy;
import dev.appliedcausality.internal.provider.ProviderUnlockPolicy;
import dev.appliedcausality.internal.registry.ProcessModelResolver;
import dev.appliedcausality.internal.validation.VerifiedProcessModel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;

/** Singular Pattern Provider state and AE2 crafting-provider service. */
public final class SingularPatternProviderBlockEntity extends AENetworkInvBlockEntity
        implements ICraftingProvider, IBulkCraftingProvider, ReloadAwareProvider {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int PATTERN_SLOT_COUNT = 9;
    public static final int INSTALLER_SLOT_COUNT = RetainedInstallerInventory.SLOT_COUNT;
    public static final int DATA_VERSION = 4;

    private static final String PROVIDER_DATA_TAG = "applied_causality";
    private static final ResourceLocation CREATIVE_BLOCK_ID = ResourceLocation.fromNamespaceAndPath(
            "applied_causality", "creative_singular_pattern_provider");
    private static final String DATA_VERSION_TAG = "dataVersion";
    private static final String BINDINGS_TAG = "bindings";
    private static final String FALLBACK_TAG = "fallback";
    private static final String INSTALLERS_TAG = "installers";
    private final boolean creative;
    private final AppEngInternalInventory patternInventory = new AppEngInternalInventory(
            this, PATTERN_SLOT_COUNT, 1, ProcessingPatternItemFilter.INSTANCE);
    // Deliberately separate from AE2's exposed pattern inventory: automation cannot alter grants.
    private final RetainedInstallerInventory installers = new RetainedInstallerInventory(this, this::canInstall);

    private ProviderPatternCache patternCache = ProviderPatternCache.empty();
    private ProviderPatternCache pendingPreviousCache;
    private boolean pendingInventoryRebuild;
    private long presentationVersion;
    private ProviderBindingStore bindings = ProviderBindingStore.empty(PATTERN_SLOT_COUNT);
    private boolean loading;
    private FallbackTransaction fallback = new FallbackTransaction();
    private boolean deferredRecoveryDrop;
    private boolean recoveryReplacesBlockDrop;

    public SingularPatternProviderBlockEntity(BlockPos pos, BlockState state) {
        this(isCreativeState(state)
                ? AppliedCausalityContent.CREATIVE_SINGULAR_PATTERN_PROVIDER_BLOCK_ENTITY.get()
                : AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER_BLOCK_ENTITY.get(), pos, state);
    }

    SingularPatternProviderBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        this.creative = isCreativeState(state);
        getMainNode()
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .addService(ICraftingProvider.class, this);
    }

    @Override
    public InternalInventory getInternalInventory() {
        return patternInventory;
    }

    public InternalInventory getInstallerInventory() {
        return installers.inventory();
    }

    public boolean isCreative() {
        return creative;
    }

    private static boolean isCreativeState(BlockState state) {
        return CREATIVE_BLOCK_ID.equals(ForgeRegistries.BLOCKS.getKey(state.getBlock()));
    }

    public Set<ResourceLocation> installedGrants() {
        ConfiguredRuleSnapshot rules = activeRules();
        return ConfiguredUnlockPolicy.grants(rules, installers.activeStacks(rules.installerSlots()));
    }

    public boolean canInstall(ItemStack stack) {
        return canInstall(0, stack);
    }

    public boolean canInstall(int slot, ItemStack stack) {
        if (stack.isEmpty() || level == null || level.getServer() == null) {
            return false;
        }
        ConfiguredRuleSnapshot rules = activeRules();
        return slot >= 0 && slot < rules.installerSlots()
                && rules.installers().values().stream()
                .anyMatch(definition -> ConfiguredUnlockPolicy.matchesInstaller(definition, stack));
    }

    @Override
    public void onChangeInventory(InternalInventory inv, int slot) {
        if (loading || isClientSide()) {
            return;
        }
        if (inv == installers.inventory()) {
            // AE2 calls saveChanges only after this callback returns. Do not run
            // adapters or crafting-service refresh while its notification flag is set.
            queueInventoryRebuild(patternCache);
        } else if (inv == patternInventory) {
            ProviderPatternCache previous = patternCache;
            presentationVersion++;
            try {
                rebuildPatternCache();
            } catch (RuntimeException exception) {
                // AE2 15.4.10 has no finally around its notification flag.
                // Returning lets it save the changed stack and clear that flag.
                queueInventoryRebuild(previous);
                LOGGER.error("Provider inventory cache rebuild failed; retrying after inventory save", exception);
            }
        }
    }

    private void queueInventoryRebuild(ProviderPatternCache previous) {
        if (!pendingInventoryRebuild) {
            pendingPreviousCache = previous;
        }
        pendingInventoryRebuild = true;
        patternCache = ProviderPatternCache.empty();
        presentationVersion++;
    }

    /** Runs only after AE2's inventory mutation callback and saveChanges have returned. */
    public void flushPendingInventoryRebuild() {
        if (!pendingInventoryRebuild || level == null || level.isClientSide() || isRemoved()) {
            return;
        }
        ProviderPatternCache previous = pendingPreviousCache;
        pendingPreviousCache = null;
        pendingInventoryRebuild = false;
        try {
            rebuildPatternCache(previous);
        } catch (RuntimeException exception) {
            patternCache = ProviderPatternCache.empty();
            LOGGER.error("Provider inventory cache rebuild failed after inventory save; provider remains unavailable",
                    exception);
        }
    }

    public long presentationVersion() {
        return presentationVersion;
    }

    @Override
    public void onReady() {
        super.onReady();
        if (!isClientSide()) {
            ProviderReloadCoordinator.register(this);
            rebuildPatternCache();
        }
    }

    @Override
    public void onChunkUnloaded() {
        ProviderReloadCoordinator.unregister(this);
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        ProviderReloadCoordinator.unregister(this);
        super.setRemoved();
    }

    @Override
    public void loadTag(CompoundTag data) {
        patternCache = ProviderPatternCache.empty();
        pendingInventoryRebuild = false;
        pendingPreviousCache = null;
        fallback = loadFallback(data);
        bindings = loadBindings(data);
        loading = true;
        try {
            super.loadTag(data);
            loadInstallers(data);
        } finally {
            loading = false;
        }
        if (removeMismatchedBindings()) {
            setChanged();
        }
    }

    @Override
    public void saveAdditional(CompoundTag data) {
        // Never serialize a binding for a definition that is no longer in its
        // slot, even if inventory mutation occurred before level attachment.
        removeMismatchedBindings();
        super.saveAdditional(data);
        CompoundTag providerData = new CompoundTag();
        providerData.putInt(DATA_VERSION_TAG, DATA_VERSION);
        providerData.put(BINDINGS_TAG, bindings.save());
        providerData.put(FALLBACK_TAG, fallback.save());
        providerData.put(INSTALLERS_TAG, installers.save());
        data.put(PROVIDER_DATA_TAG, providerData);
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return patternCache.advertisedPatterns(activeGeneration());
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        BulkExecutionMetrics.normalPushPattern();
        if (level == null || level.isClientSide() || isRemoved() || isBusy()) {
            return false;
        }
        var model = getVerifiedProcessModel(patternDetails).orElse(null);
        if (!fallback.accept(model, inputHolder, level.getGameTime())) {
            return false;
        }
        setChanged();
        return true;
    }

    @Override
    public boolean isBusy() {
        return isRemoved() || fallback.isBusy() || !getMainNode().isActive();
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
            SingularPatternProviderBlockEntity provider) {
        if (level.isClientSide() || provider.isRemoved()) {
            return;
        }
        provider.flushPendingInventoryRebuild();
        if (!provider.getMainNode().isActive()) {
            return;
        }
        try {
            provider.fallback.deliver(level.getGameTime(), (key, amount) -> {
                var grid = provider.getMainNode().getGrid();
                if (provider.isRemoved() || !provider.getMainNode().isActive() || grid == null) {
                    return 0L;
                }
                return grid.getStorageService().getInventory().insert(
                        key, amount, Actionable.MODULATE, IActionSource.ofMachine(provider));
            }, provider::setChanged);
        } finally {
            // A requester/storage callback can synchronously remove this block.
            // Drop only after exact acceptance is known, even if the BE is gone.
            if (provider.deferredRecoveryDrop && !provider.fallback.isDelivering()) {
                provider.deferredRecoveryDrop = false;
                Platform.spawnDrops(level, pos, List.of(provider.takeRecoveryItem()));
            }
        }
    }

    /** The recovery item replaces the ordinary empty block drop. */
    public boolean hasFallbackRecovery() {
        return fallback.hasOutput() || fallback.isDelivering();
    }

    public boolean replacesNormalBlockDrop() {
        // Player mining removes the BE before evaluating its normal loot.
        return recoveryReplacesBlockDrop || hasFallbackRecovery();
    }

    @Override
    public void addAdditionalDrops(Level level, BlockPos pos, List<ItemStack> drops) {
        // Forge has already restored the source item's count/NBT when it rolls
        // back a canceled placement. The transient BE owns no additional drop.
        // Skip the inherited pattern drops as well as fallback recovery.
        if (level.restoringBlockSnapshots) {
            return;
        }
        super.addAdditionalDrops(level, pos, drops);
        drops.addAll(installers.drops());
        if (hasFallbackRecovery()) {
            recoveryReplacesBlockDrop = true;
            fallback.beginRemoval();
            if (fallback.isDelivering()) {
                deferredRecoveryDrop = true;
            } else {
                drops.add(takeRecoveryItem());
            }
        }
    }

    @Override
    public void clearContent() {
        super.clearContent();
        installers.clear();
        // Wrench removal calls addAdditionalDrops first, which owns the transfer.
        // An isolated inventory clear is not permission to discard committed
        // output or to mint a recovery block while this block remains placed.
    }

    private ItemStack takeRecoveryItem() {
        var item = new ItemStack(isCreative()
                ? AppliedCausalityContent.CREATIVE_SINGULAR_PATTERN_PROVIDER_ITEM.get()
                : AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER_ITEM.get());
        var providerData = new CompoundTag();
        providerData.putInt(DATA_VERSION_TAG, DATA_VERSION);
        providerData.put(FALLBACK_TAG, fallback.takeForRemoval());
        providerData.put(INSTALLERS_TAG, new ListTag());
        var blockEntityData = new CompoundTag();
        blockEntityData.put(PROVIDER_DATA_TAG, providerData);
        item.addTagElement("BlockEntityTag", blockEntityData);
        setChanged();
        return item;
    }

    private void loadInstallers(CompoundTag data) {
        if (!data.contains(PROVIDER_DATA_TAG, Tag.TAG_COMPOUND)) {
            installers.load(new ListTag());
            return;
        }
        CompoundTag providerData = data.getCompound(PROVIDER_DATA_TAG);
        int version = providerData.getInt(DATA_VERSION_TAG);
        if (version < DATA_VERSION) {
            if (providerData.contains(INSTALLERS_TAG)) {
                throw new IllegalArgumentException("Unexpected Installer inventory in legacy provider data");
            }
            installers.load(new ListTag());
            return;
        }
        if (!(providerData.get(INSTALLERS_TAG) instanceof ListTag entries)
                || (entries.size() > 0 && entries.getElementType() != Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Missing Installer inventory");
        }
        // Removed definitions remain stored even when the current rules no longer match them.
        installers.load(entries);
    }

    private static FallbackTransaction loadFallback(CompoundTag data) {
        if (!data.contains(PROVIDER_DATA_TAG)) {
            return new FallbackTransaction();
        }
        if (!data.contains(PROVIDER_DATA_TAG, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Invalid provider data compound");
        }
        var providerData = data.getCompound(PROVIDER_DATA_TAG);
        int version = providerData.getInt(DATA_VERSION_TAG);
        // v1 inventory -> v2 bindings -> v3 fallback; migrate before ownership decode.
        if (!providerData.contains(DATA_VERSION_TAG, Tag.TAG_INT) || version < 1 || version > DATA_VERSION) {
            throw new IllegalArgumentException("Unsupported provider data version: " + version);
        }
        if (!providerData.contains(FALLBACK_TAG)) {
            if (version >= 3) {
                throw new IllegalArgumentException("Missing fallback transaction");
            }
            return new FallbackTransaction();
        }
        if (!providerData.contains(FALLBACK_TAG, Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Invalid fallback compound");
        }
        return FallbackTransaction.load(providerData.getCompound(FALLBACK_TAG));
    }

    @Override
    public Optional<VerifiedProcessModel> getVerifiedProcessModel(IPatternDetails pattern) {
        return patternCache.verifiedModel(pattern, activeGeneration());
    }

    @Override
    public long getMaxBulkOperations() {
        return Long.MAX_VALUE;
    }

    public List<ProviderPatternStatus> getPatternStatuses() {
        return patternCache.statuses(activeGeneration());
    }

    /** Returns only individually Core-validated identities for the manual binding menu. */
    public List<ProcessBindingTarget> findBindingTargets(int slot) {
        if (slot < 0 || slot >= PATTERN_SLOT_COUNT || level == null || level.isClientSide()
                || !cacheIsCurrent()) {
            return List.of();
        }
        ItemStack definition = patternInventory.getStackInSlot(slot);
        if (definition.isEmpty()) {
            return List.of();
        }
        IPatternDetails pattern = PatternDetailsHelper.decodePattern(definition, level);
        List<ProcessBindingTarget> targets = pattern == null ? List.of()
                : ProcessModelResolver.findBindingTargets(new PatternContext(level, pattern));
        return cacheIsCurrent() ? targets : List.of();
    }

    /** Current fingerprint guard for one menu-open snapshot. */
    public boolean matchesPatternFingerprint(int slot, Optional<PatternFingerprint> expected) {
        if (slot < 0 || slot >= PATTERN_SLOT_COUNT || expected.isEmpty()) {
            return false;
        }
        ItemStack definition = patternInventory.getStackInSlot(slot);
        return !definition.isEmpty()
                && expected.orElseThrow().equals(PatternFingerprint.fromDefinition(definition));
    }

    public Optional<PatternFingerprint> patternFingerprint(int slot) {
        if (slot < 0 || slot >= PATTERN_SLOT_COUNT) {
            return Optional.empty();
        }
        ItemStack definition = patternInventory.getStackInSlot(slot);
        return definition.isEmpty() ? Optional.empty()
                : Optional.of(PatternFingerprint.fromDefinition(definition));
    }

    /** Current provider-side manual selection for presentation only. */
    public Optional<ProcessBindingTarget> currentBindingTarget(int slot) {
        if (!cacheIsCurrent()) {
            return Optional.empty();
        }
        Optional<PatternFingerprint> fingerprint = patternFingerprint(slot);
        if (fingerprint.isEmpty()) {
            return Optional.empty();
        }
        return bindings.matching(slot, fingerprint.orElseThrow())
                .map(binding -> new ProcessBindingTarget(binding.adapterId(), binding.recipeId()));
    }

    /**
     * Server-side binding entry point reserved for the deferred selection UI.
     * Invalid identities are never persisted.
     */
    public boolean trySetProcessBinding(int slot, ResourceLocation adapterId, ResourceLocation recipeId) {
        Objects.requireNonNull(adapterId, "adapterId");
        Objects.requireNonNull(recipeId, "recipeId");
        if (slot < 0 || slot >= PATTERN_SLOT_COUNT || level == null || level.isClientSide()
                || !cacheIsCurrent()) {
            return false;
        }
        if (removeMismatchedBindings()) {
            setChanged();
        }

        ItemStack definition = patternInventory.getStackInSlot(slot);
        if (definition.isEmpty()) {
            return false;
        }
        IPatternDetails pattern = PatternDetailsHelper.decodePattern(definition, level);
        if (pattern == null) {
            return false;
        }

        ProcessBinding binding = new ProcessBinding(
                slot, PatternFingerprint.fromDefinition(definition), adapterId, recipeId);
        if (ProcessModelResolver.legacyMekanismId(adapterId)) {
            return false;
        }
        if (hasConflictingBindingInOtherSlot(binding)) {
            return false;
        }
        var validation = ProcessModelResolver.resolveBound(new PatternContext(level, pattern), binding);
        if (validation.model().isEmpty()) {
            return false;
        }

        bindings.put(binding);
        setChanged();
        presentationVersion++;
        rebuildPatternCache();
        return true;
    }

    public boolean clearProcessBinding(int slot) {
        if (slot < 0 || slot >= PATTERN_SLOT_COUNT
                || level == null || level.isClientSide()
                || !bindings.remove(slot)) {
            return false;
        }
        setChanged();
        presentationVersion++;
        rebuildPatternCache();
        return true;
    }

    @Override
    public void invalidateForRecipeReload() {
        pendingInventoryRebuild = false;
        pendingPreviousCache = null;
        presentationVersion++;
        ProviderPatternCache previous = patternCache;
        patternCache = ProviderPatternCache.empty();
        requestProviderUpdateIfChanged(previous);
    }

    @Override
    public void rebuildAfterRecipeReload() {
        rebuildPatternCache();
    }

    private void rebuildPatternCache() {
        rebuildPatternCache(patternCache);
    }

    private void rebuildPatternCache(ProviderPatternCache previous) {
        if (removeMismatchedBindings()) {
            setChanged();
        }
        if (level == null || level.isClientSide()) {
            return;
        }

        patternCache = ProviderPatternCache.empty();
        try {
            migrateLegacyBindings();
            long generation = activeGeneration();
            ConfiguredRuleSnapshot rules = activeRules();
            var grants = ConfiguredUnlockPolicy.grants(rules, installers.activeStacks(rules.installerSlots()));
            boolean creative = isCreative();
            patternCache = ProviderPatternCache.rebuild(
                    generation,
                    patternInventory,
                    definition -> PatternDetailsHelper.decodePattern(definition, level),
                    pattern -> ProcessModelResolver.resolve(new PatternContext(level, pattern)),
                    bindings,
                    (binding, pattern) -> ProcessModelResolver.resolveBound(
                            new PatternContext(level, pattern), binding),
                    model -> ProviderUnlockPolicy.allows(creative, rules, model, grants),
                    true);
            if (!cacheIsCurrent()) {
                patternCache = ProviderPatternCache.empty();
            }
        } finally {
            requestProviderUpdateIfChanged(previous);
        }
    }

    private void migrateLegacyBindings() {
        boolean changed = false;
        for (int slot = 0; slot < PATTERN_SLOT_COUNT; slot++) {
            ItemStack definition = patternInventory.getStackInSlot(slot);
            if (definition.isEmpty()) {
                continue;
            }
            PatternFingerprint fingerprint = PatternFingerprint.fromDefinition(definition);
            Optional<ProcessBinding> binding = bindings.matching(slot, fingerprint);
            if (binding.isEmpty() || !ProcessModelResolver.legacyMekanismId(binding.orElseThrow().adapterId())) {
                continue;
            }
            IPatternDetails pattern = PatternDetailsHelper.decodePattern(definition, level);
            if (pattern == null) {
                continue;
            }
            Optional<ProcessBinding> migrated = ProcessModelResolver.migrateLegacyBinding(
                    new PatternContext(level, pattern), binding.orElseThrow());
            if (migrated.isPresent()) {
                bindings.put(migrated.orElseThrow());
                changed = true;
            }
        }
        if (changed) {
            setChanged();
        }
    }

    private void requestProviderUpdateIfChanged(ProviderPatternCache previous) {
        if (!previous.hasSameStatusAndAvailability(patternCache)) {
            presentationVersion++;
            requestProviderUpdate();
        }
    }

    private long activeGeneration() {
        return level == null || level.getServer() == null
                ? 0 : ConfiguredRuleService.generation(level.getServer());
    }

    private ConfiguredRuleSnapshot activeRules() {
        return level == null || level.getServer() == null ? ConfiguredRuleSnapshot.empty()
                : ConfiguredRuleService.snapshot(level.getServer());
    }

    private boolean cacheIsCurrent() {
        // Constructor-free recipe-level tests have no MinecraftServer and
        // resolve binding candidates directly, without a derived cache.
        if (level != null && level.getServer() == null) {
            return true;
        }
        return patternCache.isCurrent(activeGeneration());
    }

    private void requestProviderUpdate() {
        if (getMainNode().isReady()) {
            ICraftingProvider.requestUpdate(getMainNode());
        }
    }

    private ProviderBindingStore loadBindings(CompoundTag data) {
        if (!data.contains(PROVIDER_DATA_TAG, Tag.TAG_COMPOUND)) {
            return ProviderBindingStore.empty(PATTERN_SLOT_COUNT);
        }
        CompoundTag providerData = data.getCompound(PROVIDER_DATA_TAG);
        int version = providerData.getInt(DATA_VERSION_TAG);
        if ((version < 2 || version > DATA_VERSION)
                || !providerData.contains(BINDINGS_TAG, Tag.TAG_COMPOUND)) {
            return ProviderBindingStore.empty(PATTERN_SLOT_COUNT);
        }
        return ProviderBindingStore.load(providerData.getCompound(BINDINGS_TAG), PATTERN_SLOT_COUNT);
    }

    private boolean removeMismatchedBindings() {
        boolean changed = false;
        for (int slot = 0; slot < PATTERN_SLOT_COUNT; slot++) {
            ItemStack definition = patternInventory.getStackInSlot(slot);
            PatternFingerprint fingerprint = definition.isEmpty()
                    ? null
                    : PatternFingerprint.fromDefinition(definition);
            changed |= bindings.removeIfFingerprintMismatch(slot, fingerprint);
        }
        return changed;
    }

    private boolean hasConflictingBindingInOtherSlot(ProcessBinding proposed) {
        for (int slot = 0; slot < PATTERN_SLOT_COUNT; slot++) {
            if (slot == proposed.slot()) {
                continue;
            }
            Optional<ProcessBinding> existing = bindings.matching(slot, proposed.fingerprint());
            if (existing.isEmpty()) {
                continue;
            }
            ProcessBinding existingBinding = existing.orElseThrow();
            if (!existingBinding.adapterId().equals(proposed.adapterId())
                    || !existingBinding.recipeId().equals(proposed.recipeId())) {
                return true;
            }
        }
        return false;
    }
}
