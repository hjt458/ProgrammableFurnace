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
 * 客户端 → 服务端：双界面模式切换（0 = 第一界面，1 = 依赖库）。
 *
 * <p>取值范围校验留在协议层（伪造的 7 直接丢弃），切换语义（要不要迁移槽位、要不要标脏）
 * 交给 {@link PacketHandlers} 登记的处理体。
 */
public record UiModePacket(BlockPos pos, int uiMode) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<UiModePacket> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(ModInfo.MODID, "ui_mode"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UiModePacket> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, UiModePacket::pos,
            ByteBufCodecs.VAR_INT, UiModePacket::uiMode,
            UiModePacket::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(UiModePacket payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (payload.uiMode() != 0 && payload.uiMode() != 1) {
                return;   // 只有"工作界面/依赖库"两套
            }
            BlockEntity be = PacketGuard.machine(context, payload.pos());
            if (be == null || !(context.player() instanceof ServerPlayer player)) {
                return;
            }
            PacketHandlers.dispatch(TYPE, payload, player, be);
        });
    }
}
