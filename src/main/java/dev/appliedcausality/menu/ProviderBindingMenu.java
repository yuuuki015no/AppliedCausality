package dev.appliedcausality.menu;

import appeng.api.stacks.AEItemKey;
import appeng.core.definitions.AEItems;
import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.blockentity.SingularPatternProviderBlockEntity;
import dev.appliedcausality.content.AppliedCausalityContent;
import dev.appliedcausality.internal.binding.PatternFingerprint;
import dev.appliedcausality.internal.binding.ProcessBindingTarget;
import dev.appliedcausality.internal.provider.ProviderPatternStatus;
import dev.appliedcausality.internal.validation.ValidationDiagnostic;
import dev.appliedcausality.network.ProviderBindingSnapshotPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.nio.charset.StandardCharsets;

/** Server-authoritative menu for the Singular Pattern Provider's manual bindings. */
public final class ProviderBindingMenu extends AbstractContainerMenu {
    public static final int MAX_CANDIDATES_PER_SLOT = ProcessBindingTarget.MAX_MENU_CHOICES;
    public static final int CLEAR_BUTTON_BASE = SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT
            * MAX_CANDIDATES_PER_SLOT;

    /** Forge caps the complete open-screen extra-data payload at 32,600 bytes. */
    static final int MAX_SNAPSHOT_WIRE_BYTES = 32_000;
    private static final int MAX_SELECTED_BINDING_LABEL_WIRE_BYTES = 256;

    /** Shared screen/menu coordinates: a horizontal AE2-style pattern row and centered inventory. */
    public static final int PATTERN_SLOT_X = 47;
    public static final int PATTERN_SLOT_Y = 30;
    public static final int INSTALLER_SLOT_X = 230;
    public static final int INSTALLER_SLOT_Y = 30;
    public static final int PLAYER_INVENTORY_X = 47;
    public static final int PLAYER_INVENTORY_Y = 159;
    public static final int PLAYER_HOTBAR_Y = 213;

    private static final int PROVIDER_SLOT_START = 0;
    private static final int INSTALLER_SLOT_START = SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT;
    private static final int PLAYER_SLOT_START = INSTALLER_SLOT_START
            + SingularPatternProviderBlockEntity.INSTALLER_SLOT_COUNT;
    private static final int PLAYER_SLOT_END = PLAYER_SLOT_START + 36;

    @Nullable
    private final SingularPatternProviderBlockEntity provider;
    private final Inventory playerInventory;
    private final BlockPos providerPos;
    private final boolean creative;
    private SnapshotState snapshotState;
    private long observedPresentationVersion;

    private ProviderBindingMenu(int containerId, Inventory playerInventory,
            @Nullable SingularPatternProviderBlockEntity provider, BlockPos providerPos,
            List<SlotSnapshot> snapshots) {
        this(containerId, playerInventory, provider, providerPos, snapshots,
                provider != null && provider.isCreative());
    }

    private ProviderBindingMenu(int containerId, Inventory playerInventory,
            @Nullable SingularPatternProviderBlockEntity provider, BlockPos providerPos,
            List<SlotSnapshot> snapshots, boolean creative) {
        super(AppliedCausalityContent.PROVIDER_BINDING_MENU.get(), containerId);
        this.provider = provider;
        this.playerInventory = playerInventory;
        this.providerPos = providerPos.immutable();
        this.creative = creative;
        this.snapshotState = new SnapshotState(snapshots, 0);
        this.observedPresentationVersion = provider == null ? 0 : provider.presentationVersion();

        Container patternInventory = provider == null
                ? new net.minecraft.world.SimpleContainer(SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT)
                : provider.getInternalInventory().toContainer();
        for (int slot = 0; slot < SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT; slot++) {
            addSlot(new ProcessingPatternSlot(patternInventory, slot, PATTERN_SLOT_X + slot * 18, PATTERN_SLOT_Y));
        }
        Container installerInventory = provider == null
                ? new net.minecraft.world.SimpleContainer(SingularPatternProviderBlockEntity.INSTALLER_SLOT_COUNT)
                : provider.getInstallerInventory().toContainer();
        for (int slot = 0; slot < SingularPatternProviderBlockEntity.INSTALLER_SLOT_COUNT; slot++) {
            addSlot(new InstallerSlot(installerInventory, slot, INSTALLER_SLOT_X,
                    INSTALLER_SLOT_Y + slot * 18, provider));
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(playerInventory, column + row * 9 + 9,
                        PLAYER_INVENTORY_X + column * 18, PLAYER_INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(playerInventory, column, PLAYER_INVENTORY_X + column * 18, PLAYER_HOTBAR_Y));
        }
    }

