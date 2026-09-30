package com.kirbornu.gimpanum.dimension;

import com.kirbornu.gimpanum.Gimpanum;
import com.kirbornu.gimpanum.config.JsonConfig;
import com.kirbornu.gimpanum.perf.ChunkLoads;
import com.kirbornu.gimpanum.perf.PerformanceConfig;
import com.kirbornu.gimpanum.registry.GimpanumContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Куда и как переносит портальная арка.
 *
 * <p>Выход всегда случайный, и — главное — он не обязан быть уже посещённым.
 * Размещение структур в игре подчиняется сетке: зная зерно мира и параметры
 * сетки, чанк-кандидат вычисляется без всякой загрузки. Мы выбираем случайную
 * ячейку этой сетки, дотягиваем её чанк до готового вида и ищем в нём
 * настоящий блок портала.
 *
 * <p>Так было не всегда: раньше выход искался поиском ближайшей структуры,
 * который возвращает <i>начало</i> структуры — угол её чанка на нулевой
 * высоте. Отсюда и брались переносы в коренную породу.
 *
 * <p>Границу мира проверяем на каждом шаге: ячейки сетки берём только из-под
 * неё, найденную арку сверяем отдельно (структура шире своего чанка и может
 * заходить за черту), площадку — целым блоком, а не углом. Сетка размещения о
 * границе не знает и спокойно предложит ячейку за ней; выйти оттуда игрок уже
 * не сможет.
 */
@EventBusSubscriber(modid = Gimpanum.MOD_ID)
public final class NebulaPortal {

    public static final ResourceKey<Level> GIMPANUM =
            ResourceKey.create(Registries.DIMENSION, Gimpanum.id("gimpanum"));

    public static final ResourceKey<Structure> STRUCTURE =
            ResourceKey.create(Registries.STRUCTURE, Gimpanum.id("nebula_portal"));

    private static final ResourceKey<StructureSet> STRUCTURE_SET =
            ResourceKey.create(Registries.STRUCTURE_SET, Gimpanum.id("nebula_portal"));

    /** Радиус поиска площадки у выхода. */
    private static final int FOOTING_RADIUS = 8;

    /**
     * Насколько далеко от плоскости перехода ставить пришедшего.
     *
     * <p>Три блока, а не один. Раньше площадка искалась с ближайшего кольца, и
     * вышедший оказывался вплотную к плоскости, внутри самой арки: шаг в любую
     * сторону — и он снова в портале. Три блока выносят его на лестницу, за
     * пределы арки.
     */
    private static final int MIN_GAP = 3;

    /** Сколько клеток плоскости обходить, собирая её целиком: в арке их полсотни. */
    private static final int PLANE_LIMIT = 128;

    /** Кто сейчас ждёт перехода: повторный вход в арку не должен запускать второй поиск. */
    private static final Set<UUID> PENDING = new HashSet<>();

    private NebulaPortal() {
    }

    /** Куда ведёт портал из этого измерения: Гимпанум и верхний мир — пара. */
    public static ResourceKey<Level> other(ResourceKey<Level> from) {
        return GIMPANUM.equals(from) ? Level.OVERWORLD : GIMPANUM;
    }

    /** Сколько переходов сейчас ждут подгрузки чанков — для {@code /gimpanum stats}. */
    public static int pending() {
        return PENDING.size();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PENDING.clear();
    }

