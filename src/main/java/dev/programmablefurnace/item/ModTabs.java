package dev.programmablefurnace.item;

import dev.programmablefurnace.base.ModInfo;
import dev.programmablefurnace.machine.MachineRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;

/**
 * 创造模式标签页。放在 {@code item} 包是因为它陈列的全是物品（机器方块物品也在内），
 * 而不是"入口类的私事"。
 */
public final class ModTabs {

    private ModTabs() {}

    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ModInfo.MODID);

    /** 自定义创造标签页：图标用可编程熔炉方块，只放本模组的物品与机器。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB = CREATIVE_MODE_TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.programmablefurnace"))
                    .withTabsBefore(CreativeModeTabs.COMBAT)
                    .icon(() -> MachineRegistry.find("programmable_furnace")
                            .orElseThrow(() -> new IllegalStateException("缺少 programmable_furnace 机器注册"))
                            .item().get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        // 所有已注册机器（按创造标签顺序）
                        MachineRegistry.machinesByCreativeOrder().forEach(h -> output.accept(h.item().get()));
                        output.accept(ModItems.CODE_FILE_BLANK.get());   // 空白文件
                        output.accept(ModItems.CODE_FILE_EXEC.get());    // 可执行文件（编译产物）
                        output.accept(ModItems.CODE_FILE_FAILED.get());  // 编译失败文件
                        output.accept(ModItems.exampleProgramStack());   // 示例程序（内置烧炼脚本）
                        output.accept(ModItems.USB_CARD_I.get());        // U 盘扩展卡·I（212）
                        output.accept(ModItems.USB_CARD_II.get());       // U 盘扩展卡·II（313）
                        output.accept(ModItems.USB_CARD_III.get());      // U 盘扩展卡·III（414）
                        output.accept(ModItems.USB_CARD_BOOST_I.get());  // 燃烧优化卡·I
                        output.accept(ModItems.USB_CARD_BOOST_II.get()); // 燃烧优化卡·II
                        output.accept(ModItems.USB_CARD_BOOST_III.get());// 燃烧优化卡·III
                    })
                    .build());

    public static void register(IEventBus modEventBus) {
        CREATIVE_MODE_TABS.register(modEventBus);
    }
}
