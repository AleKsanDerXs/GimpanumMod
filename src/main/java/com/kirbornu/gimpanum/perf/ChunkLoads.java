package com.kirbornu.gimpanum.perf;

import com.kirbornu.gimpanum.Gimpanum;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Неблокирующая подгрузка чанков.
 *
 * <p>Синхронный {@code getChunk(x, z, FULL, true)} на главном потоке стоит
 * ровно столько, сколько занимает чтение или генерация чанка, и весь этот
 * срок стоит сервер. Здесь вместо этого вешается временный билет — игра сама
 * грузит и генерирует чанк на рабочих потоках, — а результат забирается
 * опросом раз в тик. Обратный вызов всегда выполняется на главном потоке.
 *
 * <p>Число одновременных запросов ограничено {@code chunk_loading.max_in_flight}:
 * остальные ждут в очереди, так что даже команда над сотней Ядер не
 * устроит очередь из сотни поколений чанков разом.
 */
@EventBusSubscriber(modid = Gimpanum.MOD_ID)
public final class ChunkLoads {

    /** Как часто продлевать билеты активных запросов; живут они вдвое дольше. */
    private static final int REFRESH_TICKS = 40;

    private static final TicketType<ChunkPos> TICKET = TicketType.create(
            Gimpanum.MOD_ID + ":async", Comparator.comparingLong(ChunkPos::toLong), 2 * REFRESH_TICKS + 20);

    private static final class Request {
        final ServerLevel level;
        final List<ChunkPos> chunks;
        final Consumer<Boolean> done;
        long deadline;
        long nextRefresh;

        Request(ServerLevel level, List<ChunkPos> chunks, Consumer<Boolean> done) {
            this.level = level;
            this.chunks = chunks;
            this.done = done;
        }
    }

    private static final Deque<Request> WAITING = new ArrayDeque<>();
    private static final List<Request> ACTIVE = new ArrayList<>();

    private ChunkLoads() {
    }

    /** Все ли эти чанки уже в памяти целиком. */
    public static boolean loaded(ServerLevel level, Collection<ChunkPos> chunks) {
        for (ChunkPos pos : chunks) {
            if (level.getChunkSource().getChunkNow(pos.x, pos.z) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Дождаться, пока все чанки станут целыми, и сообщить об этом.
     *
     * <p>Если они уже загружены, обратный вызов срабатывает сразу, в этом же
     * вызове. {@code true} — чанки готовы, {@code false} — не дождались.
     */
    public static void request(ServerLevel level, Collection<ChunkPos> chunks, Consumer<Boolean> done) {
        List<ChunkPos> wanted = List.copyOf(chunks);
        if (loaded(level, wanted)) {
            done.accept(true);
            return;
        }
        WAITING.add(new Request(level, wanted, done));
    }

    public static void request(ServerLevel level, ChunkPos chunk, Consumer<Boolean> done) {
        request(level, List.of(chunk), done);
    }

    /** Запросов в очереди и в работе — для {@code /gimpanum stats}. */
    public static int waiting() {
        return WAITING.size();
    }

    public static int active() {
        return ACTIVE.size();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (WAITING.isEmpty() && ACTIVE.isEmpty()) {
            return;
        }
        var config = PerformanceConfig.of("chunk_loading");
        int limit = Math.max(1, config.integer("max_in_flight"));
        // Срок — по настенным часам, а не по тикам: генерация чанков идёт на рабочих
        // потоках и от частоты тиков не зависит.
        long timeout = Math.max(1, config.integer("timeout_seconds")) * 1000L;
        long tick = event.getServer().getTickCount();
        long now = System.currentTimeMillis();

        while (ACTIVE.size() < limit && !WAITING.isEmpty()) {
            Request request = WAITING.poll();
            request.deadline = now + timeout;
            hold(request, tick);
            ACTIVE.add(request);
        }

        // Завершившиеся собираем отдельно: обратный вызов вправе заказать ещё чанки.
        List<Request> finished = null;
        List<Boolean> outcome = null;
        Iterator<Request> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Request request = it.next();
            Boolean result = null;
            if (loaded(request.level, request.chunks)) {
                result = Boolean.TRUE;
            } else if (now >= request.deadline) {
                result = Boolean.FALSE;
            } else if (tick >= request.nextRefresh) {
                // Билет живёт недолго, а долгая генерация без него сорвалась бы на полпути.
                hold(request, tick);
            }
            if (result != null) {
                it.remove();
                if (finished == null) {
                    finished = new ArrayList<>();
                    outcome = new ArrayList<>();
                }
                finished.add(request);
                outcome.add(result);
            }
        }
        if (finished != null) {
            for (int i = 0; i < finished.size(); i++) {
                try {
                    finished.get(i).done.accept(outcome.get(i));
                } catch (Throwable failure) {
                    Gimpanum.LOGGER.error("Обратный вызов подгрузки чанков упал", failure);
                }
            }
        }
    }

    /** Вешает (или продлевает) билеты на чанки запроса. */
    private static void hold(Request request, long tick) {
        for (ChunkPos pos : request.chunks) {
            request.level.getChunkSource().addRegionTicket(TICKET, pos, 0, pos);
        }
        request.nextRefresh = tick + REFRESH_TICKS;
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        WAITING.clear();
        ACTIVE.clear();
    }
}