    /**
     * Переносит существо к порталу на той стороне.
     *
     * <p>Поиск выхода <b>не блокирует сервер</b>. Раньше здесь стояло до
     * двенадцати синхронных {@code getChunk(FULL)} — каждое означало генерацию
     * чанка на главном потоке, и переход через арку подвешивал весь сервер на
     * секунды. Теперь нужные чанки заказываются через {@link ChunkLoads}, игра
     * генерирует их на рабочих потоках, а сам переход завершается в том тике,
     * когда всё готово. Если все чанки уже в памяти, переход мгновенный.
     *
     * <p>Выход по-прежнему случайный, но {@code portal.explore_chance} позволяет
     * чаще брать уже известные порталы (проверенные на живость) и не гнать
     * генерацию мира на десятки тысяч блоков от центра при каждом входе.
     */
    public static void teleport(ServerLevel from, Entity entity) {
        MinecraftServer server = from.getServer();
        ResourceKey<Level> targetKey = other(from.dimension());
        ServerLevel target = server.getLevel(targetKey);
        if (target == null) {
            Gimpanum.LOGGER.error("Портал: измерение {} не найдено", targetKey.location());
            return;
        }
        teleport(from, target, entity);
    }

    /** То же с явным измерением назначения: пара «Гимпанум — верхний мир» зашита в {@link #other}, а тестам нужен произвольный мир. */
    public static void teleport(ServerLevel from, ServerLevel target, Entity entity) {
        if (!PENDING.add(entity.getUUID())) {
            return;
        }
        entity.setPortalCooldown();
        Search search = new Search(from.getServer(), target, entity, from.dimension());
        try {
            search.start();
        } catch (Throwable failure) {
            search.fail("исключение при старте поиска", failure);
        }
    }

    /** Один переход: цепочка асинхронных шагов, сходящаяся к {@code finish}. */
    private static final class Search {
        private final MinecraftServer server;
        private final ServerLevel target;
        private final UUID entityId;
        private final ResourceKey<Level> fromKey;
        private final RandomSource random;
        private final JsonConfig.Section config = PerformanceConfig.of("portal");
        private final long deadlineMillis;
        private final List<BlockPos> triedKnown = new ArrayList<>();
        private int attempts;
        private int validations;

        Search(MinecraftServer server, ServerLevel target, Entity entity, ResourceKey<Level> fromKey) {
            this.server = server;
            this.target = target;
            this.entityId = entity.getUUID();
            this.fromKey = fromKey;
            this.random = entity.getRandom();
            this.deadlineMillis = System.currentTimeMillis() + Math.max(1, config.integer("wait_seconds")) * 1000L;
            this.attempts = Math.max(0, config.integer("max_attempts"));
            this.validations = Math.max(1, config.integer("validate_attempts"));
            if (entity instanceof ServerPlayer player) {
                say(player, "gimpanum.portal.opening");
            }
        }

        void start() {
            List<BlockPos> known = known();
            boolean explore = known.isEmpty() || random.nextDouble() < config.number("explore_chance");
            if (explore) {
                explore();
            } else {
                validateKnown();
            }
        }

        private boolean expired() {
            return System.currentTimeMillis() > deadlineMillis;
        }

        /** Известные порталы этого измерения, что ещё не проверялись и лежат внутри границы. */
        private List<BlockPos> known() {
            WorldBorder border = target.getWorldBorder();
            List<BlockPos> found = new ArrayList<>();
            for (BlockPos pos : PortalIndex.in(server, target.dimension())) {
                if (!triedKnown.contains(pos) && inside(border, pos)) {
                    found.add(pos);
                }
            }
            return found;
        }

        /** Шаг поиска новой арки: кандидат сетки, его начало, чанки структуры, блок портала. */
        private void explore() {
            Optional<RandomSpreadStructurePlacement> placement = placement(target);
            Optional<Structure> structure = target.registryAccess()
                    .registryOrThrow(Registries.STRUCTURE).getOptional(STRUCTURE);
            if (placement.isEmpty() || structure.isEmpty() || attempts <= 0 || expired()) {
                validateKnown();
                return;
            }
            attempts--;

            WorldBorder border = target.getWorldBorder();
            int spacing = placement.get().spacing();
            int range = Math.max(1, config.integer("region_range"));
            ChunkPos candidate = placement.get().getPotentialStructureChunk(
                    target.getSeed(),
                    cell(border.getMinX(), border.getMaxX(), spacing, range, random) * spacing,
                    cell(border.getMinZ(), border.getMaxZ(), spacing, range, random) * spacing);
            if (!border.isWithinBounds(candidate)) {
                explore();
                return;
            }
            ChunkLoads.request(target, candidate, ok -> guarded(() -> {
                Optional<BoundingBox> box = ok ? startBox(candidate, structure.get()) : Optional.empty();
                if (box.isEmpty()) {
                    explore();
                    return;
                }
                ChunkLoads.request(target, chunksOf(box.get()), ready -> guarded(() -> {
                    Optional<BlockPos> portal = ready ? portalIn(box.get()) : Optional.empty();
                    if (portal.isPresent() && inside(border, portal.get())) {
                        arrive(portal.get());
                    } else {
                        explore();
                    }
                }));
            }));
        }

