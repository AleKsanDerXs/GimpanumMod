package com.kirbornu.gimpanum.perf;

import com.kirbornu.gimpanum.Gimpanum;
import com.kirbornu.gimpanum.config.JsonConfig;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

import java.nio.file.Path;

/**
 * Настройки производительности — {@code config/gimpanum/performance.json}.
 *
 * <p>Встроенные значения повторяют оригинальное поведение мода, поэтому без
 * файла ничего не меняется. Рекомендованные числа лежат отдельно, в
 * {@code recommended_config}.
 */
@EventBusSubscriber(modid = Gimpanum.MOD_ID)
public final class PerformanceConfig {

    private static final JsonConfig CONFIG =
            new JsonConfig("performance.json", "/data/gimpanum/performance/default_performance.json");

    private PerformanceConfig() {
    }

    public static JsonConfig.Section of(String name) {
        return CONFIG.section(name);
    }

    public static Path path() {
        return CONFIG.path();
    }

    public static int count() {
        return CONFIG.sections().size();
    }

    // Числа с горячих путей (события на каждую сущность и каждый тик): читаются
    // из файла один раз при загрузке, а не на каждый вызов.
    private static volatile int necrophageInterval = 1;
    private static volatile boolean vacuumEnabled = true;
    private static volatile int vacuumInterval = 10;
    private static volatile int vacuumRadius = 32;

    public static void load() {
        CONFIG.load();
        necrophageInterval = Math.max(1, CONFIG.section("necrophage").integer("target_check_interval_ticks"));
        JsonConfig.Section vacuum = CONFIG.section("vacuum");
        vacuumEnabled = vacuum.bool("enabled");
        vacuumInterval = Math.max(1, vacuum.integer("interval_ticks"));
        vacuumRadius = Math.max(1, vacuum.integer("radius_chunks"));
    }

    public static int necrophageInterval() {
        return necrophageInterval;
    }

    public static boolean vacuumEnabled() {
        return vacuumEnabled;
    }

    public static int vacuumInterval() {
        return vacuumInterval;
    }

    public static int vacuumRadius() {
        return vacuumRadius;
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        load();
    }
}
