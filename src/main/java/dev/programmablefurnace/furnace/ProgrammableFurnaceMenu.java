package dev.programmablefurnace.furnace;

import dev.programmablefurnace.base.GuiConfig;
import dev.programmablefurnace.machine.MachineMenu;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * 可编程熔炉菜单：工作界面槽（容器索引，坐标来自 gui_config.json 的 modes）=
 * 输入区 [0..3]、燃料 [4]、输出区 [5..8]、程序槽 [9]、拓展槽 [12..15]；依赖库界面槽（[10][11] + 依赖库容器）由
 * {@link MachineMenu} 基类注册。槽索引的唯一真源是 {@link FurnaceLayout}，这里不写字面量。
 * 经 {@link ContainerData}（progress/burnTime/running/guiMode，索引见 {@link FurnaceDataIndex}）每 tick 同步 GUI。
 */
public class ProgrammableFurnaceMenu extends MachineMenu<ProgrammableFurnaceBlockEntity> {

    private final ContainerData data;
    /** 打开时快照的 GUI 模式：U盘卡放入功能槽后当前 GUI 不立即切换，重开才生效（重开时重新快照）。 */
    private final int openGuiMode;

    /** 冻结 {@link FurnaceDataIndex#GUI_MODE} 为打开时快照的容器数据源：progress/burn/running/lit 仍实时委派 BE。 */
    private static final class FrozenGuiModeData implements ContainerData {
        private final ContainerData inner;
        private final int openGuiMode;
        FrozenGuiModeData(ContainerData inner, int openGuiMode) {
            this.inner = inner;
            this.openGuiMode = openGuiMode;
        }
        @Override public int get(int i) { return i == FurnaceDataIndex.GUI_MODE ? openGuiMode : inner.get(i); }
        @Override public void set(int i, int v) { inner.set(i, v); }
        @Override public int getCount() { return inner.getCount(); }
    }

