package dev.programmablefurnace.network;

import dev.programmablefurnace.base.ModInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务端：依赖库界面按钮操作。
 * action：IN_TO_BATCH（输入槽→依赖库容器）、BATCH_TO_OUTPUT（依赖库容器选中条目→输出槽）。
 * index：对 BATCH_TO_OUTPUT 是依赖库区内的相对偏移；对 IN_TO_BATCH 可忽略（固定用输入槽）。
 *
 * <p>协议层只保留"伪造值丢弃"（{@code NONE} 兜底、负下标）与归属校验，具体搬运用哪条规则
 * 交给 {@link PacketHandlers} 登记的处理体——任何实现批量语义的机器都能复用这个包。
 */
public record BatchMovePacket(BlockPos pos, Action action, int index) implements CustomPacketPayload {

    public enum Action {
        IN_TO_BATCH,
        BATCH_TO_OUTPUT,
        BATCH_REMOVE,
        /** 解码兜底：伪造 ordinal 的包落到这里，handler 直接忽略（否则 idMapper 会在解码期抛 AIOOBE）。 */
        NONE
    }

    public static final CustomPacketPayload.Type<BatchMovePacket> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModInfo.MODID, "batch_move"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BatchMovePacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, BatchMovePacket::pos,
            ByteBufCodecs.idMapper(
                    i -> i >= 0 && i < Action.values().length ? Action.values()[i] : Action.NONE,
                    Action::ordinal), BatchMovePacket::action,
            ByteBufCodecs.VAR_INT, BatchMovePacket::index,
            BatchMovePacket::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(BatchMovePacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.action() == Action.NONE || payload.index() < 0) {
                return;
            }
            BlockEntity be = PacketGuard.machine(context, payload.pos());
            if (be == null || !(context.player() instanceof ServerPlayer player)) {
                return;
            }
            PacketHandlers.dispatch(TYPE, payload, player, be);
        });
    }
}