    public static void open(ServerPlayer player, SingularPatternProviderBlockEntity provider) {
        List<SlotSnapshot> serverSnapshots = snapshots(provider);
        List<SlotSnapshot> wireSnapshots = capSnapshotsForWire(serverSnapshots);
        BlockPos pos = provider.getBlockPos();
        NetworkHooks.openScreen(player, new SimpleMenuProvider(
                (containerId, inventory, ignored) -> new ProviderBindingMenu(
                        containerId, inventory, provider, pos, serverSnapshots),
                Component.translatable("gui." + AppliedCausality.MOD_ID
                        + (provider.isCreative() ? ".creative_provider_binding" : ".provider_binding"))), buffer -> {
            buffer.writeBlockPos(pos);
            buffer.writeBoolean(provider.isCreative());
            writeSnapshots(buffer, wireSnapshots);
        });
    }

    public static ProviderBindingMenu fromNetwork(int containerId, Inventory playerInventory, FriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        boolean creative = buffer.readBoolean();
        return new ProviderBindingMenu(containerId, playerInventory, null, pos, readSnapshots(buffer), creative);
    }

    public boolean isCreative() {
        return creative;
    }

    public List<ProcessBindingTarget> bindingTargets(int slot) {
        return snapshot(slot).targets();
    }

    public ValidationDiagnostic diagnostic(int slot) {
        return snapshot(slot).diagnostic();
    }

    public Optional<ProcessBindingTarget> selectedBinding(int slot) {
        return snapshot(slot).selectedBinding();
    }

    public boolean hasSelectedBinding(int slot) {
        return snapshot(slot).hasSelectedBinding();
    }

    public String selectedBindingLabel(int slot) {
        SlotSnapshot snapshot = snapshot(slot);
        return snapshot.selectedBinding().map(target -> target.adapterId() + " / " + target.recipeId())
                .or(() -> snapshot.selectedBindingLabel()).orElse("-");
    }

    /** Client presentation token; unlike the saturated server revision, it changes for every accepted packet. */
    public long snapshotPresentationGeneration() {
        return snapshotState.presentationGeneration();
    }

    /** Client-only application of a server-originated presentation snapshot. */
    public boolean applySnapshot(ProviderBindingSnapshotPacket packet) {
        Optional<SnapshotState> replacement = SnapshotState.applyClientPacket(
                snapshotState, containerId, packet);
        if (replacement.isEmpty()) {
            return false;
        }
        snapshotState = replacement.orElseThrow();
        return true;
    }

    @Override
    public boolean stillValid(Player player) {
        if (provider == null || !isCurrentProvider(player.level(), providerPos, provider)) {
            return false;
        }
        return stillValid(ContainerLevelAccess.create(player.level(), providerPos), player,
                creative ? AppliedCausalityContent.CREATIVE_SINGULAR_PATTERN_PROVIDER.get()
                        : AppliedCausalityContent.SINGULAR_PATTERN_PROVIDER.get());
    }

    /** Rejects menus that still point to a removed or replaced provider inventory. */
    static boolean isCurrentProvider(Level level, BlockPos providerPos, SingularPatternProviderBlockEntity provider) {
        return !provider.isRemoved() && level.getBlockEntity(providerPos) == provider;
    }

    @Override
    public boolean clickMenuButton(Player player, int buttonId) {
        if (provider == null || player.level().isClientSide()) {
            return false;
        }
        List<SlotSnapshot> before = snapshotState.snapshots();
        if (!applyButton(provider, before, buttonId)) {
            refreshServerSnapshotIfChanged(player, before);
            return false;
        }
        player.closeContainer();
        return true;
    }

