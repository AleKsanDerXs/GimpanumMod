package com.kirbornu.gimpanum.dimension;

import com.kirbornu.gimpanum.perf.PerformanceConfig;
import com.kirbornu.gimpanum.Gimpanum;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Чего не бывает в вакууме.
 *
 * <p>Два случая, и оба про воздух, которого в Гимпануме нет.
 *
 * <p>Первый: баллон Create заправляется вращением — компрессор набирает
 * воздух из окружающего мира. Набирать нечего, поэтому запас не растёт.
 * Обнулять его не нужно: привезённый полным баллон остаётся полным, даже
 * если его поставить.
 *
 * <p>Второй: Вентилятор в корпусе гонит воздух, которого нет. Дальность его
 * потока обнуляется, отчего он перестаёт и толкать, и обрабатывать предметы,
 * продолжая при этом вращаться — со стороны это выглядит как машина,
 * работающая вхолостую, и это ровно то, что должно происходить.
 *
 * <p>Ни одного класса Create тут не упомянуто: типы ищутся по имени в
 * реестре, поля и методы — рефлексией по найденному экземпляру. Без Create
 * обработчик просто ничего не находит.
 */
@EventBusSubscriber(modid = Gimpanum.MOD_ID)
public final class VacuumMachinery {

    private static final ResourceLocation BACKTANK = ResourceLocation.fromNamespaceAndPath("create", "backtank");
    private static final ResourceLocation ENCASED_FAN = ResourceLocation.fromNamespaceAndPath("create", "encased_fan");

    /** Осмотр раз в полсекунды: за это время компрессор успевает набрать немного, и мы это отнимаем. */
    private static final int SCAN_INTERVAL = 10;

    private static boolean typesResolved;
    private static BlockEntityType<?> backtankType;
    private static BlockEntityType<?> fanType;

    private static boolean tankFailed;
    private static Method getAirLevel;
    private static Method setAirLevel;

    private static boolean fanFailed;
    private static Method getAirCurrent;
    private static Field maxDistance;
    private static Field bounds;
    private static Field segments;
    private static Field kineticSpeed;

    /** Коробка нулевого размера: поток, который ничего не задевает. */
    private static final AABB NOTHING = new AABB(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);

    /** Сколько воздуха было в каждом баллоне при прошлом осмотре. */
    private static Map<BlockPos, Integer> lastSeen = new HashMap<>();

