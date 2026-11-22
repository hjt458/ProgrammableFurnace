package dev.programmablefurnace.item;

import dev.programmablefurnace.base.ContentState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * 示例程序物品：一种可执行文件，但为"只读可执行"（{@link ContentState#READONLY}）。
 * <p>内容可运行——放熔炉程序槽照常执行（{@link ContentState#isRunnable()} 认 READONLY）；
 * 但编译器 {@code tryCompile} 明确拒绝重新编译，如同只读/锁定文件，源码不可被覆盖或改写。</p>
 * <p>Stack 上预置了内置烧炼脚本源码，玩家从创造栏直接拿取即可放入熔炉程序槽运行，
 * 无需手动复制/编译脚本。复用 {@link CodeFileItem} 多状态独立物品架构。</p>
 */
public class ExampleProgramItem extends CodeFileItem {

    public ExampleProgramItem(Properties properties) {
        super(properties, ContentState.READONLY);
    }

    /** 显示名固定为"示例程序（只读）"，区别于普通可执行文件。 */
    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable("item.programmablefurnace.example_program");
    }
}
