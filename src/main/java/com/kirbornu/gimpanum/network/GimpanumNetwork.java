package com.kirbornu.gimpanum.network;

import com.kirbornu.gimpanum.perf.PerformanceConfig;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import com.kirbornu.gimpanum.Gimpanum;
import com.kirbornu.gimpanum.converter.ConverterBlockEntity;
import com.kirbornu.gimpanum.converter.ConverterIndex;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;

/** Пакеты мода и их рассылка. */
@EventBusSubscriber(modid = Gimpanum.MOD_ID)
public final class GimpanumNetwork {

    private GimpanumNetwork() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(ConverterMarkersPayload.TYPE, ConverterMarkersPayload.STREAM_CODEC,
                ConverterMarkersHandler::handle);

        registrar.playToClient(DashboardOpenPayload.TYPE, DashboardOpenPayload.STREAM_CODEC,
                DashboardHandlers::open);
        registrar.playToServer(DashboardActionPayload.TYPE, DashboardActionPayload.STREAM_CODEC,
                DashboardHandlers::action);
        registrar.playToServer(DashboardApplyPayload.TYPE, DashboardApplyPayload.STREAM_CODEC,
                DashboardHandlers::apply);
        registrar.playToServer(DashboardRefreshPayload.TYPE, DashboardRefreshPayload.STREAM_CODEC,
                DashboardHandlers::refresh);

        registrar.playToClient(ThawingResultsPayload.TYPE, ThawingResultsPayload.STREAM_CODEC,
                ThawingResultsHandler::handle);

        registrar.playToServer(WingFlapPayload.TYPE, WingFlapPayload.STREAM_CODEC, WingFlapPayload::handle);
    }

    /**
     * Рассылает всем актуальный список конвертеров.
     *
     * <p>Список общий на всех: конвертеры — общественные обменники, и прятать
     * их друг от друга смысла нет.
     */
    private static boolean markersDirty;

    private static long lastMarkers;

    /**
     * Просит разослать метки конвертеров — не сейчас, а не чаще, чем раз в
     * {@code converter.marker_broadcast_ticks}. Загрузка чанка с сотней
     * конвертеров раньше значила сотню рассылок всем игрокам сразу, каждая со
     * всем списком.
     */
    public static void requestMarkerBroadcast() {
        markersDirty = true;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!markersDirty) {
            return;
        }
        long now = event.getServer().getTickCount();
        int gap = PerformanceConfig.of("converter").integer("marker_broadcast_ticks");
        if (now - lastMarkers < gap) {
            return;
        }
        markersDirty = false;
        lastMarkers = now;
        broadcastMarkers(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        markersDirty = false;
    }

    public static void broadcastMarkers(MinecraftServer server) {
        if (server == null || server.getPlayerCount() == 0) {
            return;
        }
        List<ConverterMarkersPayload.Marker> markers = collect(server);
        if (markers != null) {
            PacketDistributor.sendToAllPlayers(new ConverterMarkersPayload(markers));
        }
    }

    public static void sendMarkers(ServerPlayer player) {
        List<ConverterMarkersPayload.Marker> markers = collect(player.server);
        if (markers != null) {
            PacketDistributor.sendToPlayer(player, new ConverterMarkersPayload(markers));
        }
    }

    /**
     * Собирает метки по указателю.
     *
     * <p>Подпись берётся у самого конвертера, а её знает только загруженный
     * блок. У выгруженного остаётся название блока по умолчанию — это честнее,
     * чем держать вторую копию подписи в указателе и следить за её
     * рассинхроном.
     */
    private static List<ConverterMarkersPayload.Marker> collect(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        List<ConverterMarkersPayload.Marker> markers = new ArrayList<>();
        for (ConverterIndex.Entry entry : ConverterIndex.all(server)) {
            ServerLevel level = server.getLevel(entry.dimension());
            String label = "";
            if (level != null && level.hasChunkAt(entry.pos())
                    && level.getBlockEntity(entry.pos()) instanceof ConverterBlockEntity converter) {
                label = converter.label();
            }
            markers.add(new ConverterMarkersPayload.Marker(entry.dimension(), entry.pos(), label));
        }
        return markers;
    }

    /**
     * Шлёт всем содержимое Замороженной органики.
     *
     * <p>Список общий и не секретный: он и так виден каждому, кто переплавил
     * десяток блоков.
     */
    public static void broadcastThawingResults(MinecraftServer server) {
        if (server != null) {
            PacketDistributor.sendToAllPlayers(
                    new ThawingResultsPayload(com.kirbornu.gimpanum.recipe.ThawedOrganics.all()));
        }
    }

    public static void sendThawingResults(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player,
                new ThawingResultsPayload(com.kirbornu.gimpanum.recipe.ThawedOrganics.all()));
    }
}
