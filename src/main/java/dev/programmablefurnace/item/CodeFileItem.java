package dev.programmablefurnace.item;
import dev.programmablefurnace.base.CodeHolder;
import dev.programmablefurnace.base.CodeStorage;
import dev.programmablefurnace.base.ContentState;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

import java.util.List;
import java.util.function.Consumer;

/**
 * 可编译文件（编码文件）：代码载体，玩家在编译器中写入代码，编译器据此产出磁盘。
 * <p><b>多状态独立物品架构</b>：空白/可执行/编译失败是三个独立注册的物品（各有固定 SIM 图标，
 * 白/蓝/红），本类通过 {@link #state} 区分，各实例的显示名固定。机器（编译器）在运行时
 * 根据代码分析结果把输入物品替换为对应类型的实例。</p>
 */
public class CodeFileItem extends Item implements CodeHolder {

    /** 本物品实例代表的固定内容状态。 */
    private final ContentState state;

    public CodeFileItem(Properties properties, ContentState state) {
        super(properties);
        this.state = state;
    }

    public ContentState state() {
        return state;
    }

    /**
     * 由逻辑状态取对应的可编译文件物品实例。
     * <p>依赖库文件（{@link ContentState#DEPENDENCY}）没有单独注册物品，复用可执行文件物品：
     * 图标由 {@link CodeStorage#setContentState} 写的 {@code ITEM_MODEL} 切到 dependency 模型，
     * 显示名同样按 tag 取——多个状态共用一个物品实例，靠组件区分外观。</p>
     */
    public static Item codeFileFor(ContentState state) {
        return switch (state) {
            case EXECUTABLE, DEPENDENCY -> ModItems.CODE_FILE_EXEC.get();
            case READONLY -> ModItems.EXAMPLE_PROGRAM.get();
            case FAILED -> ModItems.CODE_FILE_FAILED.get();
            default -> ModItems.CODE_FILE_BLANK.get();
        };
    }

    /**
     * 这个 ItemStack 是不是"一段可以被机器拿去的代码"：非空 + 是本模组的编码文件 + 真的带代码 +
     * 状态可运行（{@link ContentState#isRunnable()}）。
     * <p>与"槽位收不收"（{@code canPlaceItem} 里的 {@code instanceof CodeFileItem}）不是一回事：空白文件
     * 必须能放进输入槽让你去编译，但它还不是可运行的代码。混用会让空白文件被拦在门外。</p>
     * <p>定义在此而不在 {@code base/CodeStorage}，是因为它需要认识 {@link CodeFileItem} 这个具体类；
     * 放进 base 就会形成 L1 内部"base → item 具体类"的边（规范第 58 条）。</p>
     */
    public static boolean holdsRunnableCode(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && stack.getItem() instanceof CodeFileItem
                && CodeStorage.hasCode(stack)
                && CodeStorage.getContentState(stack).isRunnable();
    }

    /** 显示名：按 tag 里的实际状态取（依赖库文件与可执行文件共用同一物品实例）。 */
    @Override
    public Component getName(ItemStack stack) {
        return switch (CodeStorage.getContentStateOr(stack, state)) {
            case EXECUTABLE, READONLY -> Component.translatable("item.programmablefurnace.code_file.exec");
            case DEPENDENCY -> Component.translatable("item.programmablefurnace.code_file.dependency");
            case FAILED -> Component.translatable("item.programmablefurnace.code_file.failed");
            default -> Component.translatable("item.programmablefurnace.code_file.blank");
        };
    }

    /**
     * 悬停展示：有编译错误时优先显示错误（错误码 + 位置），否则显示代码前几行。
     * <p>注意判据是"有没有错误"而不是"状态是不是 FAILED"：熔炉依赖库里的文件编译失败后
     * 仍然保持原有的可执行状态（否则它会被排除出模块枚举，坏文件凭空消失），错误只挂在 tag 上，
     * 所以这里要按 tag 判断。有错误时也不再展示源码——玩家此刻最需要的是"哪一行错了"。</p>
     */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
                                Consumer<Component> consumer, TooltipFlag tooltipFlag) {
        List<String> errors = CodeStorage.getCompileErrors(stack);
        if (!errors.isEmpty()) {
            consumer.accept(Component.translatable("item.programmablefurnace.code_file.errors",
                    errors.size()));
            int show = Math.min(errors.size(), 5);
            for (int i = 0; i < show; i++) {
                consumer.accept(Component.literal("§c" + errors.get(i)));
            }
            if (errors.size() > show) {
                consumer.accept(Component.literal("§7…共 " + errors.size() + " 条错误"));
            }
            return;
        }
        String code = CodeStorage.getCode(stack);
        if (code.isEmpty()) {
            consumer.accept(Component.translatable("item.programmablefurnace.code_file.empty"));
            return;
        }
        String[] lines = code.split("\\n", -1);
        int show = Math.min(lines.length, 5);
        for (int i = 0; i < show; i++) {
            consumer.accept(Component.literal("§7" + lines[i]));
        }
        if (lines.length > show) {
            consumer.accept(Component.literal("§7…共 " + lines.length + " 行"));
        }
    }
}

