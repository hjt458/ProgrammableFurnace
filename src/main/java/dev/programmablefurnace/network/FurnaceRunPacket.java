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
 * 客户端 → 服务端：执行器（可编程熔炉）开始 / 终止运行。
 * start=true 表示"开始"（tick 开始跑 RunLoop），start=false 表示"终止"（停止跑 RunLoop）。
 *
 * <p>"没有可运行程序时不允许开始"这类规则不在这里——协议层只转发，规则跟着机器走。
 */
public record FurnaceRunPacket(BlockPos pos, boolean start) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FurnaceRunPacket> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModInfo.MODID, "furnace_run"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FurnaceRunPacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, FurnaceRunPacket::pos,
            ByteBufCodecs.BOOL, FurnaceRunPacket::start,
            FurnaceRunPacket::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(FurnaceRunPacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            BlockEntity be = PacketGuard.machine(context, payload.pos());
            if (be == null || !(context.player() instanceof ServerPlayer player)) {
                return;
            }
            PacketHandlers.dispatch(TYPE, payload, player, be);
        });
    }
}
