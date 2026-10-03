package dev.appliedcausality.client;

import dev.appliedcausality.AppliedCausality;
import dev.appliedcausality.internal.binding.ProcessBindingTarget;
import dev.appliedcausality.menu.ProviderBindingMenu;
import appeng.client.gui.Icon;
import appeng.client.gui.style.BackgroundGenerator;
import appeng.client.gui.widgets.IconButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

import java.util.List;
import java.util.Locale;

/** Client presentation only; every selection is revalidated by the server menu. */
public final class ProviderBindingScreen extends AbstractContainerScreen<ProviderBindingMenu> {
    private static final int VISIBLE_CANDIDATE_BUTTONS = 3;
    private static final int TARGET_TEXT_WIDTH = 234;
    private static final int BOUND_TARGET_TEXT_WIDTH = 184;
    private static final int LABEL_COLOR = 0x404040;

    private final Button[] candidateButtons = new Button[VISIBLE_CANDIDATE_BUTTONS];
    private Button clearButton;
    private int selectedSlot;
    private int candidatePage;
    private long seenSnapshotGeneration = Long.MIN_VALUE;

    public ProviderBindingScreen(ProviderBindingMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 256;
        // Stay below Minecraft's 240px logical GUI minimum and leave a bottom border margin.
        imageHeight = 236;
        inventoryLabelX = ProviderBindingMenu.PLAYER_INVENTORY_X;
        inventoryLabelY = 149;
    }

