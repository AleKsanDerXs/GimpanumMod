package com.kirbornu.gimpanum.entity;

import com.kirbornu.gimpanum.config.JsonConfig;
import com.kirbornu.gimpanum.Gimpanum;
import com.kirbornu.gimpanum.dimension.NebulaPortal;
import com.kirbornu.gimpanum.perf.ServerLoad;
import com.kirbornu.gimpanum.registry.GimpanumContent;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Заселение Гимпанума — своё, вместо ванильного.
 *
 * <p>Ванильный заселитель для такого измерения не годится в двух местах сразу,
 * и обойти это настройкой биома нельзя.
 *
 * <p><b>Первое — общий предел.</b> Игра держит не больше 70 враждебных мобов на
 * 289 чанков, то есть примерно четверть моба на чанк, и делит их между всеми
 * записями биома пропорционально весу. «Один призрак на чанк» — вчетверо больше
 * всего этого запаса, и никакими весами такого не добиться: вес решает, кому
 * достанется доля, а не насколько велик пирог.
 *
 * <p><b>Второе — выбор высоты.</b> Точка для попытки берётся на случайной высоте
 * от дна мира до поверхности. В Гимпануме под барханами лежит лабиринт в
 * полсотни блоков, поэтому почти каждая точка приходится на подземелье: попытки
 * поставить Ходока на поверхность проваливались одна за другой, а Призраку
 * годилась любая, и он выедал долю остальных. Отсюда и то, что видно в игре, —
 * пустая пустыня и битком набитый лабиринт.
 *
 * <p>Поэтому список мобов в биоме пуст, а расселением занимается этот класс.
 * Он идёт по чанкам вокруг игрока, считает, кто там уже есть, и добирает до
 * заданной плотности — то есть задаёт плотность прямо, а не через доли общего
 * предела. Плотность каждого моба и общие ручки — радиус, частота, отступ от
 * игрока, предел за заход — берутся из {@link MobStats}, разделы
 * {@code spawn_per_chunk} и {@code spawner}. Предел за заход нужен, чтобы
 * население набиралось за несколько секунд, а не одним рывком.
 *
 * <p>Убирает мобов по-прежнему игра: всё, что отошло от игрока дальше 128
 * блоков, исчезает само. Поэтому население держится около заданного и не растёт
 * бесконечно.
 */
@EventBusSubscriber(modid = Gimpanum.MOD_ID)
public final class GimpanumSpawner {

    /** Попыток найти место под одного моба. */
    private static final int TRIES = 3;

    /** Запас от кромки барханов, ниже которого начинается лабиринт. */
    private static final int DEPTH = 7;

    /** Где живёт вид: на барханах, в лабиринте под ними или где угодно. */
    private enum Layer {
        SURFACE, DEPTHS, ANYWHERE;

        boolean admits(boolean surface) {
            return this == ANYWHERE || (this == SURFACE) == surface;
        }
    }

    /**
     * Вид, которого заселитель держит на заданной плотности.
     *
     * <p>Слой выбирается по самому игроку: стоящему на барханах достаются
     * Ходоки и Молнии, спустившемуся в лабиринт — Призраки. Это не поблажка
     * ради нагрузки, а то же самое, что и в замысле: Призрак живёт у дна и сам
     * туда возвращается ({@link com.kirbornu.gimpanum.entity.goal.SinkToDepthsGoal}),
     * а Ходок с бархана вниз не спускается. Держать полсотни Призраков под
     * ногами у того, кто гуляет по поверхности, значило бы тикать ими впустую.
     */
    private record Kind(DeferredHolder<EntityType<?>, ? extends EntityType<? extends Mob>> type,
                        Layer layer, Spot spot) {

        String name() {
            return type.getId().getPath();
        }
    }

    private static final List<Kind> KINDS = List.of(
            // Солдат заселитель не ставит: их приводит капитан.
            new Kind(GimpanumEntities.DUNE_CAPTAIN, Layer.SURFACE, GimpanumSpawner::dunes),
            new Kind(GimpanumEntities.PLASMA_BOLT, Layer.SURFACE, GimpanumSpawner::sky),
            new Kind(GimpanumEntities.COMET_WRAITH, Layer.DEPTHS, GimpanumSpawner::labyrinth),
            new Kind(GimpanumEntities.SPACE_DEVOURER, Layer.ANYWHERE, GimpanumSpawner::anywhere));

