package dev.programmablefurnace;

import dev.programmablefurnace.base.BatchStorage;
import dev.programmablefurnace.base.ModInfo;
import dev.programmablefurnace.base.ModLog;
import dev.programmablefurnace.base.UIModeSwitcher;
import dev.programmablefurnace.FuelConfig;
import dev.programmablefurnace.base.GuiConfig;
import dev.programmablefurnace.base.MachineConfigs;
import dev.programmablefurnace.compiler.CompilerRegistration;
import dev.programmablefurnace.furnace.FurnaceRegistration;
import dev.programmablefurnace.machine.ModRegisters;
import dev.programmablefurnace.compiler.CompilerBlockEntity;
import dev.programmablefurnace.compiler.CompilerInteraction;
import dev.programmablefurnace.datagen.ModDataGen;
import dev.programmablefurnace.furnace.FurnaceInteraction;
import dev.programmablefurnace.furnace.ProgrammableFurnaceBlockEntity;
import dev.programmablefurnace.gametest.ModGameTests;
import dev.programmablefurnace.item.ModItems;
import dev.programmablefurnace.item.ModTabs;
import dev.programmablefurnace.machine.MachineRegistry;
import dev.programmablefurnace.network.BatchMovePacket;
import dev.programmablefurnace.network.CompilePacket;
import dev.programmablefurnace.network.FurnaceRunPacket;
import dev.programmablefurnace.network.PacketHandlers;
import dev.programmablefurnace.network.UiModePacket;
import dev.programmablefurnace.recipe.ModRecipes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 模组入口：<b>只做组装</b>。
 *
 * <p>这里曾经是所有 {@code DeferredRegister} 与 {@code DeferredItem}/{@code DeferredBlock} 句柄的家，
 * 于是 {@code compiler}/{@code furnace}/{@code item}/{@code machine}/{@code recipe} 这些下层包为了拿一个常量
 * 反过来 import 入口类——注册表成了"什么都知道的上帝对象"。现在按领域拆开：</p>
 *
 * <ul>
 *   <li>机器（方块/方块物品/BE/Menu）：{@link dev.programmablefurnace.machine.ModRegisters} 提供注册表，各机器自己注册（{@code CompilerRegistration} / {@code FurnaceRegistration}）</li>
 *   <li>物品（编码文件、U 盘卡、优化卡、示例程序）：{@link ModItems}</li>
 *   <li>创造标签：{@link ModTabs}</li>
 *   <li>配方序列化器：{@link ModRecipes}</li>
 *   <li>GameTest 函数：{@link ModGameTests}</li>
 *   <li>日志：{@link ModLog}</li>
 * </ul>
 *
 * <p>本类剩下的职责只有：加载配置、把上面这些注册表挂到 mod 事件总线、注册网络包与它们的处理体。</p>
 */
@Mod(ModInfo.MODID)
public final class ProgrammableFurnaceMod {

    /** 兼容旧引用；新代码请直接用 {@link ModInfo#MODID}。 */
    public static final String MODID = ModInfo.MODID;

    public ProgrammableFurnaceMod(IEventBus modEventBus, ModContainer modContainer) {
        // 注册阶段加载全局 GUI 布局配置（gui_config.json）与各机器配置（compiler/furnace_config.json）
        GuiConfig.load();
        MachineConfigs.load();
        FuelConfig.load();

        modEventBus.addListener(this::commonSetup);

        // 各领域的注册表各自挂总线
        ModRegisters.register(modEventBus);
        CompilerRegistration.init();
        FurnaceRegistration.init();
        ModItems.register(modEventBus);
        ModTabs.register(modEventBus);
        ModRecipes.register(modEventBus);
        ModGameTests.register(modEventBus);

        // datagen：注册 GameTest 测试实例 + 生成结构模板
        modEventBus.addListener(ModDataGen::gatherData);

        NeoForge.EVENT_BUS.register(this);

        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::registerPayloads);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        // 燃料/布局/机器配置均在构造阶段由资源 JSON 加载（FuelConfig/GuiConfig/MachineConfigs）
    }

    /** 把机器方块物品也放进原版建造方块标签页。 */
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.BUILDING_BLOCKS) {
            MachineRegistry.machines().forEach(h -> event.accept(h.item()));
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        registerPacketHandlers();
        event.registrar(ModInfo.MODID)
                .playToServer(CompilePacket.TYPE, CompilePacket.STREAM_CODEC, CompilePacket::handle)
                .playToServer(BatchMovePacket.TYPE, BatchMovePacket.STREAM_CODEC, BatchMovePacket::handle)
                .playToServer(UiModePacket.TYPE, UiModePacket.STREAM_CODEC, UiModePacket::handle)
                .playToServer(FurnaceRunPacket.TYPE, FurnaceRunPacket.STREAM_CODEC, FurnaceRunPacket::handle);
    }

    /**
     * 组合根：把"哪种包 → 哪台机器怎么处理"登记进 {@link PacketHandlers}。
     *
     * <p>这里允许认识所有具体机器——它就是唯一该知道全貌的地方。协议层（{@code network}）因此
     * 不必 import 任何 {@code compiler}/{@code furnace} 类，新增机器只是在这张表里多一行。</p>
     */
    private void registerPacketHandlers() {
        PacketHandlers.register(CompilePacket.TYPE, (payload, player, be) -> {
            if (be instanceof CompilerBlockEntity compiler) {
                CompilerInteraction.compile(compiler, payload.code(), player);
            }
        });
        PacketHandlers.register(BatchMovePacket.TYPE, (payload, player, be) -> {
            if (!(be instanceof BatchStorage storage)) {
                return;
            }
            boolean changed = switch (payload.action()) {
                case IN_TO_BATCH -> storage.moveInputToBatch(0);
                case BATCH_REMOVE -> storage.removeBatchItem(payload.index());
                case BATCH_TO_OUTPUT -> storage.moveBatchToOutput(payload.index());
                default -> false;   // NONE 已在协议层丢弃
            };
            if (changed) {
                be.setChanged();
            }
        });
        PacketHandlers.register(UiModePacket.TYPE, (payload, player, be) -> {
            if (be instanceof UIModeSwitcher switcher) {
                switcher.applySwitchMode(payload.uiMode());
                be.setChanged();
            }
        });
        PacketHandlers.register(FurnaceRunPacket.TYPE, (payload, player, be) -> {
            if (be instanceof ProgrammableFurnaceBlockEntity furnace) {
                // 带上 player：按"开始"却跑不起来时，由交互层负责把编译错误说给这个人听
                FurnaceInteraction.setRunning(furnace, payload.start(), player);
            }
        });
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        ModLog.LOG.debug("server starting：可编程熔炉已加载");
    }
}
