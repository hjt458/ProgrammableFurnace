package dev.programmablefurnace.furnace;

import dev.programmablefurnace.machine.MachineRegistry;
import dev.programmablefurnace.machine.ModRegisters;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

import java.util.function.Supplier;

/**
 * 可编程熔炉的注册：方块、方块物品、方块实体类型、菜单类型。
 *
 * <p>与 {@code CompilerRegistration} 对称：注册表从 {@link ModRegisters} 取，
 * 规格与句柄留在自己包内。</p>
 */
public final class FurnaceRegistration {

    private FurnaceRegistration() {}

    public static final MachineRegistry.MachineSpec<ProgrammableFurnaceBlock, ProgrammableFurnaceBlockEntity, ProgrammableFurnaceMenu> SPEC =
            new MachineRegistry.MachineSpec<ProgrammableFurnaceBlock, ProgrammableFurnaceBlockEntity, ProgrammableFurnaceMenu>(
                    "programmable_furnace",
                    BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(3.5f).requiresCorrectToolForDrops()
                            .lightLevel(state -> state.getValue(ProgrammableFurnaceBlock.LIT) ? 13 : 0),
                    ProgrammableFurnaceBlock::new,
                    ProgrammableFurnaceBlockEntity::new,
                    () -> IMenuTypeExtension.create(ProgrammableFurnaceMenu::new),
                    2);

    public static final MachineRegistry.MachineHandle<ProgrammableFurnaceBlock, ProgrammableFurnaceBlockEntity, ProgrammableFurnaceMenu> HANDLE =
            MachineRegistry.register(SPEC, ModRegisters.BLOCKS, ModRegisters.ITEMS,
                    ModRegisters.BLOCK_ENTITIES, ModRegisters.MENUS);

    public static final DeferredBlock<ProgrammableFurnaceBlock> BLOCK = HANDLE.block();
    public static final DeferredItem<net.minecraft.world.item.BlockItem> ITEM = HANDLE.item();
    public static final Supplier<BlockEntityType<ProgrammableFurnaceBlockEntity>> BLOCK_ENTITY = HANDLE.blockEntity();
    public static final Supplier<MenuType<ProgrammableFurnaceMenu>> MENU = HANDLE.menu();

    /** 供入口类调用，确保本类的静态初始化（注册）在挂总线之前发生。 */
    public static void init() {
        // 静态字段已在类初始化时完成注册，这里只是显式触发。
    }
}
