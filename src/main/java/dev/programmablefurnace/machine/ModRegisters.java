package dev.programmablefurnace.machine;

import dev.programmablefurnace.base.ModInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 机器相关的四个注册表本身（方块、方块物品、方块实体类型、菜单类型）。
 *
 * <p>放在 {@code machine} 包而不是入口类：注册表是"基础设施"，各机器的注册类
 * （{@code compiler/CompilerRegistration}、{@code furnace/FurnaceRegistration}）从下往上用它，
 * 入口类只调一次 {@link #register(IEventBus)}。这样 {@code machine} 包不需要认识任何具体机器。</p>
 */
public final class ModRegisters {

    private ModRegisters() {}

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ModInfo.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ModInfo.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ModInfo.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, ModInfo.MODID);

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        MENUS.register(modEventBus);
    }
}