    private VacuumMachinery() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !PerformanceConfig.vacuumEnabled()
                || !NebulaPortal.GIMPANUM.equals(level.dimension())
                || level.getGameTime() % PerformanceConfig.vacuumInterval() != 0) {
            return;
        }
        resolveTypes();
        if (backtankType == null && fanType == null) {
            return;
        }

        LongSet chunks = LoadedBlockEntities.candidates(level, PerformanceConfig.vacuumRadius());
        if (chunks.isEmpty()) {
            if (!lastSeen.isEmpty()) {
                lastSeen = new HashMap<>();
            }
            return;
        }

        Map<BlockPos, Integer> current = new HashMap<>();
        LoadedBlockEntities.forEach(level, chunks, blockEntity -> {
            if (blockEntity.getType() == backtankType) {
                holdBacktank(level, blockEntity, current);
            } else if (blockEntity.getType() == fanType) {
                silenceFan(blockEntity);
            }
        });
        lastSeen = current;
    }

    // ── Баллон ──────────────────────────────────────────────────────────

    private static void holdBacktank(ServerLevel level, BlockEntity blockEntity, Map<BlockPos, Integer> current) {
        if (tankFailed) {
            return;
        }
        Integer air = airOf(blockEntity);
        if (air == null) {
            return;
        }
        BlockPos pos = blockEntity.getBlockPos().immutable();
        Integer before = lastSeen.get(pos);
        if (before != null && air > before) {
            setAir(blockEntity, before);
            air = before;
            vent(level, pos);
        }
        current.put(pos, air);
    }

    private static Integer airOf(BlockEntity blockEntity) {
        if (getAirLevel == null) {
            try {
                Class<?> owner = blockEntity.getClass();
                getAirLevel = owner.getMethod("getAirLevel");
                setAirLevel = owner.getMethod("setAirLevel", int.class);
            } catch (Throwable failure) {
                tankFailed = true;
                Gimpanum.LOGGER.warn("Не добрался до запаса воздуха в баллоне Create — "
                        + "в Гимпануме его можно будет заправлять", failure);
                return null;
            }
        }
        try {
            Object value = getAirLevel.invoke(blockEntity);
            return value instanceof Integer number ? number : null;
        } catch (Throwable failure) {
            tankFailed = true;
            return null;
        }
    }

    private static void setAir(BlockEntity blockEntity, int air) {
        try {
            setAirLevel.invoke(blockEntity, air);
        } catch (Throwable failure) {
            tankFailed = true;
        }
    }

    private static void vent(ServerLevel level, BlockPos pos) {
        // Не на каждый осмотр, иначе шипение сливается в сплошной шум.
        if (level.getGameTime() % 40 >= PerformanceConfig.vacuumInterval()) {
            return;
        }
        level.sendParticles(ParticleTypes.SMOKE,
                pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                3, 0.15, 0.05, 0.15, 0.01);
        level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.25F, 1.6F);
    }

    // ── Вентилятор ──────────────────────────────────────────────────────

    /**
     * Останавливает Вентилятор в корпусе.
     *
     * <p>Обнулить одну лишь дальность потока мало — так и вышло с первой
     * попытки: Вентилятор пересобирает поток по своему расписанию и оживал
     * между осмотрами. Поэтому режем в двух местах. Скорость вращения самого
     * блока ставим в ноль — тогда любая пересборка даёт пустой поток; и заодно
     * гасим уже собранный, обнуляя дальность, коробку и отрезки.
     *
     * <p>Со стороны это выглядит так: вал крутится, а Вентилятор стоит. Это и
     * есть нужное впечатление — лопастям не за что зацепиться.
     */
    private static void silenceFan(BlockEntity blockEntity) {
        if (fanFailed) {
            return;
        }
        try {
            if (getAirCurrent == null) {
                getAirCurrent = blockEntity.getClass().getMethod("getAirCurrent");
                Class<?> current = getAirCurrent.getReturnType();
                maxDistance = current.getField("maxDistance");
                bounds = current.getField("bounds");
                segments = current.getField("segments");
                kineticSpeed = findSpeedField(blockEntity.getClass());
                kineticSpeed.setAccessible(true);
            }
            kineticSpeed.setFloat(blockEntity, 0.0F);

            Object current = getAirCurrent.invoke(blockEntity);
            if (current != null) {
                maxDistance.setFloat(current, 0.0F);
                bounds.set(current, NOTHING);
                if (segments.get(current) instanceof List<?> list) {
                    list.clear();
                }
            }
        } catch (Throwable failure) {
            fanFailed = true;
            Gimpanum.LOGGER.warn("Не добрался до потока Вентилятора Create — "
                    + "в Гимпануме он будет дуть как ни в чём не бывало", failure);
        }
    }

    /** Поле скорости лежит в базовом кинетическом классе, не в самом Вентиляторе. */
    private static Field findSpeedField(Class<?> type) throws NoSuchFieldException {
        for (Class<?> cls = type; cls != null; cls = cls.getSuperclass()) {
            try {
                return cls.getDeclaredField("speed");
            } catch (NoSuchFieldException ignored) {
                // ищем выше по цепочке
            }
        }
        throw new NoSuchFieldException("speed");
    }

    private static void resolveTypes() {
        if (!typesResolved) {
            typesResolved = true;
            backtankType = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(BACKTANK);
            fanType = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(ENCASED_FAN);
        }
    }
}
