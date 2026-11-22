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
 * 客户端 → 服务端：玩家在编译器 GUI 里点"编译"时，把方块坐标 + 输入的代码发到服务端。
 *
 * <p>这里只做协议层的事：长度上限、坐标归属校验，然后把载荷交给 {@link PacketHandlers}。
 * "编译"具体是什么、失败怎么回话，由组合根登记的处理体决定——协议层不认识任何机器。
 */
public record CompilePacket(BlockPos pos, String code) implements CustomPacketPayload {

    /** 源码长度上限（GUI 编辑器本身远小于此）。超出直接丢弃：32KB 的畸形源码足以把解析器/内存打满。 */
    private static final int MAX_CODE_LENGTH = 16_384;

    public static final CustomPacketPayload.Type<CompilePacket> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModInfo.MODID, "compile"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CompilePacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, CompilePacket::pos,
            ByteBufCodecs.STRING_UTF8, CompilePacket::code,
            CompilePacket::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CompilePacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.code() == null || payload.code().length() > MAX_CODE_LENGTH) {
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