    /**
     * Server-side command boundary. It accepts only identities captured by this
     * server menu and still delegates authorization to the provider validator.
     */
    static boolean applyButton(@Nullable SingularPatternProviderBlockEntity provider,
            List<SlotSnapshot> snapshots, int buttonId) {
        if (provider == null || snapshots.size() != SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT) {
            return false;
        }
        if (buttonId >= 0 && buttonId < CLEAR_BUTTON_BASE) {
            int slot = buttonId / MAX_CANDIDATES_PER_SLOT;
            int candidate = buttonId % MAX_CANDIDATES_PER_SLOT;
            SlotSnapshot snapshot = snapshots.get(slot);
            if (candidate >= snapshot.targets().size()
                    || !provider.matchesPatternFingerprint(slot, snapshot.fingerprint())) {
                return false;
            }
            ProcessBindingTarget target = snapshot.targets().get(candidate);
            if (!provider.trySetProcessBinding(slot, target.adapterId(), target.recipeId())) {
                return false;
            }
            return true;
        }
        int slot = buttonId - CLEAR_BUTTON_BASE;
        if (slot < 0 || slot >= SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT) {
            return false;
        }
        SlotSnapshot snapshot = snapshots.get(slot);
        if (snapshot.selectedBinding().isEmpty()
                || !provider.matchesPatternFingerprint(slot, snapshot.fingerprint())
                || !provider.currentBindingTarget(slot).equals(snapshot.selectedBinding())
                || !provider.clearProcessBinding(slot)) {
            return false;
        }
        return true;
    }

    @Override
    public void clicked(int slotId, int dragType, ClickType clickType, Player player) {
        List<SlotSnapshot> before = snapshotState.snapshots();
        super.clicked(slotId, dragType, clickType, player);
        refreshServerSnapshotIfChanged(player, before);
    }

    @Override
    public void broadcastChanges() {
        if (provider != null && !playerInventory.player.level().isClientSide()
                && provider.presentationVersion() != observedPresentationVersion) {
            refreshServerSnapshotIfChanged(playerInventory.player, snapshotState.snapshots());
        }
        super.broadcastChanges();
    }

    private void refreshServerSnapshotIfChanged(Player player, List<SlotSnapshot> before) {
        if (provider == null || player.level().isClientSide()) {
            return;
        }
        provider.flushPendingInventoryRebuild();
        refreshAfterServerInventoryAction(provider, before, snapshotState.revision(), containerId).ifPresent(update -> {
            snapshotState = new SnapshotState(update.serverSnapshots(), update.revision());
            if (player instanceof ServerPlayer serverPlayer) {
                dev.appliedcausality.network.AppliedCausalityNetwork.sendMenuSnapshot(
                        serverPlayer, update.packet());
            }
        });
        observedPresentationVersion = provider.presentationVersion();
    }

    /**
     * Builds the sole server-originated replacement snapshot after a menu
     * inventory operation. Kept separate so packet/revision behavior remains
     * directly testable without a live player connection.
     */
    static Optional<SnapshotUpdate> refreshAfterServerInventoryAction(
            SingularPatternProviderBlockEntity provider, List<SlotSnapshot> previousSnapshots,
            int previousRevision, int containerId) {
        if (previousRevision < 0 || containerId < 0) {
            throw new IllegalArgumentException("menu revision and container ID must be non-negative");
        }
        previousSnapshots = checkedSnapshots(previousSnapshots);
        List<SlotSnapshot> refreshed = snapshots(provider);
        if (refreshed.equals(previousSnapshots)) {
            return Optional.empty();
        }
        return Optional.of(new SnapshotUpdate(nextSnapshotRevision(previousRevision),
                refreshed, new ProviderBindingSnapshotPacket(containerId, capSnapshotsForWire(refreshed))));
    }

