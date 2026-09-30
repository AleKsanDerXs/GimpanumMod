package com.kirbornu.gimpanum.structure;

import com.kirbornu.gimpanum.Gimpanum;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Отложенные действия ловушек: волна увядания, серия взрывов.
 *
 * <p>Живут только в памяти: ловушка, сработавшая перед остановкой сервера,
 * после перезапуска не доигрывается.
 */
@EventBusSubscriber(modid = Gimpanum.MOD_ID)
public final class Later {

    private record Task(ResourceKey<Level> dimension, long at, Consumer<ServerLevel> action) {
    }

    private static final List<Task> TASKS = new ArrayList<>();

    private Later() {
    }

    /** Сколько отложенных действий ждёт своего часа — для {@code /gimpanum stats}. */
    public static int size() {
        return TASKS.size();
    }

    /** Сделать это через столько тиков в этом измерении. */
    public static void run(ServerLevel level, int delay, Consumer<ServerLevel> action) {
        TASKS.add(new Task(level.dimension(), level.getGameTime() + delay, action));
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || TASKS.isEmpty()) {
            return;
        }
        // Сперва собрать созревшие, потом выполнить: действие вправе
        // запланировать следующее, и список не должен меняться под обходом.
        List<Task> due = new ArrayList<>();
        Iterator<Task> it = TASKS.iterator();
        while (it.hasNext()) {
            Task task = it.next();
            if (task.dimension().equals(level.dimension()) && task.at() <= level.getGameTime()) {
                due.add(task);
                it.remove();
            }
        }
        // Исключение в одной задаче не должно ни ронять тик мира, ни съедать остальные.
        for (Task task : due) {
            try {
                task.action().accept(level);
            } catch (Throwable failure) {
                Gimpanum.LOGGER.error("Отложенное действие ловушки упало", failure);
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        TASKS.clear();
    }
}
