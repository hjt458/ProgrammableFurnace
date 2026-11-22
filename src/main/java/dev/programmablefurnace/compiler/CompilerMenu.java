package dev.programmablefurnace.compiler;

import dev.programmablefurnace.base.GuiConfig;
import dev.programmablefurnace.machine.MachineMenu;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * 编译器菜单（编译界面与依赖库界面共用同一 menu，靠页签切换 Screen）：
 * 工作界面槽 = [0]编译输入(49,30) + [1]编译输出(109,30)，仅 uiMode==0 生效；
 * 依赖库界面槽 + 容器槽由 {@link MachineMenu} 基类注册。
 */
public class CompilerMenu extends MachineMenu<CompilerBlockEntity> {

    /** 客户端构造：从网络 buffer 读方块位置，重建方块实体引用。 */
    public CompilerMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf extraData) {
        this(containerId, playerInventory,
                (CompilerBlockEntity) playerInventory.player.level().getBlockEntity(extraData.readBlockPos()));
    }

    /** 服务端构造：绑定真实方块实体。 */
    public CompilerMenu(int containerId, Inventory playerInventory, CompilerBlockEntity blockEntity) {
        super(CompilerRegistration.MENU.get(), containerId, playerInventory, blockEntity);
    }

    @Override
    protected void addWorkSlots() {
        // 编译输入槽：编码文件，仅编译界面生效
        addSlot(new Slot(blockEntity, CompilerBlockEntity.SLOT_INPUT,
                GuiConfig.layout().slots.compileInX, GuiConfig.layout().slots.compileInY) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return blockEntity.canPlaceItem(CompilerBlockEntity.SLOT_INPUT, stack);
            }
            @Override
            public boolean isActive() {
                return blockEntity.uiMode() == 0;
            }
        });
        // 编译输出槽：编译产物，不可放入，仅编译界面生效
        addSlot(new Slot(blockEntity, CompilerBlockEntity.SLOT_OUTPUT,
                GuiConfig.layout().slots.compileOutX, GuiConfig.layout().slots.compileOutY) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return blockEntity.canPlaceItem(CompilerBlockEntity.SLOT_OUTPUT, stack);
            }
            @Override
            public boolean isActive() {
                return blockEntity.uiMode() == 0;
            }
        });
    }

    @Override
    protected Block getStillValidBlock() {
        return CompilerRegistration.BLOCK.get();
    }
}