    /** Revision ordering is not compared across packets, so saturation preserves the non-negative contract. */
    static int nextSnapshotRevision(int revision) {
        if (revision < 0) {
            throw new IllegalArgumentException("snapshot revision must be non-negative");
        }
        return revision == Integer.MAX_VALUE ? Integer.MAX_VALUE : revision + 1;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= PLAYER_SLOT_END) {
            return ItemStack.EMPTY;
        }
        Slot source = slots.get(index);
        if (!source.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack sourceStack = source.getItem();
        ItemStack result = sourceStack.copy();
        if (index < PLAYER_SLOT_START) {
            if (!moveItemStackTo(sourceStack, PLAYER_SLOT_START, PLAYER_SLOT_END, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            boolean moved = sourceStack.is(AEItems.PROCESSING_PATTERN.asItem())
                    && moveItemStackTo(sourceStack, PROVIDER_SLOT_START, INSTALLER_SLOT_START, false);
            if (!moved && provider != null && provider.canInstall(sourceStack)) {
                moved = moveItemStackTo(sourceStack, INSTALLER_SLOT_START, PLAYER_SLOT_START, false);
            }
            if (!moved) {
                return ItemStack.EMPTY;
            }
        }
        if (sourceStack.isEmpty()) {
            source.set(ItemStack.EMPTY);
        } else {
            source.setChanged();
        }
        return result;
    }

    private SlotSnapshot snapshot(int slot) {
        if (slot < 0 || slot >= snapshotState.snapshots().size()) {
            throw new IndexOutOfBoundsException("provider pattern slot out of range: " + slot);
        }
        return snapshotState.snapshots().get(slot);
    }

    public static List<SlotSnapshot> snapshots(SingularPatternProviderBlockEntity provider) {
        var diagnostics = new ValidationDiagnostic[SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT];
        java.util.Arrays.fill(diagnostics, ValidationDiagnostic.NO_RECIPE);
        for (ProviderPatternStatus status : provider.getPatternStatuses()) {
            diagnostics[status.slot()] = status.diagnostic();
        }
        var result = new ArrayList<SlotSnapshot>(SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT);
        for (int slot = 0; slot < SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT; slot++) {
            Optional<PatternFingerprint> fingerprint = provider.patternFingerprint(slot);
            result.add(new SlotSnapshot(diagnostics[slot], fingerprint, fingerprint.isEmpty()
                    ? List.of() : provider.findBindingTargets(slot), provider.currentBindingTarget(slot)));
        }
        return checkedSnapshots(result);
    }

    public static void writeSnapshots(FriendlyByteBuf buffer, List<SlotSnapshot> snapshots) {
        snapshots = checkedSnapshots(snapshots);
        if (serializedSnapshotBytes(snapshots) > MAX_SNAPSHOT_WIRE_BYTES) {
            throw new IllegalArgumentException("Provider binding snapshot exceeds wire budget");
        }
        buffer.writeVarInt(snapshots.size());
        for (SlotSnapshot snapshot : snapshots) {
            buffer.writeVarInt(snapshot.diagnostic().ordinal());
            buffer.writeVarInt(snapshot.targets().size());
            for (ProcessBindingTarget target : snapshot.targets()) {
                buffer.writeResourceLocation(target.adapterId());
                buffer.writeResourceLocation(target.recipeId());
            }
            buffer.writeBoolean(snapshot.hasSelectedBinding());
            if (snapshot.hasSelectedBinding()) {
                buffer.writeBoolean(snapshot.selectedBinding().isPresent());
            }
            snapshot.selectedBinding().ifPresent(binding -> {
                buffer.writeResourceLocation(binding.adapterId());
                buffer.writeResourceLocation(binding.recipeId());
            });
            if (snapshot.hasSelectedBinding() && snapshot.selectedBinding().isEmpty()) {
                buffer.writeUtf(snapshot.selectedBindingLabel().orElseThrow(), MAX_SELECTED_BINDING_LABEL_WIRE_BYTES);
            }
        }
    }

    public static List<SlotSnapshot> readSnapshots(FriendlyByteBuf buffer) {
        int slotCount = buffer.readVarInt();
        if (slotCount != SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT) {
            throw new IllegalArgumentException("Unexpected provider slot count: " + slotCount);
        }
        var result = new ArrayList<SlotSnapshot>(slotCount);
        ValidationDiagnostic[] diagnostics = ValidationDiagnostic.values();
        for (int slot = 0; slot < slotCount; slot++) {
            int diagnostic = buffer.readVarInt();
            int targetCount = buffer.readVarInt();
            if (diagnostic < 0 || diagnostic >= diagnostics.length
                    || targetCount < 0 || targetCount > MAX_CANDIDATES_PER_SLOT) {
                throw new IllegalArgumentException("Invalid provider binding menu data");
            }
            var targets = new ArrayList<ProcessBindingTarget>(targetCount);
            for (int target = 0; target < targetCount; target++) {
                targets.add(new ProcessBindingTarget(buffer.readResourceLocation(), buffer.readResourceLocation()));
            }
            boolean hasSelectedBinding = buffer.readBoolean();
            Optional<ProcessBindingTarget> selectedBinding = Optional.empty();
            Optional<String> selectedBindingLabel = Optional.empty();
            if (hasSelectedBinding) {
                if (buffer.readBoolean()) {
                    selectedBinding = Optional.of(new ProcessBindingTarget(
                            buffer.readResourceLocation(), buffer.readResourceLocation()));
                } else {
                    selectedBindingLabel = Optional.of(buffer.readUtf(MAX_SELECTED_BINDING_LABEL_WIRE_BYTES));
                }
            }
            result.add(new SlotSnapshot(diagnostics[diagnostic], Optional.empty(), targets,
                    selectedBinding, selectedBindingLabel));
        }
        result = new ArrayList<>(checkedSnapshots(result));
        if (serializedSnapshotBytes(result) > MAX_SNAPSHOT_WIRE_BYTES) {
            throw new IllegalArgumentException("Provider binding snapshot exceeds wire budget");
        }
        return List.copyOf(result);
    }

    /**
     * Retains presentation data that fits Forge's open-screen packet budget.
     * Candidates are admitted round-robin so an early ambiguous slot cannot
     * hide all choices for later pattern slots.
     */
    static List<SlotSnapshot> capSnapshotsForWire(List<SlotSnapshot> snapshots) {
        snapshots = checkedSnapshots(snapshots);
        var selectedBindings = new ArrayList<Optional<ProcessBindingTarget>>(snapshots.size());
        var selectedBindingLabels = new ArrayList<Optional<String>>(snapshots.size());
        var retainedTargets = new ArrayList<List<ProcessBindingTarget>>(snapshots.size());
        int usedBytes = varIntBytes(snapshots.size());
        for (SlotSnapshot snapshot : snapshots) {
            selectedBindings.add(Optional.empty());
            selectedBindingLabels.add(Optional.empty());
            retainedTargets.add(new ArrayList<>());
            usedBytes += snapshotBaseWireBytes(snapshot.diagnostic());
        }
        if (usedBytes > MAX_SNAPSHOT_WIRE_BYTES) {
            throw new IllegalArgumentException("Provider binding snapshot metadata exceeds wire budget");
        }

        for (int slot = 0; slot < snapshots.size(); slot++) {
            SlotSnapshot snapshot = snapshots.get(slot);
            if (!snapshot.hasSelectedBinding()) {
                continue;
            }
            Optional<ProcessBindingTarget> selected = snapshot.selectedBinding();
            int fullBindingBytes = selected.map(ProviderBindingMenu::bindingTargetWireBytes).orElse(Integer.MAX_VALUE);
            if (selected.isPresent() && usedBytes + 1 + fullBindingBytes <= MAX_SNAPSHOT_WIRE_BYTES) {
                selectedBindings.set(slot, selected);
                usedBytes += 1 + fullBindingBytes;
            } else {
                String label = snapshot.selectedBindingLabel().orElseGet(() -> selected
                        .map(ProviderBindingMenu::abbreviatedBindingLabel).orElseThrow());
                label = abbreviateWireLabel(label);
                int labelBytes = utf8WireBytes(label);
                if (usedBytes + 1 + labelBytes > MAX_SNAPSHOT_WIRE_BYTES) {
                    throw new IllegalArgumentException("Provider binding label exceeds wire budget");
                }
                selectedBindingLabels.set(slot, Optional.of(label));
                usedBytes += 1 + labelBytes;
            }
        }

        boolean[] candidatePrefixClosed = new boolean[snapshots.size()];
        for (int candidate = 0; candidate < MAX_CANDIDATES_PER_SLOT; candidate++) {
            for (int slot = 0; slot < snapshots.size(); slot++) {
                List<ProcessBindingTarget> offered = snapshots.get(slot).targets();
                if (candidatePrefixClosed[slot] || candidate >= offered.size()) {
                    continue;
                }
                ProcessBindingTarget target = offered.get(candidate);
                int targetBytes = bindingTargetWireBytes(target);
                if (usedBytes + targetBytes <= MAX_SNAPSHOT_WIRE_BYTES) {
                    retainedTargets.get(slot).add(target);
                    usedBytes += targetBytes;
                } else {
                    // The client button index is also the server snapshot index: retain prefixes only.
                    candidatePrefixClosed[slot] = true;
                }
            }
        }

        var result = new ArrayList<SlotSnapshot>(snapshots.size());
        for (int slot = 0; slot < snapshots.size(); slot++) {
            SlotSnapshot snapshot = snapshots.get(slot);
            result.add(new SlotSnapshot(snapshot.diagnostic(), snapshot.fingerprint(), retainedTargets.get(slot),
                    selectedBindings.get(slot), selectedBindingLabels.get(slot)));
        }
        if (serializedSnapshotBytes(result) > MAX_SNAPSHOT_WIRE_BYTES) {
            throw new IllegalStateException("Provider binding snapshot wire accounting drifted");
        }
        return List.copyOf(result);
    }

    static int serializedSnapshotBytes(List<SlotSnapshot> snapshots) {
        snapshots = checkedSnapshots(snapshots);
        int bytes = varIntBytes(snapshots.size());
        for (SlotSnapshot snapshot : snapshots) {
            bytes += snapshotBaseWireBytes(snapshot.diagnostic());
            for (ProcessBindingTarget target : snapshot.targets()) {
                bytes += bindingTargetWireBytes(target);
            }
            bytes += selectedBindingWireBytes(snapshot);
        }
        return bytes;
    }

    private static int snapshotBaseWireBytes(ValidationDiagnostic diagnostic) {
        return varIntBytes(diagnostic.ordinal()) + varIntBytes(0) + 1;
    }

    private static int selectedBindingWireBytes(SlotSnapshot snapshot) {
        if (!snapshot.hasSelectedBinding()) {
            return 0;
        }
        return 1 + snapshot.selectedBinding().map(ProviderBindingMenu::bindingTargetWireBytes)
                .orElseGet(() -> utf8WireBytes(snapshot.selectedBindingLabel().orElseThrow()));
    }

    private static int bindingTargetWireBytes(ProcessBindingTarget target) {
        return resourceLocationWireBytes(target.adapterId()) + resourceLocationWireBytes(target.recipeId());
    }

    private static int resourceLocationWireBytes(net.minecraft.resources.ResourceLocation id) {
        return utf8WireBytes(id.toString());
    }

    private static int varIntBytes(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }

    private static int utf8WireBytes(String value) {
        int utf8Bytes = value.getBytes(StandardCharsets.UTF_8).length;
        return varIntBytes(utf8Bytes) + utf8Bytes;
    }

    private static String abbreviatedBindingLabel(ProcessBindingTarget target) {
        return abbreviateWireLabel(target.adapterId() + " / " + target.recipeId());
    }

    private static String abbreviateWireLabel(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= MAX_SELECTED_BINDING_LABEL_WIRE_BYTES) {
            return value;
        }
        String suffix = "...";
        int maximumPrefixBytes = MAX_SELECTED_BINDING_LABEL_WIRE_BYTES - suffix.length();
        StringBuilder result = new StringBuilder();
        int usedBytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = character.getBytes(StandardCharsets.UTF_8).length;
            if (usedBytes + characterBytes > maximumPrefixBytes) {
                break;
            }
            result.append(character);
            usedBytes += characterBytes;
            offset += Character.charCount(codePoint);
        }
        return result.append(suffix).toString();
    }

