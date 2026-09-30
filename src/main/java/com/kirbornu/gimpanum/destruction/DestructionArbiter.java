package com.kirbornu.gimpanum.destruction;

import com.kirbornu.gimpanum.perf.PerformanceConfig;
import com.kirbornu.gimpanum.Gimpanum;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Отличает настоящее уничтожение блока от его переезда.
 *
 * <p>Проблема: сборка физической конструкции Sable удаляет блок из мира и
 * создаёт его копию в служебной делянке. Для игры это обычное удаление, и
 * наивный триггер разрушения сработал бы при каждой сборке корабля. То же
 * верно для поршней и сборки контрапций Create.
 *
 * <p>Решение: у блока есть постоянный UUID в NBT, переживающий переезд. При
 * удалении событие не выполняется сразу, а встаёт в очередь на
 * {@link #window()} тиков. Если за это время блок с тем же UUID объявится
 * где-то ещё — это был переезд, и событие отменяется.
 *
 * <p>Окно считается в тиках, а не в реальном времени, поэтому просадка TPS на
 * работу схемы не влияет: удаление и появление копии происходят в одном и том
 * же тиковом цикле.
 *
 * <p>Схема намеренно устойчива к порядку событий: если появление копии
 * опередит удаление оригинала, совпадение всё равно будет распознано — для
 * этого хранится и память о недавних появлениях.
 *
 * <p>При любой неоднозначности схема молчит. Пропущенное событие — досадно;
 * ложный взрыв при каждой сборке корабля — сломанная механика.
 */
public final class DestructionArbiter {

    /**
     * Сколько тиков ждать подтверждения переезда.
     *
     * <p>Измерено на живом сервере 2026-08-02: сборка конструкции даёт зазор в
     * 0 тиков, разборка — 1 тик. Пять оставлено как пятикратный запас. Если
     * сборка очень крупного корабля окажется размазанной по тикам, значение
     * надо поднять — фактический зазор пишется в лог при каждом распознанном
     * переезде, так что число подбирается по данным, а не на глаз.
     */
    public static final int DEFAULT_WINDOW_TICKS = 5;

    /** Окно из {@code core.arbiter_window_ticks}: подбирается по журналу, поэтому вынесено в настройку. */
    public static int window() {
        return Math.max(1, PerformanceConfig.of("core").integer("arbiter_window_ticks"));
    }

    private static final Map<UUID, PendingRemoval> pendingRemovals = new HashMap<>();
    private static final Map<UUID, Integer> recentAppearances = new HashMap<>();

    private static int currentTick;

    private DestructionArbiter() {
    }

    /**
     * Что известно об удалённом блоке. Мировая позиция снимается в момент
     * удаления: позже конструкция сдвинется и точка гибели будет потеряна.
     */
    public record PendingRemoval(
            UUID blockId,
            ResourceKey<Level> dimension,
            BlockPos rawPos,
            Vec3 worldPos,
            boolean wasOnSubLevel,
            int removedAtTick,
            Consumer<PendingRemoval> onConfirmedDestroyed
    ) {
    }

    /** Блок удалён. Событие выполнится, только если удаление подтвердится. */
    public static void onRemoved(PendingRemoval removal) {
        Integer appearedAt = recentAppearances.remove(removal.blockId());
        if (appearedAt != null) {
            // Копия объявилась раньше, чем исчез оригинал — это переезд.
            Gimpanum.LOGGER.info("[арбитр] {} — переезд (копия опередила удаление на {} тиков)",
                    removal.blockId(), currentTick - appearedAt);
            return;
        }
        pendingRemovals.put(removal.blockId(), removal);
    }

    /** Блок с таким UUID появился в мире. Отменяет ожидающее удаление. */
    public static void onAppeared(UUID blockId) {
        PendingRemoval pending = pendingRemovals.remove(blockId);
        if (pending != null) {
            Gimpanum.LOGGER.info("[арбитр] {} — переезд подтверждён через {} тиков (окно {})",
                    blockId, currentTick - pending.removedAtTick(), window());
            return;
        }
        recentAppearances.put(blockId, currentTick);
    }

    /** Вызывается раз в тик сервера: добивает истёкшие ожидания. */
    public static void tick(MinecraftServer server) {
        currentTick++;
        int window = window();

        if (!pendingRemovals.isEmpty()) {
            List<PendingRemoval> confirmed = new ArrayList<>();
            pendingRemovals.values().removeIf(pending -> {
                if (currentTick - pending.removedAtTick() >= window) {
                    confirmed.add(pending);
                    return true;
                }
                return false;
            });

            for (PendingRemoval pending : confirmed) {
                ServerLevel level = server.getLevel(pending.dimension());
                if (level == null) {
                    Gimpanum.LOGGER.warn("[арбитр] {} — измерение {} недоступно, событие пропущено",
                            pending.blockId(), pending.dimension().location());
                    continue;
                }
                Gimpanum.LOGGER.info("[арбитр] {} — уничтожение подтверждено, мировая позиция {}",
                        pending.blockId(), pending.worldPos());
                try {
                    pending.onConfirmedDestroyed().accept(pending);
                } catch (Throwable t) {
                    // Последствия выполняются внутри серверного тика: взрыв на
                    // физической конструкции идёт через чужие миксины, и одно
                    // исключение оттуда положило бы весь тик, а с ним сервер.
                    Gimpanum.LOGGER.error("Последствия гибели Ядра {} прерваны ошибкой",
                            pending.blockId(), t);
                }
            }
        }

        // Проверка на пустоту не лишняя: тик идёт двадцать раз в секунду, а обе
        // памяти почти всегда пусты, и removeIf каждый раз заводил бы итератор.
        if (!recentAppearances.isEmpty()) {
            recentAppearances.values().removeIf(tick -> currentTick - tick >= window);
        }
    }

    /** Сброс при остановке сервера: незавершённые ожидания не переносим. */
    public static void reset() {
        pendingRemovals.clear();
        recentAppearances.clear();
        currentTick = 0;
    }

    /** Сколько удалений и появлений ждёт подтверждения — для {@code /gimpanum stats}. */
    public static int pending() {
        return pendingRemovals.size() + recentAppearances.size();
    }

    public static int currentTick() {
        return currentTick;
    }
}