    /** 客户端构造：从网络 buffer 读方块位置，重建方块实体引用。 */
    public ProgrammableFurnaceMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, playerInventory,
                (ProgrammableFurnaceBlockEntity) playerInventory.player.level().getBlockEntity(extraData.readBlockPos()));
    }

    /** 服务端构造：绑定真实方块实体。 */
    public ProgrammableFurnaceMenu(int containerId, Inventory playerInventory, ProgrammableFurnaceBlockEntity blockEntity) {
        super(FurnaceRegistration.MENU.get(), containerId, playerInventory, blockEntity);
        // 打开时快照 guiMode：U盘卡放入功能槽后当前 GUI 不立即切换，重开才生效（重开时重新快照）。
        // 服务端用冻结包装数据源（FurnaceDataIndex.GUI_MODE 固定为 openGuiMode），客户端经 SimpleContainerData 同步同一值。
        boolean server = blockEntity.getLevel() != null && !blockEntity.getLevel().isClientSide();
        this.openGuiMode = blockEntity.getGuiMode();
        this.data = server
                ? new FrozenGuiModeData(blockEntity, this.openGuiMode)
                : new SimpleContainerData(FurnaceDataIndex.COUNT);
        this.addDataSlots(this.data);
    }

    /** GUI 读取已同步的容器数据：0=progress, 1=burnTime, 2=running, 3=litDuration。 */
    public int getDataValue(int index) {
        return this.data.get(index);
    }

    /** Shift+点击：工作界面时燃料→燃料槽(1)、其余→输入槽(0)；依赖库界面→依赖库输入。 */
    /** 全部拓展物品的公共 item tag：新拓展物品只要加入该 tag 即可放入拓展槽（mayPlace 查 tag，无需改代码）。 */
    public static final TagKey<Item> FURNACE_EXPANSION_TAG =
            TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("programmablefurnace", "furnace_expansion"));

    /** 是否为拓展物品（熔炉扩容器/燃烧优化器/未来新增，仅拓展槽可放，按公共 tag 判定）。 */
    private static boolean isExpansionItem(ItemStack stack) {
        return stack.is(FURNACE_EXPANSION_TAG);
    }

    @Override
    protected int getQuickMoveTarget(ItemStack stack) {
        // 返回 menu.slots 列表索引（quickMoveStack 的 moveItemStackTo 用列表索引，容器索引≠列表索引）
        if (blockEntity.uiMode() == 1) return slotListIndexOf(blockEntity.getDepInputIndex());
        // 工作页不再有"放程序"的格子：程序文件在依赖库页导入（2026-11-17 移除了程序槽），
        // 所以这里不再给可执行文件单独分流，让它按普通物品走下面的燃料/输入/输出判断。
        // 拓展物品（熔炉扩容器/燃烧优化器，mayPlace 仅拓展槽可放）→ 直接进拓展槽（EXTRA 区第一个空槽，满则第一个拓展槽）
        if (isExpansionItem(stack)) {
            for (int i = 0; i < ProgrammableFurnaceBlockEntity.EXTRA_COUNT; i++) {
                int idx = ProgrammableFurnaceBlockEntity.EXTRA_START + i;
                if (blockEntity.getItem(idx).isEmpty()) return slotListIndexOf(idx);
            }
            return slotListIndexOf(ProgrammableFurnaceBlockEntity.EXTRA_START);
        }
        boolean fuel = isFuel(stack);
        int targetIn;
        if (fuel) {
            targetIn = slotListIndexOf(ProgrammableFurnaceBlockEntity.SLOT_FUEL);
        } else {
            // 输入槽区按当前 GUI 显示模式（打开时快照 openGuiMode）的槽数，找第一个空槽放（多输入槽支持）
            GuiConfig.GuiMode m = GuiConfig.mode(getDataValue(FurnaceDataIndex.GUI_MODE));
            int inCount = m != null ? Math.min(m.inputCount, ProgrammableFurnaceBlockEntity.INPUT_COUNT) : 1;
            targetIn = -1;
            for (int i = 0; i < inCount; i++) {
                int idx = ProgrammableFurnaceBlockEntity.SLOT_INPUT + i;
                if (blockEntity.getItem(idx).isEmpty()) { targetIn = slotListIndexOf(idx); break; }
            }
            if (targetIn < 0) targetIn = slotListIndexOf(ProgrammableFurnaceBlockEntity.SLOT_INPUT);
        }
        return targetIn;
    }

    /** 容器槽索引 → menu.slots 列表索引（quickMove 的 moveItemStackTo 按列表索引遍历）。 */
    // slotListIndexOf 由基类 MachineMenu 提供（protected），子类直接复用。

    /** 用 CookingFuel 组件判断是否燃料（比 hasTag(fuels) 底层可靠）。 */
    private static boolean isFuel(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.get(DataComponents.COOKING_FUEL) != null) return true;
        // 26.3 兜底：部分常见燃料（煤炭/木炭）可能不在 COOKING_FUEL 组件，按物品 id 匹配
        return stack.is(Items.COAL) || stack.is(Items.CHARCOAL);
    }

    @Override
    protected void addWorkSlots() {
        // 固定注册全部工作槽（input 4 + fuel 1 + output 4 + program 1）：槽数恒定，setGui 切换 guiMode 后
        // 服务端/客户端 menu 槽数一致（不再因模式不同槽数越界崩溃）；isActive 按当前 guiMode 过滤显示，
        // 坐标由 containerTick 每 tick 按 guiMode 动态更新（匹配对应背景图）。
        GuiConfig.GuiMode m = GuiConfig.mode(blockEntity.getGuiMode());
        if (m == null) m = GuiConfig.mode(111);
        // 输入槽区：固定 4 个（容器 0..3），isActive 于当前 guiMode.inputCount
        for (int i = 0; i < ProgrammableFurnaceBlockEntity.INPUT_COUNT; i++) {
            final int idx = ProgrammableFurnaceBlockEntity.SLOT_INPUT + i;
            final int inI = i;
            addSlot(new Slot(blockEntity, idx, i < m.inputX.length ? m.inputX[i] : 0,
                    i < m.inputY.length ? m.inputY[i] : 0) {
                @Override
                public boolean isActive() {
                    GuiConfig.GuiMode gm = GuiConfig.mode(ProgrammableFurnaceMenu.this.getDataValue(FurnaceDataIndex.GUI_MODE));
                    return blockEntity.uiMode() == 0 && gm != null && inI < gm.inputCount;
                }
            });
        }
        // 燃料槽：固定 1 个（容器4）
        addSlot(new Slot(blockEntity, ProgrammableFurnaceBlockEntity.SLOT_FUEL,
                m.fuelX.length > 0 ? m.fuelX[0] : 0, m.fuelY.length > 0 ? m.fuelY[0] : 0) {
            @Override
            public boolean isActive() { return blockEntity.uiMode() == 0; }
        });
        // 输出槽区：固定 4 个（容器 5..8），isActive 于当前 guiMode.outputCount
        for (int i = 0; i < ProgrammableFurnaceBlockEntity.OUTPUT_COUNT; i++) {
            final int idx = ProgrammableFurnaceBlockEntity.SLOT_OUTPUT + i;
            final int outI = i;
            addSlot(new Slot(blockEntity, idx, i < m.outputX.length ? m.outputX[i] : 0,
                    i < m.outputY.length ? m.outputY[i] : 0) {
                @Override
                public boolean isActive() {
                    GuiConfig.GuiMode gm = GuiConfig.mode(ProgrammableFurnaceMenu.this.getDataValue(FurnaceDataIndex.GUI_MODE));
                    return blockEntity.uiMode() == 0 && gm != null && outI < gm.outputCount;
                }
            });
        }
        // 程序槽在 2026-11-17 移除：程序文件只从依赖库页的输入槽导入，工作页不再有"放程序"的格子。
        // 拓展槽：放 U盘扩展卡等拓展物品（最右侧一列，isActive 于工作界面）
        GuiConfig.Extra ex = GuiConfig.layout().extra;
        for (int i = 0; i < ProgrammableFurnaceBlockEntity.EXTRA_COUNT; i++) {
            final int idx = ProgrammableFurnaceBlockEntity.EXTRA_START + i;
            addSlot(new Slot(blockEntity, idx, ex.x[i], ex.y[i]) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return blockEntity.canPlaceItem(idx, stack);
                }
                @Override
                public boolean isActive() { return blockEntity.uiMode() == 0; }
            });
        }
    }

    @Override
    protected Block getStillValidBlock() {
        return FurnaceRegistration.BLOCK.get();
    }
}
