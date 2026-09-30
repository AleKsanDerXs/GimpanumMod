package com.kirbornu.gimpanum.network;

import com.kirbornu.gimpanum.Gimpanum;
import com.kirbornu.gimpanum.core.CoreConfig;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.UUID;

/**
 * Правка настройки: перенести указанные части образца в указанные Ядра.
 *
 * <p>Один пакет и на «сохранить изменения выбранного Ядра», и на «скопировать
 * настройку на отмеченные». Это одна и та же операция: в первом случае
 * получатель один, а частей — все.
 *
 * @param sections битовая маска {@link com.kirbornu.gimpanum.dashboard.ConfigSection}
 */
public record DashboardApplyPayload(List<UUID> cores, CoreConfig source, int sections)
        implements CustomPacketPayload {

    /** Больше Ядер в одном пакете не принимается: список приходит от клиента, до проверки прав. */
    public static final int MAX_CORES = 1500;

    public static final Type<DashboardApplyPayload> TYPE = new Type<>(Gimpanum.id("dashboard_apply"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DashboardApplyPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_CORES)), DashboardApplyPayload::cores,
                    CoreConfig.STREAM_CODEC, DashboardApplyPayload::source,
                    ByteBufCodecs.VAR_INT, DashboardApplyPayload::sections,
                    DashboardApplyPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
