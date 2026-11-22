package dev.programmablefurnace.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.HashMap;
import java.util.Map;

/**
 * 服务端包处理登记表：协议层只做"解码 + 归属校验"，具体某台机器怎么响应由注册方决定。
 *
 * <p>为什么要有这一层：{@code network} 是最靠近框架的一层，如果它在处理函数里直接
 * {@code instanceof CompilerBlockEntity}，协议层就永久绑死在具体机器上——每加一种机器、
 * 每加一种交互，都得回来改包；分层图上也多出一条"下层引用上层实现"的反向边。
 *
 * <p>现在的形状是：包只管把 <em>谁（方块实体）</em> 和 <em>载荷</em> 交给这里，组合根
 * （{@code ProgrammableFurnaceMod}）在启动时把"哪种包 → 哪段处理"登记进来。于是
 * {@code network} 对 {@code compiler}/{@code furnace} 一个 import 都没有，新机器只是在
 * 组合根多一行登记。
 *
 * <p>没登记的类型不是错误：旧客户端可能发包给新版本没有的交互，静默丢弃即可。
 */
public final class PacketHandlers {

    /**
     * 一种包的处理体。
     *
     * @param <P> 载荷类型
     */
    public interface Handler<P extends CustomPacketPayload> {
        /**
         * @param payload 已解码的载荷（各字段已在包里做过范围校验）
         * @param player  发包的服务端玩家
         * @param be      该坐标上的方块实体（已通过距离与菜单绑定校验）
         */
        void handle(P payload, ServerPlayer player, BlockEntity be);
    }

    private static final Map<CustomPacketPayload.Type<?>, Handler<?>> HANDLERS = new HashMap<>();

    private PacketHandlers() {
    }

    /**
     * 登记一种包的处理体。
     *
     * <p>同一个类型重复登记会直接抛异常，而不是让后一条覆盖前一条。理由：NeoForge 的
     * {@code NetworkRegistry.setup()} 保证 {@code RegisterPayloadHandlersEvent} 每个 JVM 只 fire 一次
     * （它自己用 {@code setup} 标记挡了第二次调用，见其 {@code setup()} 里的
     * {@code IllegalStateException("The network registry can only be setup once.")}），
     * 所以"重复登记"只可能来自我们自己的笔误（组合根里同一个 TYPE 写了两遍），
     * 那就是个该立刻炸出来的 bug——静默覆盖会让先登记那条永远不生效，且没有任何痕迹。
     */
    public static <P extends CustomPacketPayload> void register(CustomPacketPayload.Type<P> type, Handler<P> handler) {
        if (type == null || handler == null) {
            throw new IllegalArgumentException("登记包处理体不能为空：type=" + type);
        }
        Handler<?> previous = HANDLERS.putIfAbsent(type, handler);
        if (previous != null) {
            throw new IllegalStateException("包 '" + type.id() + "' 已经登记过处理体，重复登记会让其中一条永远静默失效");
        }
    }

    /**
     * 把载荷交给登记的处理体；没登记就丢弃。
     */
    public static <P extends CustomPacketPayload> void dispatch(CustomPacketPayload.Type<P> type, P payload,
                                                               ServerPlayer player, BlockEntity be) {
        if (type == null || payload == null || player == null || be == null) {
            return;
        }
        Handler<?> raw = HANDLERS.get(type);
        if (raw == null) {
            return;
        }
        @SuppressWarnings("unchecked")
        Handler<P> handler = (Handler<P>) raw;
        handler.handle(payload, player, be);
    }
}