    private static List<SlotSnapshot> checkedSnapshots(List<SlotSnapshot> snapshots) {
        if (snapshots.size() != SingularPatternProviderBlockEntity.PATTERN_SLOT_COUNT) {
            throw new IllegalArgumentException("Unexpected provider slot count: " + snapshots.size());
        }
        return List.copyOf(snapshots);
    }

    public static record SlotSnapshot(ValidationDiagnostic diagnostic, Optional<PatternFingerprint> fingerprint,
            List<ProcessBindingTarget> targets, Optional<ProcessBindingTarget> selectedBinding,
            Optional<String> selectedBindingLabel) {
        public SlotSnapshot(ValidationDiagnostic diagnostic, Optional<PatternFingerprint> fingerprint,
                List<ProcessBindingTarget> targets, Optional<ProcessBindingTarget> selectedBinding) {
            this(diagnostic, fingerprint, targets, selectedBinding, Optional.empty());
        }

        public SlotSnapshot {
            diagnostic = java.util.Objects.requireNonNull(diagnostic, "diagnostic");
            fingerprint = java.util.Objects.requireNonNull(fingerprint, "fingerprint");
            targets = List.copyOf(java.util.Objects.requireNonNull(targets, "targets"));
            selectedBinding = java.util.Objects.requireNonNull(selectedBinding, "selectedBinding");
            selectedBindingLabel = java.util.Objects.requireNonNull(selectedBindingLabel, "selectedBindingLabel");
            if (targets.size() > MAX_CANDIDATES_PER_SLOT) {
                throw new IllegalArgumentException("Too many binding targets");
            }
            if (selectedBinding.isPresent() && selectedBindingLabel.isPresent()) {
                throw new IllegalArgumentException("Selected binding cannot have both identity and label");
            }
            if (selectedBindingLabel.filter(String::isEmpty).isPresent()
                    || selectedBindingLabel.map(value -> value.getBytes(StandardCharsets.UTF_8).length)
                    .filter(bytes -> bytes > MAX_SELECTED_BINDING_LABEL_WIRE_BYTES).isPresent()) {
                throw new IllegalArgumentException("Invalid selected binding label");
            }
        }

        public boolean hasSelectedBinding() {
            return selectedBinding.isPresent() || selectedBindingLabel.isPresent();
        }
    }

