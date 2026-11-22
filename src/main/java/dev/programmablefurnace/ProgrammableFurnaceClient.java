package dev.programmablefurnace;

import dev.programmablefurnace.furnace.FurnaceRegistration;
import dev.programmablefurnace.compiler.CompilerRegistration;
import dev.programmablefurnace.base.ModLog;
import dev.programmablefurnace.base.ModInfo;
import dev.programmablefurnace.compiler.CompilerMenu;
import dev.programmablefurnace.compiler.CompilerScreen;
import dev.programmablefurnace.furnace.ProgrammableFurnaceMenu;
import dev.programmablefurnace.furnace.ProgrammableFurnaceScreen;
import dev.programmablefurnace.machine.MachineRegistry;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

import java.util.Map;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = ModInfo.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = ModInfo.MODID, value = Dist.CLIENT)
public class ProgrammableFurnaceClient {
    // 本模组没有 ModConfigSpec（数值都在 resources 的 JSON 里由玩家直接改），
    // 所以不注册 IConfigScreenFactory：那会生成一个永远空白的配置页，并连带一堆没人读的 translation key。

    /** 机器 id → 界面工厂。新增机器必须在这里补一条，否则启动期直接报错（而不是留个打不开的方块）。 */
    private static final Map<String, ScreenConstructor> SCREENS = Map.of(
            FurnaceRegistration.SPEC.id(),
            (menu, inv, title) -> new ProgrammableFurnaceScreen((ProgrammableFurnaceMenu) menu, inv, title),
            CompilerRegistration.SPEC.id(),
            (menu, inv, title) -> new CompilerScreen((CompilerMenu) menu, inv, title));

    @FunctionalInterface
    private interface ScreenConstructor {
        Screen create(AbstractContainerMenu menu, Inventory inventory, Component title);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // 客户端侧没有需要额外注册的渲染器/模型（物品模型走数据驱动），保留钩子以便后续扩展。
        ModLog.LOG.debug("client setup: {}", Minecraft.getInstance().getUser().getName());
    }

    @SubscribeEvent
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        // 服务端机器由 MachineRegistry 统一注册，这里遍历注册表按 id 绑定界面：
        // 加机器忘配界面 → 启动即抛，而不是等玩家右键才发现菜单没界面。
        for (MachineRegistry.MachineHandle<?, ?, ?> machine : MachineRegistry.machines()) {
            ScreenConstructor ctor = SCREENS.get(machine.id());
            if (ctor == null) {
                throw new IllegalStateException("机器 '" + machine.id()
                        + "' 没有界面映射：在 ProgrammableFurnaceClient.SCREENS 里补一条");
            }
            event.register((MenuType) machine.menu().get(),
                    (net.minecraft.client.gui.screens.MenuScreens.ScreenConstructor) (menu, inv, title) -> ctor.create(menu, inv, title));
        }
    }
}