        /** Взять известный портал, убедившись, что арка на месте. Мёртвые записи из указателя уходят. */
        private void validateKnown() {
            List<BlockPos> known = known();
            if (known.isEmpty() || validations <= 0 || expired()) {
                fail("не нашлось ни одного выхода (известных: " + known.size() + ", проверок осталось: "
                        + validations + ", срок вышел: " + expired() + ")", null);
                return;
            }
            validations--;
            BlockPos pos = known.get(random.nextInt(known.size()));
            triedKnown.add(pos);
            ChunkLoads.request(target, ring(pos), ok -> guarded(() -> {
                if (ok && target.getBlockState(pos).is(GimpanumContent.NEBULA_PORTAL.get())) {
                    finish(pos);
                    return;
                }
                if (ok) {
                    PortalIndex.remove(server, target.dimension(), pos);
                }
                validateKnown();
            }));
        }

        /** Выход найден; осталось убедиться, что вокруг него загружены чанки для площадки. */
        private void arrive(BlockPos portal) {
            ChunkLoads.request(target, ring(portal), ok -> guarded(() -> {
                if (ok) {
                    finish(portal);
                } else {
                    fail("не дождались чанков выхода", null);
                }
            }));
        }

        private void finish(BlockPos portal) {
            Entity entity = entity();
            PENDING.remove(entityId);
            if (entity == null) {
                return;
            }
            List<BlockPos> plane = plane(target, portal);
            BlockPos spot = footing(target, plane);
            Gimpanum.LOGGER.debug("Портал: выход {}, подножие плоскости {}, площадка {}",
                    portal, anchor(plane), spot);
            entity.setPortalCooldown();
            entity.teleportTo(target, spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5,
                    Set.of(), facingAway(plane, spot), entity.getXRot());
        }

        /** Тот, кого переносим, если он ещё в игре. */
        private Entity entity() {
            ServerPlayer player = server.getPlayerList().getPlayer(entityId);
            if (player != null) {
                return player.isAlive() && !player.hasDisconnected() ? player : null;
            }
            ServerLevel from = server.getLevel(fromKey);
            Entity found = from == null ? null : from.getEntity(entityId);
            return found != null && found.isAlive() && !found.isRemoved() ? found : null;
        }

        private void guarded(Runnable step) {
            try {
                step.run();
            } catch (Throwable failure) {
                fail("исключение в шаге поиска", failure);
            }
        }

        void fail(String reason, Throwable failure) {
            PENDING.remove(entityId);
            if (failure != null) {
                Gimpanum.LOGGER.error("Портал: {}", reason, failure);
            } else {
                Gimpanum.LOGGER.warn("Портал: в {} {}", target.dimension().location(), reason);
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entityId);
            if (player != null) {
                say(player, "gimpanum.portal.failed");
            }
        }

        private static void say(ServerPlayer player, String key) {
            player.displayClientMessage(Component.translatable(key), true);
        }

        /** Габарит начала структуры в этом чанке, если оно есть и годится. Чанк уже загружен. */
        private Optional<BoundingBox> startBox(ChunkPos candidate, Structure structure) {
            LevelChunk chunk = target.getChunkSource().getChunkNow(candidate.x, candidate.z);
            if (chunk == null) {
                return Optional.empty();
            }
            StructureStart start = target.structureManager()
                    .getStartForStructure(SectionPos.bottomOf(chunk), structure, chunk);
            return start == null || !start.isValid() ? Optional.empty() : Optional.of(start.getBoundingBox());
        }

