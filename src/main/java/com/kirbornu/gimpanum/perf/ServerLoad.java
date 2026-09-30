package com.kirbornu.gimpanum.perf;

import net.minecraft.server.MinecraftServer;

/** Защита от перегрузки: необязательные затеи ждут, пока сервер не отдышится. */
public final class ServerLoad {

    private ServerLoad() {
    }

    /** Средний тик дольше порога из {@code load_guard.skip_above_mspt}. */
    public static boolean overloaded(MinecraftServer server) {
        double limit = PerformanceConfig.of("load_guard").number("skip_above_mspt");
        return limit > 0.0 && server.getAverageTickTimeNanos() / 1_000_000.0 > limit;
    }
}
