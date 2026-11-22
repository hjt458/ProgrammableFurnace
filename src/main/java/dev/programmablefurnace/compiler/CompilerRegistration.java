package dev.programmablefurnace.compiler;

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
 * 编译器的注册：方块、方块物品、方块实体类型、菜单类型。
 *
 * <p>注册表从 {@link ModRegisters} 取（向下依赖），规格与句柄留在自己包内——这样
 * {@code machine} 包不必 import 编译器，入口类也不必持有这些句柄。</p>
 */
public final class CompilerRegistration {

    private CompilerRegistration() {}

    public static final MachineRegistry.MachineSpec<CompilerBlock, CompilerBlockEntity, CompilerMenu> SPEC =
            new MachineRegistry.MachineSpec<CompilerBlock, CompilerBlockEntity, CompilerMenu>(
                    "compiler",
                    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5f).requiresCorrectToolForDrops(),
                    CompilerBlock::new,
                    CompilerBlockEntity::new,
                    () -> IMenuTypeExtension.create(CompilerMenu::new),
                    1);

    public static final MachineRegistry.MachineHandle<CompilerBlock, CompilerBlockEntity, CompilerMenu> HANDLE =
            MachineRegistry.register(SPEC, ModRegisters.BLOCKS, ModRegisters.ITEMS,
                    ModRegisters.BLOCK_ENTITIES, ModRegisters.MENUS);

    public static final DeferredBlock<CompilerBlock> BLOCK = HANDLE.block();
    public static final DeferredItem<net.minecraft.world.item.BlockItem> ITEM = HANDLE.item();
    public static final Supplier<BlockEntityType<CompilerBlockEntity>> BLOCK_ENTITY = HANDLE.blockEntity();
    public static final Supplier<MenuType<CompilerMenu>> MENU = HANDLE.menu();

    /** 供入口类调用，确保本类的静态初始化（注册）在挂总线之前发生。 */
    public static void init() {
        // 静态字段已在类初始化时完成注册，这里只是显式触发。
    }
}
