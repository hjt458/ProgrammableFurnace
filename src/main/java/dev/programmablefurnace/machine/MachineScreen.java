package dev.programmablefurnace.machine;

import dev.programmablefurnace.base.CodeStorage;
import dev.programmablefurnace.base.GuiConfig;
import dev.programmablefurnace.base.GuiTab;
import dev.programmablefurnace.network.BatchMovePacket;
import dev.programmablefurnace.network.UiModePacket;
import dev.programmablefurnace.util.DependencyUi;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 双界面机器 Screen 基类（工厂模式）。编译/执行器共用：
 * <ul>
 *   <li>双模式（工作 / 依赖库）+ 左侧页签切换（只改 {@link #mode}，不重建 Screen，避免闪烁）；</li>
 *   <li>依赖库界面：batch 背景 + ScrollView 列表（复用 {@link DependencyUi}）+ 输入/输出/删除按钮，
 *       输出权限由方块实体的机器配置 {@link MachineBlockEntity#machineConfig()} 决定；</li>
 *   <li>切换发通用 {@link UiModePacket} 同步服务端；</li>
 *   <li>工作界面由子类覆写（纹理/渲染/按钮）。</li>
 * </ul>
 * 子类实现：{@link #getWorkTexture} / {@link #getDependencyTexture} / {@link #renderWorkBackground} /
 * {@link #rebuildWorkButtons} / {@link #getWorkTabLabel}。
 */
public abstract class MachineScreen<M extends MachineMenu<?>> extends AbstractContainerScreen<M> {

    // 依赖库模式布局（两机器一致，GuiConfig.layout().dependency）
    private static final int IN_X = GuiConfig.layout().dependency.inX;
    private static final int OUT_X = GuiConfig.layout().dependency.outX;
    private static final int SLOT_Y = GuiConfig.layout().dependency.slotY;
    private static final int SLOT_SIZE = GuiConfig.layout().dependency.slotSize;
    private static final int LIST_X = GuiConfig.layout().dependency.listX;
    private static final int LIST_Y = GuiConfig.layout().dependency.listY;
    private static final int LIST_W = GuiConfig.layout().dependency.listW;
    private static final int LIST_H = GuiConfig.layout().dependency.listH;
    private static final int ROW_H = GuiConfig.layout().dependency.rowH;

    /** 界面模式。 */
    public enum Mode { WORK, DEPENDENCY }

    private Mode mode = Mode.WORK;

    /** 当前界面模式（工作 / 依赖库），供子类判断当前渲染场景。 */
    public Mode mode() { return mode; }

    protected int scrollOffset = 0;
    protected int selectedOffset = -1;

    // 界面提示（与物品栏标签同 y、GUI 最右端，3 秒后消失）
    private String hint = null;
    private long hintUntil = 0L;

    private final GuiTab guiTab;

    private Button inputButton;   // 依赖库
    private Button outputButton;  // 依赖库
    private Button deleteButton;  // 依赖库

    protected MachineScreen(M menu, Inventory playerInventory, Component title) {
        this(menu, playerInventory, title, 176, 166);
    }

    /** 子类可指定窗口宽高（如执行器按 guiMode 的面板宽度：111=176/212=178/313=218/414=258）。 */
    protected MachineScreen(M menu, Inventory playerInventory, Component title, int gw, int gh) {
        super(menu, playerInventory, title, gw, gh);
        this.guiTab = new GuiTab(GuiConfig.layout().tab.x, GuiConfig.layout().tab.y)
                .setTabSize(GuiConfig.layout().tab.w, GuiConfig.layout().tab.h);
        this.titleLabelX = (this.imageWidth - this.font.width(this.title)) / 2;
    }

    @Override
    protected void init() {
        super.init();
        this.guiTab
                .clearTabs()
                .addTab(getWorkTabLabel(), () -> switchMode(Mode.WORK))
                .addTab(Component.translatable("gui.programmablefurnace.tab.dependency"), () -> switchMode(Mode.DEPENDENCY))
                .setActive(mode == Mode.DEPENDENCY ? 1 : 0)
                .initButtons(this, this.leftPos, this.topPos);
        rebuildButtons();
    }

    /** 切换模式：更新激活页签、重建按钮，并同步服务端（迁移行为由方块实体 applySwitchMode 决定）。 */
    protected void switchMode(Mode newMode) {
        if (newMode == this.mode) return;
        int newOrd = newMode.ordinal();
        this.mode = newMode;
        var be = this.menu.getBlockEntity();
        be.applySwitchMode(newOrd);
        this.selectedOffset = -1;
        this.scrollOffset = 0;
        this.guiTab.setActive(newMode == Mode.DEPENDENCY ? 1 : 0);
        rebuildButtons();
        Minecraft.getInstance().getConnection().send(new UiModePacket(be.getBlockPos(), newOrd));
    }

    private void rebuildButtons() {
        this.clearWidgets();
        if (mode == Mode.DEPENDENCY) {
            // 输入按钮：最右端对齐输入槽最右端（NoFocusButton：点击后不抢占焦点，避免白框）
            this.inputButton = new NoFocusButton(
                    this.leftPos + (IN_X + SLOT_SIZE - 24), this.topPos + SLOT_Y + SLOT_SIZE + 4, 24, 12,
                    Component.translatable("gui.programmablefurnace.btn.input"),
                    b -> {
                        BatchMovePacket p = new BatchMovePacket(
                                this.menu.getBlockEntity().getBlockPos(), BatchMovePacket.Action.IN_TO_BATCH, 0);
                        Minecraft.getInstance().getConnection().send(p);
                        resetButtonFocus(b); // 触发一次后复位焦点
                    });
            // 输出按钮：输出权限由机器配置（MachineConfigs）决定（NoFocusButton：点击后不抢占焦点）
            this.outputButton = new NoFocusButton(
                    this.leftPos + OUT_X, this.topPos + SLOT_Y + SLOT_SIZE + 4, 24, 12,
                    Component.translatable("gui.programmablefurnace.btn.output"),
                    b -> {
                        if (this.selectedOffset >= 0) {
                            int offset = this.selectedOffset;
                            ItemStack entry = this.menu.getBlockEntity().getBatchItem(offset);
                            if (!entry.isEmpty()
                                    && this.menu.getBlockEntity().machineConfig().canOutput(CodeStorage.getFileType(entry))) {
                                if (!this.menu.getBlockEntity().getItem(this.menu.getBlockEntity().depOutputIndex()).isEmpty()) {
                                    showHint("gui.programmablefurnace.hint.cannot_output");
                                    return;
                                }
                                this.menu.getBlockEntity().applyMoveBatchToOutput(offset);
                                this.selectedOffset = -1;
                                updateButtonStates();
                                BatchMovePacket p = new BatchMovePacket(
                                        this.menu.getBlockEntity().getBlockPos(),
                                        BatchMovePacket.Action.BATCH_TO_OUTPUT, offset);
                                Minecraft.getInstance().getConnection().send(p);
                                resetButtonFocus(b); // 触发一次后复位焦点
                            }
                        }
                    });
            // 删除按钮：输出槽上方，与输出按钮关于输出槽垂直对称（NoFocusButton：点击后不抢占焦点）
            this.deleteButton = new NoFocusButton(
                    this.leftPos + OUT_X, this.topPos + 14, 24, 12,
                    Component.translatable("gui.programmablefurnace.btn.delete"),
                    b -> {
                        if (this.selectedOffset >= 0) {
                            int offset = this.selectedOffset;
                            this.menu.getBlockEntity().applyRemoveBatchItem(offset);
                            this.selectedOffset = -1;
                            updateButtonStates();
                            BatchMovePacket p = new BatchMovePacket(
                                    this.menu.getBlockEntity().getBlockPos(),
                                    BatchMovePacket.Action.BATCH_REMOVE, offset);
                            Minecraft.getInstance().getConnection().send(p);
                            resetButtonFocus(b); // 触发一次后复位焦点
                        }
                    });
            this.addRenderableWidget(this.inputButton);
            this.addRenderableWidget(this.outputButton);
            this.addRenderableWidget(this.deleteButton);
            updateButtonStates();
        } else {
            rebuildWorkButtons();
        }
    }

    /** 刷新依赖库按钮可用性：输出权限由机器配置决定，未选中时输出/删除禁用。 */
    private void updateButtonStates() {
        boolean outputOk = false;
        boolean deleteOk = false;
        if (this.selectedOffset >= 0) {
            ItemStack entry = this.menu.getBlockEntity().getBatchItem(this.selectedOffset);
            if (!entry.isEmpty()) {
                deleteOk = true;
                outputOk = this.menu.getBlockEntity().machineConfig().canOutput(CodeStorage.getFileType(entry));
            }
        }
        this.outputButton.active = outputOk;
        this.deleteButton.active = deleteOk;
    }

    /**
     * 按钮触发一次后复位焦点（避免白框）。
     * 已通过 {@link NoFocusButton}（shouldTakeFocusAfterInteraction=false）阻止点击后 Screen 聚焦按钮，
     * 此处再兜底清按钮自身字段与键盘导航焦点。
     */
    private void resetButtonFocus(Button button) {
        button.setFocused(false);
        this.clearFocus();
    }

    /**
     * 不抢占焦点的按钮：{@link #shouldTakeFocusAfterInteraction()} 返回 false，点击后 Screen 不再 setFocused(this)。
     * 高亮 sprite 保留默认逻辑（由 isHoveredOrFocused 决定）：鼠标悬停显示白框、移开恢复；
     * 因点击后不再聚焦，鼠标移开后不会因焦点残留而持续白框。
     */
    private static final class NoFocusButton extends Button {
        private NoFocusButton(int x, int y, int w, int h, Component message, OnPress onPress) {
            super(x, y, w, h, message, onPress, Button.DEFAULT_NARRATION);
        }

        @Override
        public boolean shouldTakeFocusAfterInteraction() {
            return false;
        }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            this.extractDefaultSprite(graphics);
            Font font = Minecraft.getInstance().font;
            graphics.text(font, this.getMessage(),
                    this.getX() + (this.getWidth() - font.width(this.getMessage())) / 2,
                    this.getY() + (this.getHeight() - 8) / 2,
                    this.isActive() ? 0xFFFFFFFF : 0xFFA0A0A0);
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        int x = this.leftPos;
        int y = this.topPos;
        Identifier tex = (mode == Mode.DEPENDENCY) ? getDependencyTexture() : getWorkTexture();
        graphics.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0.0F, 0.0F,
                this.imageWidth, this.imageHeight, 256, 256);
        if (mode == Mode.WORK) {
            renderWorkBackground(graphics);
        }
    }

    @Override
    public void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractContents(graphics, mouseX, mouseY, delta);
        // 列表/页签/提示是 GUI 前景，须在槽位内容之后、拾取物品(extractCarriedItem)之前渲染，
        // 否则会盖住跟随鼠标的 carried 物品。
        if (mode == Mode.DEPENDENCY) {
            renderList(graphics);
        }
        this.guiTab.render(graphics, this.font, this.leftPos, this.topPos);
        renderHint(graphics);
    }

    /** 依赖库列表：样式通用（复用 DependencyUi）。 */
    private void renderList(GuiGraphicsExtractor graphics) {
        DependencyUi.renderList(graphics, this.font, this.leftPos, this.topPos,
                this.menu.getBlockEntity(), this.scrollOffset, this.selectedOffset);
    }

    private int countItems() {
        int count = 0;
        int capacity = this.menu.getBlockEntity().batchCapacity();
        for (int i = 0; i < capacity; i++) {
            if (!this.menu.getBlockEntity().getBatchItem(i).isEmpty()) count++;
        }
        return count;
    }

    /** 界面提示：与"物品栏"标签同 y、右对齐到 GUI 最右端，3 秒后自动消失。 */
    private void renderHint(GuiGraphicsExtractor graphics) {
        if (this.hint == null) return;
        if (System.currentTimeMillis() >= this.hintUntil) {
            this.hint = null;
            return;
        }
        int y = this.topPos + this.inventoryLabelY;
        int x = this.leftPos + this.imageWidth - 2 - this.font.width(Component.translatable(this.hint));
        graphics.text(this.font, Component.translatable(this.hint), x, y, 0xFFFF4444);
    }

    protected void showHint(String key) {
        this.hint = key;
        this.hintUntil = System.currentTimeMillis() + 3000L;
    }

    // ===================== 输入事件 =====================

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mode == Mode.DEPENDENCY) {
            int mx = (int) mouseX, my = (int) mouseY;
            int x0 = this.leftPos + LIST_X, y0 = this.topPos + LIST_Y;
            if (mx >= x0 && mx <= x0 + LIST_W && my >= y0 && my <= y0 + LIST_H) {
                int visibleRows = LIST_H / ROW_H;
                int itemCount = countItems();
                int maxOffset = Math.max(0, itemCount - visibleRows);
                this.scrollOffset = Math.max(0, Math.min(maxOffset, this.scrollOffset - (int) Math.signum(verticalAmount)));
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean insideWindow) {
        this.guiTab.handleClick(this, event);
        if (mode == Mode.DEPENDENCY) {
            double mx = event.x(), my = event.y();
            int x0 = this.leftPos + LIST_X, y0 = this.topPos + LIST_Y;
            if (mx >= x0 && mx <= x0 + LIST_W && my >= y0 && my <= y0 + LIST_H) {
                int visibleRows = LIST_H / ROW_H;
                int r = (int) ((my - y0) / ROW_H);
                if (r >= 0 && r < visibleRows) {
                    int offset = this.scrollOffset + r;
                    int capacity = this.menu.getBlockEntity().batchCapacity();
                    if (offset < capacity && !this.menu.getBlockEntity().getBatchItem(offset).isEmpty()) {
                        this.selectedOffset = (this.selectedOffset == offset) ? -1 : offset;
                        updateButtonStates();
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(event, insideWindow);
    }

    // ===================== 子类钩子 =====================

    /** 工作界面背景纹理。 */
    protected abstract Identifier getWorkTexture();

    /** 依赖库界面背景纹理（两机器通常共用 batch.png）。 */
    protected abstract Identifier getDependencyTexture();

    /** 工作界面额外渲染（如熔炉火焰/进度；编译器可为空）。 */
    protected abstract void renderWorkBackground(GuiGraphicsExtractor graphics);

    /** 工作界面按钮（如编译器"开始编译"；执行器可无）。 */
    protected abstract void rebuildWorkButtons();

    /** 第一页签文字（如"编译"/"工作"）。 */
    protected abstract Component getWorkTabLabel();
}
