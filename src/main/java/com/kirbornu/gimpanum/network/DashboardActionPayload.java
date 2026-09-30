package com.kirbornu.gimpanum.network;

import com.kirbornu.gimpanum.Gimpanum;
import com.kirbornu.gimpanum.dashboard.CoreAction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.UUID;

/**
 * Действие консоли над одним или несколькими Ядрами.
 *
 * <p>Список идентификаторов, а не один: в консоли отмечают несколько Ядер и
 * применяют действие ко всем сразу. Одиночное действие — тот же пакет со
 * списком из одного.
 *
 * @param argument смысл зависит от действия: имя экипажа для привязки и
 *                 отвязки, пусто для всего остального
 */
public record DashboardActionPayload(List<UUID> cores, CoreAction action, String argument)
        implements CustomPacketPayload {

    /** Больше Ядер в одном пакете не принимается: список приходит от клиента, до проверки прав. */
    public static final int MAX_CORES = 1500;

    public static final Type<DashboardActionPayload> TYPE = new Type<>(Gimpanum.id("dashboard_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DashboardActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_CORES)), DashboardActionPayload::cores,
                    CoreAction.STREAM_CODEC, DashboardActionPayload::action,
                    ByteBufCodecs.STRING_UTF8, DashboardActionPayload::argument,
                    DashboardActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