    private GimpanumSpawner() {
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        JsonConfig.Section spawner = MobStats.of("spawner");
        if (server.getTickCount() % Math.max(1, spawner.integer("period_ticks")) != 0) {
            return;
        }
        ServerLevel level = server.getLevel(NebulaPortal.GIMPANUM);
        if (level == null || level.players().isEmpty() || ServerLoad.overloaded(server)) {
            return;
        }
        int budget = spawner.integer("max_per_pass");
        int radius = spawner.integer("radius_chunks");
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            budget = populate(level, player.chunkPosition(), onSurface(level, player), radius, budget);
            if (budget <= 0) {
                return;
            }
        }
    }

    /**
     * Добрать население вокруг одного игрока.
     *
     * <p>Виды перебираются внутри чанка, а не чанки внутри вида: иначе Ходоки,
     * которых больше всех, выбирали бы весь предел захода, и Молнии ждали бы,
     * пока барханы заполнятся целиком.
     */
    private static int populate(ServerLevel level, ChunkPos centre, boolean surface, int radius, int budget) {
        AABB region = new AABB(
                (centre.x - radius) << 4, level.getMinBuildHeight(), (centre.z - radius) << 4,
                (centre.x + radius + 1) << 4, level.getMaxBuildHeight(), (centre.z + radius + 1) << 4);

        int side = 2 * radius + 1;
        int chunks = side * side;

        List<Kind> kinds = KINDS.stream()
                .filter(kind -> kind.layer().admits(surface))
                .filter(kind -> MobStats.of(kind.name()).number("spawn_per_chunk") > 0)
                .toList();

        // Сколько каждого вида положено каждому чанку. Считаем заранее: сумма
        // задаёт и предел на всю область, а не только ответ по каждому чанку.
        int[][] quota = new int[kinds.size()][chunks];
        Long2IntMap[] present = new Long2IntMap[kinds.size()];
        int[] room = new int[kinds.size()];
        for (int k = 0; k < kinds.size(); k++) {
            Kind kind = kinds.get(k);
            double density = MobStats.of(kind.name()).number("spawn_per_chunk");
            long salt = kind.name().hashCode();
            int sum = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int i = (dx + radius) * side + (dz + radius);
                    quota[k][i] = quota(level, centre.x + dx, centre.z + dz, density, salt);
                    sum += quota[k][i];
                }
            }
            present[k] = count(level, kind.type().get(), region);
            // Предел на всю область поверх предела на чанк. Без него население
            // медленно ползёт вверх: мобы расходятся по соседям, опустевший чанк
            // просит добавки, а ушедшие никуда не делись.
            room[k] = sum - total(present[k]);
        }

        // Потолок населения на игрока: считаются и солдаты, которых заселитель сам не ставит.
        int cap = MobStats.of("spawner").integer("max_per_player");
        if (cap > 0) {
            int have = count(level, GimpanumEntities.DUNE_WALKER.get(), region).values().intStream().sum();
            for (int k = 0; k < kinds.size(); k++) {
                have += total(present[k]);
            }
            if (have >= cap) {
                return budget;
            }
            budget = Math.min(budget, cap - have);
        }

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centre.x + dx;
                int cz = centre.z + dz;
                if (level.getChunkSource().getChunkNow(cx, cz) == null) {
                    continue;
                }
                long key = ChunkPos.asLong(cx, cz);
                int i = (dx + radius) * side + (dz + radius);
                for (int k = 0; k < kinds.size(); k++) {
                    if (budget <= 0) {
                        return 0;
                    }
                    int added = fill(level, cx, cz, kinds.get(k).type().get(),
                            Math.min(quota[k][i] - present[k].get(key), room[k]), budget, kinds.get(k).spot());
                    room[k] -= added;
                    budget -= added;
                }
            }
        }
        return budget;
    }

    /** Сколько всего мобов такого рода в области. */
    private static int total(Long2IntMap counts) {
        int sum = 0;
        IntIterator it = counts.values().iterator();
        while (it.hasNext()) {
            sum += it.nextInt();
        }
        return sum;
    }

    /** Ищет место, куда встаёт сущность такого размера, и возвращает его — или ничего. */
    @FunctionalInterface
    private interface Spot {
        @Nullable
        Vec3 find(ServerLevel level, int chunkX, int chunkZ, EntityType<?> type, RandomSource random);
    }

    /** Доставить в чанк недостающих мобов. Возвращает, сколько поставлено. */
    private static int fill(ServerLevel level, int chunkX, int chunkZ, EntityType<? extends Mob> type,
                            int missing, int budget, Spot spot) {
        int done = 0;
        RandomSource random = level.random;
        while (done < missing && done < budget) {
            Vec3 at = null;
            for (int attempt = 0; attempt < TRIES && at == null; attempt++) {
                at = spot.find(level, chunkX, chunkZ, type, random);
            }
            if (at == null || !spawn(level, type, at)) {
                break;
            }
            done++;
        }
        return done;
    }

    /**
     * Сколько мобов этого вида положено чанку.
     *
     * <p>Целая часть плотности — каждому чанку, дробная — доле чанков. Зерно
     * замешано из зерна мира и координат чанка, как это делает игра при
     * размещении структур: один и тот же чанк всегда даёт один и тот же ответ,
     * и «один на четыре чанка» означает ровно это, а не «с вероятностью
     * четверть каждый заход».
     */
    private static int quota(ServerLevel level, int chunkX, int chunkZ, double density, long salt) {
        int whole = (int) density;
        double part = density - whole;
        if (part <= 0.0) {
            return whole;
        }
        RandomSource random = RandomSource.create(
                chunkX * 341873128712L + chunkZ * 132897987541L + level.getSeed() + salt);
        return whole + (random.nextDouble() < part ? 1 : 0);
    }

    /** Кромка барханов: Ходоки. */
    @Nullable
    private static Vec3 dunes(ServerLevel level, int chunkX, int chunkZ, EntityType<?> type, RandomSource random) {
        int x = (chunkX << 4) + random.nextInt(16);
        int z = (chunkZ << 4) + random.nextInt(16);
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (!level.getBlockState(new BlockPos(x, y - 1, z)).is(GimpanumContent.COSMIC_SAND.get())) {
            return null;
        }
        return free(level, type, x + 0.5, y, z + 0.5);
    }

    /** Толща под барханами: Призраки. */
    @Nullable
    private static Vec3 labyrinth(ServerLevel level, int chunkX, int chunkZ, EntityType<?> type, RandomSource random) {
        int x = (chunkX << 4) + random.nextInt(16);
        int z = (chunkZ << 4) + random.nextInt(16);
        int floor = level.getMinBuildHeight() + 1;
        int ceiling = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - DEPTH,
                MobStats.of("comet_wraith").integer("max_y"));
        if (ceiling <= floor) {
            return null;
        }
        return free(level, type, x + 0.5, floor + random.nextInt(ceiling - floor), z + 0.5);
    }

    /** Небо над барханами: Молнии. */
    @Nullable
    private static Vec3 sky(ServerLevel level, int chunkX, int chunkZ, EntityType<?> type, RandomSource random) {
        int x = (chunkX << 4) + random.nextInt(16);
        int z = (chunkZ << 4) + random.nextInt(16);
        int floor = Math.max(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1, PlasmaBolt.FLOOR);
        int ceiling = Math.min(PlasmaBolt.CEILING, level.getMaxBuildHeight() - 1) - (int) Math.ceil(type.getHeight());
        if (ceiling <= floor) {
            return null;
        }
        return free(level, type, x + 0.5, floor + random.nextInt(ceiling - floor), z + 0.5);
    }

    /** Любая высота: Поглотители. */
    @Nullable
    private static Vec3 anywhere(ServerLevel level, int chunkX, int chunkZ, EntityType<?> type, RandomSource random) {
        int x = (chunkX << 4) + random.nextInt(16);
        int z = (chunkZ << 4) + random.nextInt(16);
        int floor = level.getMinBuildHeight() + 1;
        int ceiling = level.getMaxBuildHeight() - (int) Math.ceil(type.getHeight()) - 1;
        if (ceiling <= floor) {
            return null;
        }
        return free(level, type, x + 0.5, floor + random.nextInt(ceiling - floor), z + 0.5);
    }

    /**
     * Точка, если сущность туда помещается и там не стоит игрок.
     *
     * <p>Габарит собираем руками, а не берём готовый: у Поглотителя четыре
     * блока в ширину и три в высоту, и проверять его по одной клетке — то же
     * самое, что не проверять вовсе.
     */
    @Nullable
    private static Vec3 free(ServerLevel level, EntityType<?> type, double x, double y, double z) {
        double half = type.getWidth() / 2.0;
        AABB box = new AABB(x - half, y, z - half, x + half, y + type.getHeight(), z + half);
        if (!level.noCollision(box)) {
            return null;
        }
        int keepAway = MobStats.of("spawner").integer("min_distance_blocks");
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && player.distanceToSqr(x, y, z) < (double) keepAway * keepAway) {
                return null;
            }
        }
        return new Vec3(x, y, z);
    }

    private static boolean spawn(ServerLevel level, EntityType<? extends Mob> type, Vec3 at) {
        Mob mob = type.create(level);
        if (mob == null) {
            return false;
        }
        mob.moveTo(at.x, at.y, at.z, level.random.nextFloat() * 360.0F, 0.0F);
        // Через EventHooks, а не напрямую: так о появлении узнают чужие моды —
        // прямой вызов finalizeSpawn в NeoForge для того и объявлен устаревшим.
        EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(BlockPos.containing(at)),
                MobSpawnType.NATURAL, null);
        return level.addFreshEntity(mob);
    }

    /** Сколько мобов такого рода уже стоит в каждом чанке области. */
    private static Long2IntMap count(ServerLevel level, EntityType<? extends Mob> type, AABB region) {
        Long2IntOpenHashMap counts = new Long2IntOpenHashMap();
        for (Entity entity : level.getEntities(type, region, e -> true)) {
            counts.addTo(ChunkPos.asLong(entity.blockPosition()), 1);
        }
        return counts;
    }

    /** Игрок на барханах, а не в лабиринте под ними. */
    public static boolean onSurface(ServerLevel level, ServerPlayer player) {
        BlockPos pos = player.blockPosition();
        return pos.getY() >= level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ()) - DEPTH;
    }
}
