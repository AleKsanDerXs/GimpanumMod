package com.kirbornu.gimpanum.structure;

import com.kirbornu.gimpanum.Gimpanum;
import com.kirbornu.gimpanum.config.JsonConfig;
import com.kirbornu.gimpanum.dimension.NebulaPortal;
import com.kirbornu.gimpanum.emission.EmissionKind;
import com.kirbornu.gimpanum.emission.Emissions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.VanillaGameEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Крикуны скалка в городах Примо.
 *
 * <p>Как в Древнем городе: датчики слышат шаги и шум, крикуны кричат, и каждый
 * крик накрывает всех вокруг Тьмой. Только будят они не Хранителя, а Примо:
 * несколько криков от одного игрока за короткое время — и на него начинается
 * Кошмар Спящего Бога. Сам Поглотитель по-прежнему приходит только с этим
 * выбросом, город лишь зовёт его.
 *
 * <p>Крикуны ставятся шаблоном без права призыва, поэтому ванильный
 * счётчик предупреждений Хранителя они не трогают — считаем сами. Кричит любой
 * крикун в Гимпануме: других, кроме городских, там не бывает, а поставленный
 * игроком — такой же шум.
 */
@EventBusSubscriber(modid = Gimpanum.MOD_ID)
public final class PrimoCity {

    /** Как далеко от крикуна накрывает Тьмой — как у ванильного. */
    private static final double DARKNESS_RADIUS = 40.0;

    /** Когда этот игрок будил крикунов — по игровому времени. */
    private static final Map<UUID, Deque<Long>> SHRIEKS = new HashMap<>();

    private PrimoCity() {
    }

    @SubscribeEvent
    public static void onGameEvent(VanillaGameEvent event) {
        if (!event.getVanillaEvent().is(GameEvent.SHRIEK)
                || !(event.getLevel() instanceof ServerLevel level)
                || !NebulaPortal.GIMPANUM.equals(level.dimension())
                || !(event.getCause() instanceof ServerPlayer culprit)) {
            return;
        }
        JsonConfig.Section config = StructureConfig.of(ClotKind.PRIMO_CITY.id());
        int darkness = config.integer("darkness_seconds") * 20;
        for (ServerPlayer player : level.players()) {
            if (player.position().distanceTo(event.getEventPosition()) <= DARKNESS_RADIUS) {
                player.addEffect(new MobEffectInstance(MobEffects.DARKNESS, darkness, 0, false, false));
            }
        }

        long now = level.getGameTime();
        long window = config.integer("shriek_memory_minutes") * 1200L;
        // Очереди давно ушедших игроков не копятся: чистим устаревшие при каждом крике.
        SHRIEKS.values().removeIf(queue -> queue.isEmpty() || now - queue.peekLast() > window);
        Deque<Long> times = SHRIEKS.computeIfAbsent(culprit.getUUID(), id -> new ArrayDeque<>());
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > window) {
            times.pollFirst();
        }
        if (times.size() >= config.integer("shrieks_to_wake")) {
            times.clear();
            Emissions.start(level, EmissionKind.SLEEPING_GOD, List.of(culprit));
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SHRIEKS.clear();
    }
}
