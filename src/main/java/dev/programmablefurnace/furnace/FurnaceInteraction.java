package dev.programmablefurnace.furnace;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 执行器的"服务端交互"：开始 / 终止运行，以及随之而来的规则。
 *
 * <p>规则有三条，但它们都是业务而不是协议：①没有可运行程序时"开始"没有意义（否则运行标志会
 * 一直亮着、每 tick 空转）；②状态没变时不必标脏重写存档；③按"开始"却因为编译失败跑不起来时，
 * 必须把原因说出来——否则玩家面对的是一台"点了没反应"的熔炉。
 * 协议层不该知道这些，所以它们放在这里，由组合根登记。</p>
 */
public final class FurnaceInteraction {

    /** 一次提示里最多列几条错误：聊天栏放不下更多，完整列表在源文件 tooltip 里。 */
    private static final int MAX_REPORTED_ERRORS = 3;

    private FurnaceInteraction() {
    }

    /**
     * 响应开始 / 终止请求。
     *
     * @param furnace 目标方块实体
     * @param start   {@code true} 开始，{@code false} 终止
     * @param player  发包的玩家，用于回话（可为 null，则只做事不提示）
     */
    public static void setRunning(ProgrammableFurnaceBlockEntity furnace, boolean start, ServerPlayer player) {
        if (start && !furnace.hasExecutableProgram()) {
            reportCompileErrors(furnace, player);
            return;
        }
        if (furnace.isRunning() == start) {
            return;   // 重复点同一个按钮不必重写存档
        }
        furnace.setRunning(start);
        furnace.setChanged();
    }

    /**
     * 把"为什么跑不起来"直接告诉按按钮的人。
     *
     * <p>编译发生在方块实体的每 20 tick 检测里，玩家按下"开始"时可能早就编译失败了；
     * 这时如果只是静默返回，界面上什么都不会变，观感就是按钮坏了。</p>
     */
    private static void reportCompileErrors(ProgrammableFurnaceBlockEntity furnace, ServerPlayer player) {
        if (player == null) return;
        List<String> errors = furnace.lastCompileErrors();
        if (errors.isEmpty()) return;   // 确实没有程序，也没什么好解释的
        player.sendSystemMessage(Component.translatable(
                "message.programmablefurnace.furnace_compile_failed", errors.size()));
        int show = Math.min(errors.size(), MAX_REPORTED_ERRORS);
        for (int i = 0; i < show; i++) {
            player.sendSystemMessage(Component.literal("§c" + errors.get(i)));
        }
        if (errors.size() > show) {
            player.sendSystemMessage(Component.literal(
                    "§7…共 " + errors.size() + " 条错误，悬停依赖库里的文件可看全部"));
        }
    }
}
