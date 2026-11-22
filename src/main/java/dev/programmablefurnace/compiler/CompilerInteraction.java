package dev.programmablefurnace.compiler;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 编译器的"服务端交互"：把 {@link CompilerBlockEntity#tryCompile(String)} 的结果翻译成玩家能看见的反馈。
 *
 * <p>为什么不在包里写：{@code network} 属于协议层，它不该知道"编译器"存在；这段是业务
 * （编译失败要回话、只回前三条），放在机器自己旁边。组合根负责把它登记给 {@code CompilePacket}。
 */
public final class CompilerInteraction {

    /** 回给玩家的错误条数上限：再多就刷屏了，完整清单在产物文件的 tooltip 里。 */
    private static final int MAX_REPORTED_ERRORS = 3;

    private CompilerInteraction() {
    }

    /**
     * 编译并把失败原因回给玩家。成功时不打扰玩家。
     *
     * @param compiler 目标方块实体
     * @param code     源码
     * @param player   触发编译的玩家
     */
    public static void compile(CompilerBlockEntity compiler, String code, ServerPlayer player) {
        List<String> errors = compiler.tryCompile(code);
        if (errors.isEmpty()) {
            return;
        }
        player.sendSystemMessage(
                Component.translatable("message.programmablefurnace.compile_failed", errors.size()));
        for (int i = 0; i < Math.min(errors.size(), MAX_REPORTED_ERRORS); i++) {
            player.sendSystemMessage(Component.literal("§c" + errors.get(i)));
        }
    }
}
