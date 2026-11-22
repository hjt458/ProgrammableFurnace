package dev.programmablefurnace.machine;

import dev.programmablefurnace.base.GuiConfig;
import dev.programmablefurnace.item.CodeFileItem;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * 双界面机器菜单基类（工厂模式）。编译/执行器共用：
 * <ul>
 *   <li>工作界面槽由子类 {@link #addWorkSlots} 注册（isActive 于 uiMode==0）；</li>
 *   <li>依赖库界面 in/out 槽 + 容器槽由基类注册（isActive 于 uiMode==1，坐标来自通用布局 GuiConfig）；</li>
 *   <li>玩家物品栏/快捷栏、{@link #quickMoveStack}（Shift 移入当前生效输入槽）、客户端重建方块实体。</li>
 * </ul>
 * 每次打开 GUI 默认第一界面（uiMode=0）。
 */
public abstract class MachineMenu<B extends MachineBlockEntity> extends AbstractContainerMenu {

    protected final B blockEntity;
    protected final ContainerLevelAccess access;

    /** 服务端构造：绑定真实方块实体。 */
    protected MachineMenu(MenuType<?> type, int containerId, Inventory playerInventory, B blockEntity) {
        super(type, containerId);
        this.blockEntity = blockEntity;
        // 每次打开 GUI 默认第一界面：重置 uiMode（客户端/服务端实例各自重置，保证 Slot.isActive 与 quickMove 一致）
        blockEntity.setUiMode(0);
        this.access = blockEntity.getLevel() != null
                ? ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos())
                : ContainerLevelAccess.NULL;

        // 工作界面槽（isActive 于 uiMode==0）
        addWorkSlots();

        // 依赖库界面输入槽：编码文件（仅依赖库界面生效）
        addSlot(new Slot(blockEntity, blockEntity.getDepInputIndex(),
                GuiConfig.layout().slots.depInX, GuiConfig.layout().slots.depInY) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof CodeFileItem;
            }
            @Override
            public boolean isActive() {
                return blockEntity.uiMode() == 1;
            }
        });
        // 依赖库界面输出槽：作业产物，不可直接放入（仅依赖库界面生效）
        addSlot(new Slot(blockEntity, blockEntity.getDepOutputIndex(),
                GuiConfig.layout().slots.depOutX, GuiConfig.layout().slots.depOutY) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }
            @Override
            public boolean isActive() {
                return blockEntity.uiMode() == 1;
            }
        });
        // 依赖库容器槽：置于 GUI 框外，不可交互，仅供依赖库界面 ScrollView 读取
        for (int b = 0; b < blockEntity.batchCapacity(); b++) {
            addSlot(new Slot(blockEntity, blockEntity.getBatchStart() + b,
                    GuiConfig.layout().slots.hiddenX, GuiConfig.layout().slots.hiddenY) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }
            });
        }

        addStandardInventorySlots(playerInventory, 8, 84);
    }

    /** 子类注册工作界面槽（坐标/放行规则各异，isActive 于 uiMode==0）。 */
    protected abstract void addWorkSlots();

    /** 子类提供所属方块（stillValid 校验）。 */
    protected abstract Block getStillValidBlock();

    @Override
    public boolean stillValid(Player player) {
        return AbstractContainerMenu.stillValid(this.access, player, getStillValidBlock());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack stack = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stackInSlot = slot.getItem();
            stack = stackInSlot.copy();
            // batchEnd = 玩家背包第一个槽的 slots 列表索引（机器槽数）；容器索引≠列表索引，不能用 getBatchStart()+capacity
            int batchEnd = this.slots.size() - player.getInventory().getContainerSize();
            int playerEnd = this.slots.size();
            if (index < batchEnd) {
                // 任意机器槽（工作/依赖库 in·out/容器）→ 玩家背包 + 快捷栏
                if (!this.moveItemStackTo(stackInSlot, batchEnd, playerEnd, true)) {
                    return ItemStack.EMPTY;
                }
            } else {
                // 玩家背包 → 当前生效的输入槽（或子类按物品类型分流，如熔炉燃料→燃料槽）
                int targetIn = getQuickMoveTarget(stackInSlot);
                boolean moved = targetIn >= 0 && this.moveItemStackTo(stackInSlot, targetIn, targetIn + 1, false);
                if (!moved) return ItemStack.EMPTY;
            }
            if (stackInSlot.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
            if (stackInSlot.getCount() == stack.getCount()) {
                return ItemStack.EMPTY;
            }
            slot.onTake(player, stackInSlot);
        }
        return stack;
    }

    /** quickMove 从玩家背包移入时的目标槽索引；默认当前生效的输入槽，子类可覆写按物品类型分流（如熔炉燃料→燃料槽）。 */
    protected int getQuickMoveTarget(ItemStack stack) {
        // 返回 menu.slots 列表索引（quickMoveStack 的 moveItemStackTo 用列表索引，容器索引≠列表索引）
        return blockEntity.uiMode() == 1
                ? slotListIndexOf(blockEntity.getDepInputIndex())
                : slotListIndexOf(blockEntity.getWorkInputIndex());
    }

    /** 容器槽索引 → menu.slots 列表索引（quickMove 的 moveItemStackTo 按列表索引遍历；多槽/重排布局时容器索引≠列表索引）。
     *  MC 26.3 的 Slot.index 是 slots 列表位置，getContainerSlot() 才是容器槽位，匹配必须用 getContainerSlot()。 */
    protected int slotListIndexOf(int containerIndex) {
        for (int i = 0; i < this.slots.size(); i++) {
            Slot s = this.slots.get(i);
            if (s.container == blockEntity && s.getContainerSlot() == containerIndex) return i;
        }
        return -1;
    }

    public B getBlockEntity() { return blockEntity; }
}