    @Override
    protected void init() {
        super.init();
        for (int candidate = 0; candidate < candidateButtons.length; candidate++) {
            int selected = candidate;
            candidateButtons[candidate] = addRenderableWidget(Button.builder(Component.empty(), ignored ->
                    sendButton(selectedSlot * ProviderBindingMenu.MAX_CANDIDATES_PER_SLOT
                            + candidatePage * VISIBLE_CANDIDATE_BUTTONS + selected))
                    .bounds(leftPos + 8, topPos + 58 + candidate * 16, 212, 15).build());
        }
        AeIconButton previousPageButton = addRenderableWidget(new AeIconButton(
                Icon.ARROW_LEFT, Component.translatable("gui." + AppliedCausality.MOD_ID + ".previous_page"), ignored -> {
            if (candidatePage > 0) {
                candidatePage--;
                refreshSelection();
            }
        }));
        previousPageButton.setPosition(leftPos + 8, topPos + 129);
        AeIconButton nextPageButton = addRenderableWidget(new AeIconButton(
                Icon.ARROW_RIGHT, Component.translatable("gui." + AppliedCausality.MOD_ID + ".next_page"), ignored -> {
            int maxPage = maxCandidatePage();
            if (candidatePage < maxPage) {
                candidatePage++;
                refreshSelection();
            }
        }));
        nextPageButton.setPosition(leftPos + 232, topPos + 129);
        clearButton = addRenderableWidget(Button.builder(Component.translatable("gui." + AppliedCausality.MOD_ID + ".clear_binding"),
                ignored -> sendButton(ProviderBindingMenu.CLEAR_BUTTON_BASE + selectedSlot))
                .bounds(leftPos + 32, topPos + 128, 192, 18).build());
        refreshSelection();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        refreshIfSnapshotChanged();
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (hoveredSlot == null) {
            renderSelectedBindingTooltip(graphics, mouseX, mouseY);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        BackgroundGenerator.draw(imageWidth, imageHeight, graphics, leftPos, topPos);
        graphics.fill(leftPos + 4, topPos + 48, leftPos + 252, topPos + 147, 0x28000000);
        drawSlotBackgrounds(graphics);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, LABEL_COLOR, false);
        if (menu.isCreative()) {
            graphics.drawString(font, Component.translatable("gui." + AppliedCausality.MOD_ID
                    + ".creative_unlock_bypass"), 8, 14, 0x7A3AA0, false);
        }
        graphics.drawString(font, Component.translatable("gui." + AppliedCausality.MOD_ID + ".pattern_slots"),
                8, 24, LABEL_COLOR, false);
        graphics.drawString(font, Component.translatable("gui." + AppliedCausality.MOD_ID + ".binding_candidates"),
                8, 48, LABEL_COLOR, false);
        graphics.drawString(font, Component.translatable("gui." + AppliedCausality.MOD_ID + ".status",
                        diagnosticName(menu.diagnostic(selectedSlot))), 8, 107, LABEL_COLOR, false);
        graphics.drawString(font, Component.translatable("gui." + AppliedCausality.MOD_ID + ".bound",
                        selectedBindingName()), 8, 117, LABEL_COLOR, false);
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, LABEL_COLOR, false);
    }

    private void refreshSelection() {
        List<ProcessBindingTarget> targets = menu.bindingTargets(selectedSlot);
        candidatePage = Math.min(candidatePage, maxCandidatePage());
        var selectedBinding = menu.selectedBinding(selectedSlot);
        for (int candidate = 0; candidate < candidateButtons.length; candidate++) {
            Button button = candidateButtons[candidate];
            int targetIndex = candidatePage * VISIBLE_CANDIDATE_BUTTONS + candidate;
            if (targetIndex < targets.size()) {
                ProcessBindingTarget target = targets.get(targetIndex);
                String fullTargetName = target.adapterId() + " / " + target.recipeId();
                button.setMessage(Component.literal((selectedBinding.filter(target::equals).isPresent()
                        ? "* " : "") + abbreviateTarget(target)));
                button.setTooltip(Tooltip.create(Component.literal(fullTargetName)));
                button.visible = true;
                button.active = true;
            } else {
                button.visible = false;
                button.active = false;
                button.setTooltip(null);
            }
        }
        clearButton.active = menu.hasSelectedBinding(selectedSlot);
        seenSnapshotGeneration = menu.snapshotPresentationGeneration();
    }

    private void sendButton(int buttonId) {
        if (Minecraft.getInstance().gameMode != null) {
            Minecraft.getInstance().gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int slot = providerSlotAt(mouseX, mouseY);
        if (consumesPatternSlotClick(slot, button, hasShiftDown())) {
            selectedSlot = slot;
            candidatePage = 0;
            refreshSelection();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Primary-click selects a pattern; shift-click retains the normal transfer path. */
    static boolean consumesPatternSlotClick(int patternSlot, int button, boolean shiftDown) {
        return patternSlot >= 0 && button == 0 && !shiftDown;
    }

    private int maxCandidatePage() {
        return Math.max(0, (menu.bindingTargets(selectedSlot).size() - 1) / VISIBLE_CANDIDATE_BUTTONS);
    }

    private int providerSlotAt(double mouseX, double mouseY) {
        int x = (int) mouseX - leftPos - ProviderBindingMenu.PATTERN_SLOT_X;
        int y = (int) mouseY - topPos - ProviderBindingMenu.PATTERN_SLOT_Y;
        if (x < 0 || y < 0 || x >= 9 * 18 || y >= 18) {
            return -1;
        }
        return x / 18;
    }

    private void refreshIfSnapshotChanged() {
        if (snapshotPresentationChanged(seenSnapshotGeneration, menu.snapshotPresentationGeneration())) {
            candidatePage = 0;
            refreshSelection();
        }
    }

    static boolean snapshotPresentationChanged(long seenGeneration, long currentGeneration) {
        return seenGeneration != currentGeneration;
    }

    private static Component diagnosticName(dev.appliedcausality.internal.validation.ValidationDiagnostic diagnostic) {
        return Component.translatable("gui." + AppliedCausality.MOD_ID + ".diagnostic."
                + diagnostic.name().toLowerCase(Locale.ROOT));
    }

    private String selectedBindingName() {
        return abbreviate(menu.selectedBindingLabel(selectedSlot), BOUND_TARGET_TEXT_WIDTH);
    }

    private String abbreviateTarget(ProcessBindingTarget target) {
        String compact = target.adapterId().getPath() + " / " + target.recipeId().getPath();
        return abbreviate(compact, TARGET_TEXT_WIDTH);
    }

    private String abbreviate(String value, int width) {
        if (font.width(value) <= width) {
            return value;
        }
        String ellipsis = "...";
        return font.plainSubstrByWidth(value, width - font.width(ellipsis)) + ellipsis;
    }

    private void renderSelectedBindingTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (mouseX < leftPos + 8 || mouseX >= leftPos + 248
                || mouseY < topPos + 117 || mouseY >= topPos + 126) {
            return;
        }
        if (menu.hasSelectedBinding(selectedSlot)) {
            graphics.renderTooltip(font, Component.literal(menu.selectedBindingLabel(selectedSlot)), mouseX, mouseY);
        }
    }

    /** Uses AE2's own slot chrome rather than a mod-local imitation texture. */
    private void drawSlotBackgrounds(GuiGraphics graphics) {
        for (int slot = 0; slot < 9; slot++) {
            drawSlotBackground(graphics, ProviderBindingMenu.PATTERN_SLOT_X + slot * 18,
                    ProviderBindingMenu.PATTERN_SLOT_Y);
        }
        for (int slot = 0; slot < 4; slot++) {
            drawSlotBackground(graphics, ProviderBindingMenu.INSTALLER_SLOT_X,
                    ProviderBindingMenu.INSTALLER_SLOT_Y + slot * 18);
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                drawSlotBackground(graphics, ProviderBindingMenu.PLAYER_INVENTORY_X + column * 18,
                        ProviderBindingMenu.PLAYER_INVENTORY_Y + row * 18);
            }
        }
        for (int column = 0; column < 9; column++) {
            drawSlotBackground(graphics, ProviderBindingMenu.PLAYER_INVENTORY_X + column * 18,
                    ProviderBindingMenu.PLAYER_HOTBAR_Y);
        }
    }

    private void drawSlotBackground(GuiGraphics graphics, int x, int y) {
        Icon.SLOT_BACKGROUND.getBlitter().dest(leftPos + x - 1, topPos + y - 1).blit(graphics);
    }

    private static final class AeIconButton extends IconButton {
        private final Icon icon;

        private AeIconButton(Icon icon, Component tooltip, OnPress onPress) {
            super(onPress);
            this.icon = icon;
            setMessage(tooltip);
            setTooltip(Tooltip.create(tooltip));
        }

        @Override
        protected Icon getIcon() {
            return icon;
        }
    }
}