    record SnapshotUpdate(int revision, List<SlotSnapshot> serverSnapshots, ProviderBindingSnapshotPacket packet) {
        SnapshotUpdate {
            if (revision < 0) {
                throw new IllegalArgumentException("snapshot revision must be non-negative");
            }
            serverSnapshots = checkedSnapshots(serverSnapshots);
            packet = java.util.Objects.requireNonNull(packet, "packet");
        }
    }

    /** Immutable client/server presentation state, shared by both snapshot paths. */
    record SnapshotState(List<SlotSnapshot> snapshots, int revision, long presentationGeneration) {
        SnapshotState(List<SlotSnapshot> snapshots, int revision) {
            this(snapshots, revision, 0L);
        }

        SnapshotState {
            snapshots = checkedSnapshots(snapshots);
            if (revision < 0) {
                throw new IllegalArgumentException("snapshot revision must be non-negative");
            }
        }

        static Optional<SnapshotState> applyClientPacket(SnapshotState current,
                int openContainerId, ProviderBindingSnapshotPacket packet) {
            current = java.util.Objects.requireNonNull(current, "current");
            packet = java.util.Objects.requireNonNull(packet, "packet");
            if (!packet.targetsContainer(openContainerId)) {
                return Optional.empty();
            }
            return Optional.of(new SnapshotState(packet.snapshots(), nextSnapshotRevision(current.revision()),
                    nextPresentationGeneration(current.presentationGeneration())));
        }

        private static long nextPresentationGeneration(long generation) {
            return generation == Long.MAX_VALUE ? Long.MIN_VALUE : generation + 1L;
        }
    }

    private static final class ProcessingPatternSlot extends Slot {
        private ProcessingPatternSlot(Container inventory, int slot, int x, int y) {
            super(inventory, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return AEItemKey.of(stack) != null && stack.is(AEItems.PROCESSING_PATTERN.asItem());
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }

    private static final class InstallerSlot extends Slot {
        @Nullable
        private final SingularPatternProviderBlockEntity provider;

        private InstallerSlot(Container inventory, int slot, int x, int y,
                @Nullable SingularPatternProviderBlockEntity provider) {
            super(inventory, slot, x, y);
            this.provider = provider;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return provider == null || provider.canInstall(getContainerSlot(), stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
