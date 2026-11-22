package dev.programmablefurnace.network;

import dev.programmablefurnace.machine.MachineMenu;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * C2S 包的统一前置校验（L1 胶水层：只判"该不该处理"，不含业务）。
 * <p>没有这层时，任何客户端都能凭一个 BlockPos 远程编译 / 切换界面 / 搬依赖库 / 启动机器——
 * 服务端只判了"方块实体类型对"，等于把四个 GUI 变成公开 RPC。三条判据：</p>
 * <ul>
 *   <li>发送者是 {@link ServerPlayer}（把客户端本地上下文/非玩家上下文排除掉）；</li>
 *   <li>坐标在玩家交互距离内（8 格）；</li>
 *   <li>玩家当前打开的菜单就绑在这个方块实体上——即"这台机器的 GUI 里按的按钮"。</li>
 * </ul>
 * <p>校验不过一律返回 null 且不回包：对伪造者没有可观测反馈，对正常玩家没有影响。</p>
 */
final class PacketGuard {

    private static final double MAX_INTERACTION_DISTANCE_SQR = 64.0;   // 8 格

    private PacketGuard() {}

    /** 通过校验返回目标方块实体，否则 null。 */
    static BlockEntity machine(IPayloadContext context, BlockPos pos) {
        Player player = context.player();
        if (!(player instanceof ServerPlayer sp)) return null;
        if (!(sp.level() instanceof ServerLevel level)) return null;
        if (sp.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > MAX_INTERACTION_DISTANCE_SQR) {
            return null;
        }
        // 菜单校验把操作绑在"自己打开的那台机器"上：换个坐标也操作不了别人的机器
        if (!(sp.containerMenu instanceof MachineMenu<?> menu)) return null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;
        if (menu.getBlockEntity() != be) return null;
        return be;
    }
}
