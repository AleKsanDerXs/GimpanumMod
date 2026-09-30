package com.kirbornu.gimpanum.emission;

import com.kirbornu.gimpanum.config.JsonConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Сколько осталось до следующего выброса.
 *
 * <p>Хранится в мире, чтобы перезапуск сервера не отодвигал выброс на новые
 * десять-сорок минут. Время идёт, только пока в Гимпануме кто-то есть, —
 * отсчитывает его {@link Emissions}.
 */
final class EmissionClock extends SavedData {

    private static final String FILE_NAME = "gimpanum_emissions";
    private static final String KEY_LEFT = "TicksLeft";

    private int left;

    private EmissionClock(int left) {
        this.left = left;
    }

    static EmissionClock get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(() -> new EmissionClock(interval(server.overworld().random)),
                        EmissionClock::load, null),
                FILE_NAME);
    }

    private static EmissionClock load(CompoundTag tag, HolderLookup.Provider registries) {
        return new EmissionClock(tag.getInt(KEY_LEFT));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt(KEY_LEFT, left);
        return tag;
    }

    /** Отсчитать тик и сказать, сколько осталось. */
    int tickDown() {
        left--;
        // Раз в десять секунд, а не каждый тик: иначе файл переписывается на каждом автосохранении
        // (при остановке сервера метка ставится отдельно, см. Emissions.onServerStopping).
        if (left % 200 == 0) {
            setDirty();
        }
        return left;
    }

    int left() {
        return left;
    }

    /** Назначить следующий выброс — случайно между краями из настройки. */
    void rewind(RandomSource random) {
        left = interval(random);
        setDirty();
    }

    void set(int ticks) {
        left = ticks;
        setDirty();
    }

    private static int interval(RandomSource random) {
        JsonConfig.Section schedule = EmissionConfig.of("schedule");
        int min = schedule.integer("interval_minutes_min") * 1200;
        int max = schedule.integer("interval_minutes_max") * 1200;
        return max > min ? min + random.nextInt(max - min + 1) : min;
    }
}