        /** Блок портала внутри габарита. Все его чанки уже загружены. */
        private Optional<BlockPos> portalIn(BoundingBox box) {
            for (ChunkPos pos : chunksOf(box)) {
                LevelChunk piece = target.getChunkSource().getChunkNow(pos.x, pos.z);
                if (piece == null) {
                    continue;
                }
                for (BlockPos blockPos : piece.getBlockEntitiesPos()) {
                    if (box.isInside(blockPos)
                            && piece.getBlockState(blockPos).is(GimpanumContent.NEBULA_PORTAL.get())) {
                        return Optional.of(blockPos.immutable());
                    }
                }
            }
            return Optional.empty();
        }
    }

    /** Чанки 3×3 вокруг точки: площадке хватает блоков в радиусе восьми. */
    private static List<ChunkPos> ring(BlockPos pos) {
        ChunkPos centre = new ChunkPos(pos);
        List<ChunkPos> chunks = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                chunks.add(new ChunkPos(centre.x + dx, centre.z + dz));
            }
        }
        return chunks;
    }

    private static List<ChunkPos> chunksOf(BoundingBox box) {
        List<ChunkPos> chunks = new ArrayList<>();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                chunks.add(new ChunkPos(cx, cz));
            }
        }
        return chunks;
    }

    private static int cell(double min, double max, int spacing, int range, RandomSource random) {
        int low = Math.max(-range, Math.floorDiv(Mth.floor(min) >> 4, spacing));
        int high = Math.min(range, Math.floorDiv((Mth.ceil(max) - 1) >> 4, spacing));
        return high < low ? low : low + random.nextInt(high - low + 1);
    }

    private static boolean inside(WorldBorder border, BlockPos pos) {
        return border.isWithinBounds(new AABB(pos));
    }

    private static BlockPos clampInside(WorldBorder border, BlockPos pos) {
        int x = Mth.clamp(pos.getX(), Mth.ceil(border.getMinX()), Mth.floor(border.getMaxX()) - 1);
        int z = Mth.clamp(pos.getZ(), Mth.ceil(border.getMinZ()), Mth.floor(border.getMaxZ()) - 1);
        return new BlockPos(x, pos.getY(), z);
    }

    private static Optional<RandomSpreadStructurePlacement> placement(ServerLevel target) {
        return target.registryAccess().registryOrThrow(Registries.STRUCTURE_SET)
                .getOptional(STRUCTURE_SET)
                .map(StructureSet::placement)
                .filter(RandomSpreadStructurePlacement.class::isInstance)
                .map(RandomSpreadStructurePlacement.class::cast);
    }

    /**
     * Вся плоскость перехода, а не одна её клетка.
     *
     * <p>Клетку нам дают какую придётся — какая первой попалась в списке
     * блок-сущностей чанка, хоть самую верхнюю. Отмерять от неё три блока
     * бессмысленно: три блока вниз от верхнего края плоскости — это по-прежнему
     * плоскость. Поэтому обходим её целиком и меряем расстояние до ближайшей
     * клетки.
     */
    private static List<BlockPos> plane(ServerLevel target, BlockPos start) {
        List<BlockPos> found = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start.immutable());
        seen.add(start.immutable());
        while (!queue.isEmpty() && found.size() < PLANE_LIMIT) {
            BlockPos pos = queue.poll();
            if (!target.getBlockState(pos).is(GimpanumContent.NEBULA_PORTAL.get())) {
                continue;
            }
            found.add(pos);
            for (Direction side : Direction.values()) {
                BlockPos next = pos.relative(side);
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return found.isEmpty() ? List.of(start.immutable()) : found;
    }

    /**
     * Куда поставить пришедшего: твёрдая площадка поодаль от арки.
     *
     * <p>Ищем, а не отсчитываем на глазок: арку разворачивает мироген, и
     * постоянного смещения «на три блока в сторону» не существует. Зато есть
     * требование — не ближе {@value #MIN_GAP} блоков к любой клетке плоскости.
     * Если такого места не нашлось, требование ослабляем по шагу: оказаться
     * вплотную всё же лучше, чем повиснуть над аркой.
     */
    private static BlockPos footing(ServerLevel target, List<BlockPos> plane) {
        BlockPos centre = anchor(plane);
        for (int gap = MIN_GAP; gap >= 1; gap--) {
            // Сперва перебираем всё на одном уровне и лишь потом спускаемся:
            // выйти в трёх шагах от арки лучше, чем в двух, но восемью
            // блоками ниже — по такой площадке ещё карабкаться обратно.
            for (int dy = 0; dy >= -FOOTING_RADIUS; dy--) {
                for (int radius = gap; radius <= FOOTING_RADIUS; radius++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                                continue;
                            }
                            BlockPos candidate = centre.offset(dx, dy, dz);
                            if (distance(plane, candidate) < gap) {
                                continue;
                            }
                            if (standable(target, candidate)
                                    && inside(target.getWorldBorder(), candidate)) {
                                return candidate;
                            }
                        }
                    }
                }
            }
        }
        // Не нашлось вовсе — ставим над аркой: упасть лучше, чем застрять в
        // плоскости. Но и тут под границей: арку она может резать пополам.
        BlockPos above = centre.above(3);
        return inside(target.getWorldBorder(), above) ? above : clampInside(target.getWorldBorder(), above);
    }

    /**
     * Подножие плоскости — её нижний ряд, середина.
     *
     * <p>Отмерять от какой попало клетки нельзя: список приходит от обхода, а
     * тот начинается с той клетки, которую первой выдал чанк, — легко с самой
     * верхней. От верхней «ноль по высоте» — это крыша арки, и пришедший
     * оказывался на ней. От подножия ноль — это пол перед аркой.
     */
    private static BlockPos anchor(List<BlockPos> plane) {
        int floor = Integer.MAX_VALUE;
        for (BlockPos cell : plane) {
            floor = Math.min(floor, cell.getY());
        }
        long x = 0;
        long z = 0;
        int count = 0;
        for (BlockPos cell : plane) {
            if (cell.getY() == floor) {
                x += cell.getX();
                z += cell.getZ();
                count++;
            }
        }
        return new BlockPos((int) (x / count), floor, (int) (z / count));
    }

    /** Расстояние по клеткам до ближайшей клетки плоскости. */
    private static int distance(List<BlockPos> plane, BlockPos pos) {
        int nearest = Integer.MAX_VALUE;
        for (BlockPos cell : plane) {
            int d = Math.max(Math.max(Math.abs(cell.getX() - pos.getX()), Math.abs(cell.getY() - pos.getY())),
                    Math.abs(cell.getZ() - pos.getZ()));
            nearest = Math.min(nearest, d);
        }
        return nearest;
    }

    /**
     * Развернуть пришедшего спиной к арке.
     *
     * <p>Мелочь, но именно она решает: тот, кто вышел лицом в плоскость,
     * шагает вперёд не глядя и уезжает обратно.
     */
    private static float facingAway(List<BlockPos> plane, BlockPos spot) {
        double x = 0.0;
        double z = 0.0;
        for (BlockPos cell : plane) {
            x += cell.getX();
            z += cell.getZ();
        }
        double dx = spot.getX() - x / plane.size();
        double dz = spot.getZ() - z / plane.size();
        if (dx == 0.0 && dz == 0.0) {
            return 0.0F;
        }
        return (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
    }

    private static boolean standable(ServerLevel target, BlockPos pos) {
        return target.getBlockState(pos).isAir()
                && target.getBlockState(pos.above()).isAir()
                && target.getBlockState(pos.below()).isSolidRender(target, pos.below());
    }
}
