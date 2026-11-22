package dev.programmablefurnace.item;

import dev.programmablefurnace.base.CodeStorage;
import dev.programmablefurnace.base.ContentState;
import dev.programmablefurnace.base.ModInfo;
import dev.programmablefurnace.base.ModLog;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 物品注册：编码文件三态、U 盘扩展卡、燃烧优化卡、示例程序。
 *
 * <p>以前这些 {@code DeferredItem} 常量都挂在入口类 {@code ProgrammableFurnaceMod} 上，
 * 结果是 {@code item} 包反过来 import 入口类。现在物品的注册表跟着物品走，
 * 入口类只调一次 {@link #register(IEventBus)}。</p>
 */
public final class ModItems {

    private ModItems() {}

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ModInfo.MODID);

    // ===== 编码文件（三态独立物品）=====
    public static final DeferredItem<CodeFileItem> CODE_FILE_BLANK = ITEMS.registerItem("code_file_blank",
            p -> new CodeFileItem(p, ContentState.BLANK));
    public static final DeferredItem<CodeFileItem> CODE_FILE_EXEC = ITEMS.registerItem("code_file_exec",
            p -> new CodeFileItem(p, ContentState.EXECUTABLE));
    public static final DeferredItem<CodeFileItem> CODE_FILE_FAILED = ITEMS.registerItem("code_file_failed",
            p -> new CodeFileItem(p, ContentState.FAILED));
    /** 旧单物品：仍注册为空白（兼容引用），图标用空白文件。 */
    public static final DeferredItem<CodeFileItem> CODE_FILE = CODE_FILE_BLANK;

    // ===== U 盘扩展卡（决定工作界面多输入/输出布局，三档 212/313/414）=====
    public static final DeferredItem<UsbCardItem> USB_CARD_I = ITEMS.registerItem("usb_card_i",
            p -> new UsbCardItem(p, 1, 212));
    public static final DeferredItem<UsbCardItem> USB_CARD_II = ITEMS.registerItem("usb_card_ii",
            p -> new UsbCardItem(p, 2, 313));
    public static final DeferredItem<UsbCardItem> USB_CARD_III = ITEMS.registerItem("usb_card_iii",
            p -> new UsbCardItem(p, 3, 414));

    // ===== 燃烧优化卡（三档，与当前燃料逐项取优）=====
    public static final DeferredItem<BoostCardItem> USB_CARD_BOOST_I = ITEMS.registerItem("usb_card_boost_i",
            p -> new BoostCardItem(p, 1));
    public static final DeferredItem<BoostCardItem> USB_CARD_BOOST_II = ITEMS.registerItem("usb_card_boost_ii",
            p -> new BoostCardItem(p, 2));
    public static final DeferredItem<BoostCardItem> USB_CARD_BOOST_III = ITEMS.registerItem("usb_card_boost_iii",
            p -> new BoostCardItem(p, 3));

    // ===== 示例程序（内置烧炼脚本源码，创造栏直接发放即用）=====
    public static final DeferredItem<ExampleProgramItem> EXAMPLE_PROGRAM = ITEMS.registerItem("example_program",
            p -> new ExampleProgramItem(p));

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }

    /** 示例程序 Stack：预置内置烧炼脚本源码 + READONLY 状态（只读可执行，编译器拒绝重编译）。 */
    public static ItemStack exampleProgramStack() {
        ItemStack stack = new ItemStack(EXAMPLE_PROGRAM.get());
        CodeStorage.setCode(stack, loadExampleSource());
        // READONLY 同时写 tag 与 ITEM_MODEL，否则显示名/图标会与内容不符
        CodeStorage.setContentState(stack, ContentState.READONLY);
        return stack;
    }

    /** 读取打包进 mod jar 的示例烧炼脚本源码（resources/programmablefurnace/scripts/furnace_smelting.mc）。 */
    private static String loadExampleSource() {
        try (InputStream in = ModItems.class.getResourceAsStream("/programmablefurnace/scripts/furnace_smelting.mc")) {
            if (in == null) {
                ModLog.LOG.warn("示例脚本资源缺失: /programmablefurnace/scripts/furnace_smelting.mc");
                return "";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ModLog.LOG.warn("加载示例脚本失败", e);
            return "";
        }
    }
}
