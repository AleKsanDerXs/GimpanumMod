package com.kirbornu.gimpanum.core;

import com.kirbornu.gimpanum.Gimpanum;
import com.kirbornu.gimpanum.perf.ChunkLoads;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Указатель «имя Ядра → где оно находится и как настроено», переживающий
 * перезапуск сервера.
 *
 * <p>Существует потому, что адресовать Ядро координатами неудобно: у блока на
 * физической конструкции они выглядят как 20481032, и запомнить их невозможно.
 *
 * <p>Хранится на диске: посреди боя сервер может быть перезапущен, и без
 * сохранения все имена пропали бы до того, как подгрузятся чанки с кораблями.
 *
 * <p>Вместе с местом хранится и <b>снимок настройки</b>. Это не кэш ради
 * скорости, а условие работоспособности: Ядра уезжают на корабли, корабли
 * стоят в выгруженных чанках месяцами, а список для консоли нужен весь и
 * сразу. Читать настройку из блок-сущности означало бы грузить сотни чанков
 * ради одного открытия окна. Снимок обновляется при каждой правке настройки и
 * при загрузке Ядра, поэтому расходится с истиной он только в одну сторону —
 * пока Ядро выгружено, его настройку никто и не меняет.
 *
 * <p>Записи всё же могут разойтись с миром: Ядро переезжает при сборке
 * конструкции, его копируют схематикой, сносят сторонним модом. Поэтому поиск
 * всегда проверяет, что блок на месте, и чинит указатель, когда это не так.
 * Но чинит осторожно — см. {@link #find}.
 */
public final class CoreIndex extends SavedData {

    private static final String FILE_NAME = "gimpanum_cores";

    /**
     * Подстановка в образце имени: {@code tank+} задаёт все Ядра, чьё имя
     * начинается с {@code tank}.
     *
     * <p>Именно плюс, потому что Brigadier пропускает в неэкранированном слове
     * только буквы, цифры и {@code _-.+}. Звёздочка и скобки туда не проходят, а
     * подчёркивание и дефис слишком часто встречаются в самих именах.
     */
    public static final String WILDCARD = "+";

    public static final String DEFAULT_NAME_PREFIX = "core";

    private static final String KEY_CORES = "Cores";
    private static final String KEY_PREFIX = "DefaultNamePrefix";
    private static final String KEY_ID = "Id";
    private static final String KEY_NAME = "Name";
    private static final String KEY_DIMENSION = "Dimension";
    private static final String KEY_POS = "Pos";
    private static final String KEY_CONFIG = "Config";

    /**
     * Всё, что указатель знает о Ядре, не открывая его чанк.
     *
     * @param config снимок настройки на момент последней правки; у записей,
     *               сделанных прежними версиями мода, — {@link CoreConfig#EMPTY}
     */
    public record Snapshot(UUID id, String name, ResourceKey<Level> dimension, BlockPos pos,
                           CoreConfig config) {
    }

    private record Entry(String name, ResourceKey<Level> dimension, BlockPos pos, CoreConfig config) {
    }

    private final Map<UUID, Entry> byId = new HashMap<>();
    private final Map<String, UUID> byName = new HashMap<>();

    /** К нему приписываются номера при выдаче имён новым Ядрам. */
    private String defaultNamePrefix = DEFAULT_NAME_PREFIX;

    private CoreIndex() {
    }

    // --- Хранение ------------------------------------------------------------

    public static CoreIndex get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(CoreIndex::new, CoreIndex::load, null), FILE_NAME);
    }

    private static CoreIndex load(CompoundTag tag, HolderLookup.Provider registries) {
        CoreIndex index = new CoreIndex();
        ListTag list = tag.getList(KEY_CORES, Tag.TAG_COMPOUND);

        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation dimensionId = ResourceLocation.tryParse(entry.getString(KEY_DIMENSION));
            if (dimensionId == null || !entry.hasUUID(KEY_ID)) {
                continue;
            }
            UUID coreId = entry.getUUID(KEY_ID);
            String name = entry.getString(KEY_NAME);
            int[] pos = entry.getIntArray(KEY_POS);
            if (name.isEmpty() || pos.length != 3) {
                continue;
            }
            // Битый снимок настройки не повод терять запись о месте: без места
            // Ядро не найти вовсе, а настройка прочитается заново при загрузке.
            CoreConfig config = entry.contains(KEY_CONFIG)
                    ? CoreConfig.CODEC.parse(NbtOps.INSTANCE, entry.get(KEY_CONFIG))
                    .resultOrPartial(error -> Gimpanum.LOGGER.error(
                            "Снимок настройки Ядра '{}' в указателе повреждён: {}", name, error))
                    .orElse(CoreConfig.EMPTY)
                    : CoreConfig.EMPTY;

            index.byId.put(coreId, new Entry(name,
                    ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimensionId),
                    new BlockPos(pos[0], pos[1], pos[2]), config));
            index.byName.put(name, coreId);
        }
        if (tag.contains(KEY_PREFIX)) {
            index.defaultNamePrefix = tag.getString(KEY_PREFIX);
        }
        return index;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        byId.forEach((coreId, entry) -> {
            CompoundTag stored = new CompoundTag();
            stored.putUUID(KEY_ID, coreId);
            stored.putString(KEY_NAME, entry.name());
            stored.putString(KEY_DIMENSION, entry.dimension().location().toString());
            stored.putIntArray(KEY_POS,
                    new int[]{entry.pos().getX(), entry.pos().getY(), entry.pos().getZ()});
            CoreConfig.CODEC.encodeStart(NbtOps.INSTANCE, entry.config())
                    .resultOrPartial(error -> Gimpanum.LOGGER.error(
                            "Снимок настройки Ядра '{}' не сохранён: {}", entry.name(), error))
                    .ifPresent(encoded -> stored.put(KEY_CONFIG, encoded));
            list.add(stored);
        });
        tag.put(KEY_CORES, list);
        tag.putString(KEY_PREFIX, defaultNamePrefix);
        return tag;
    }

    // --- Изменение -----------------------------------------------------------

    /**
     * Отмечает, где Ядро находится сейчас и как оно настроено.
     *
     * <p>Грязным указатель становится, только если запись действительно
     * изменилась: вызывается это при каждой загрузке чанка с Ядром, и
     * безусловная пометка переписывала бы весь указатель на каждом автосохранении.
     */
    public static void put(MinecraftServer server, String name, UUID coreId,
                           ResourceKey<Level> dimension, BlockPos pos, CoreConfig config) {
        CoreIndex index = get(server);
        Entry previous = index.byId.get(coreId);
        Entry entry = new Entry(name, dimension, pos.immutable(), config);
        if (entry.equals(previous) && coreId.equals(index.byName.get(name))) {
            return;
        }
        if (previous != null && !previous.name().equals(name)) {
            index.byName.remove(previous.name());
        }
        index.byId.put(coreId, entry);
        index.byName.put(name, coreId);
        index.setDirty();
    }

    public static void remove(MinecraftServer server, UUID coreId) {
        CoreIndex index = get(server);
        Entry removed = index.byId.remove(coreId);
        if (removed != null) {
            index.byName.remove(removed.name());
            index.setDirty();
        }
    }

    public static boolean isNameTaken(MinecraftServer server, String name, UUID exceptCoreId) {
        UUID owner = get(server).byName.get(name);
        return owner != null && !owner.equals(exceptCoreId);
    }

    // --- Поиск ---------------------------------------------------------------

    /**
     * Ищет Ядро по имени <b>среди уже загруженных</b>.
     *
     * <p>Чанки здесь не грузятся никогда: раньше был вариант с синхронной
     * подгрузкой, и одна команда над Ядром на выгруженном корабле ставила
     * сервер, пока чанк читался с диска. Тем, кому нужны и выгруженные, —
     * {@link #resolveAsync}.
     */
    public static Optional<CoreBlockEntity> find(MinecraftServer server, String name) {
        UUID coreId = get(server).byName.get(name);
        return coreId == null ? Optional.empty() : findById(server, coreId);
    }

    /** Постоянный идентификатор Ядра с этим именем — по указателю, без чанков. */
    public static Optional<UUID> idOf(MinecraftServer server, String name) {
        return Optional.ofNullable(get(server).byName.get(name));
    }

    /**
     * Ищет Ядро по постоянному идентификатору среди загруженных.
     *
     * <p>Именно так адресует Ядра консоль: имя можно переименовать прямо в
     * открытом окне, а идентификатор у блока один на всю его жизнь.
     *
     * <p>Про запись в указателе. Она удаляется <b>только</b> тогда, когда чанк
     * действительно загружен и Ядра в нём нет: это значит, что блок исчез
     * помимо нас. Если чанк выгружен, запись остаётся на месте. Раньше здесь
     * удалялось и в этом случае, и любая заминка навсегда стирала имя Ядра, до
     * которого просто не дотянулись.
     */
    public static Optional<CoreBlockEntity> findById(MinecraftServer server, UUID coreId) {
        CoreIndex index = get(server);
        Entry entry = index.byId.get(coreId);
        if (entry == null) {
            return Optional.empty();
        }

        ServerLevel level = server.getLevel(entry.dimension());
        if (level == null || !level.hasChunkAt(entry.pos())) {
            return Optional.empty();
        }

        if (level.getBlockEntity(entry.pos()) instanceof CoreBlockEntity core
                && core.coreId().equals(coreId)) {
            return Optional.of(core);
        }

        Gimpanum.LOGGER.debug("Указатель: запись '{}' устарела, удалена", entry.name());
        index.byId.remove(coreId);
        index.byName.remove(entry.name());
        index.setDirty();
        return Optional.empty();
    }

    /**
     * Находит Ядра по идентификаторам, при нужде подгружая их чанки — но без
     * ожидания на главном потоке.
     *
     * <p>Уже загруженные Ядра отдаются сразу, в этом же вызове. Для остальных
     * заказывается подгрузка через {@link ChunkLoads} (чанки одной группы — одним
     * запросом), и когда всё готово, вызывается {@code then} — на главном потоке,
     * в порядке исходного списка. Ядра, до которых не дотянулись (нет измерения,
     * чанк не дождались), в список не попадают, а их записи остаются в указателе.
     */
    public static void resolveAsync(MinecraftServer server, Collection<UUID> ids,
                                    Consumer<List<CoreBlockEntity>> then) {
        CoreIndex index = get(server);
        Map<UUID, CoreBlockEntity> found = new HashMap<>();
        Map<ResourceKey<Level>, Map<ChunkPos, List<UUID>>> missing = new LinkedHashMap<>();

        for (UUID coreId : ids) {
            Entry entry = index.byId.get(coreId);
            ServerLevel level = entry == null ? null : server.getLevel(entry.dimension());
            if (level == null) {
                continue;
            }
            if (level.hasChunkAt(entry.pos())) {
                findById(server, coreId).ifPresent(core -> found.put(coreId, core));
            } else {
                missing.computeIfAbsent(entry.dimension(), key -> new LinkedHashMap<>())
                        .computeIfAbsent(new ChunkPos(entry.pos()), key -> new ArrayList<>())
                        .add(coreId);
            }
        }

        int groups = missing.values().stream().mapToInt(Map::size).sum();
        if (groups == 0) {
            then.accept(inOrder(ids, found));
            return;
        }
        int[] left = {groups};
        missing.forEach((dimension, byChunk) -> {
            ServerLevel level = server.getLevel(dimension);
            byChunk.forEach((chunk, members) -> ChunkLoads.request(level, chunk, ok -> {
                if (ok) {
                    for (UUID coreId : members) {
                        findById(server, coreId).ifPresent(core -> found.put(coreId, core));
                    }
                }
                if (--left[0] == 0) {
                    then.accept(inOrder(ids, found));
                }
            }));
        });
    }

    private static List<CoreBlockEntity> inOrder(Collection<UUID> ids, Map<UUID, CoreBlockEntity> found) {
        List<CoreBlockEntity> ordered = new ArrayList<>(found.size());
        for (UUID coreId : ids) {
            CoreBlockEntity core = found.get(coreId);
            if (core != null) {
                ordered.add(core);
            }
        }
        return ordered;
    }

    public static int count(MinecraftServer server) {
        return get(server).byId.size();
    }

    /** Все известные имена, включая выгруженные Ядра. */
    public static List<String> names(MinecraftServer server) {
        return new ArrayList<>(get(server).byName.keySet());
    }

    /** Всё, что известно об одном Ядре, без открытия его чанка. */
    public static Optional<Snapshot> snapshot(MinecraftServer server, UUID coreId) {
        Entry entry = get(server).byId.get(coreId);
        return entry == null
                ? Optional.empty()
                : Optional.of(new Snapshot(coreId, entry.name(), entry.dimension(), entry.pos(),
                        entry.config()));
    }

    /** Все Ядра по алфавиту — то, из чего строится список в консоли. */
    public static List<Snapshot> all(MinecraftServer server) {
        List<Snapshot> found = new ArrayList<>();
        get(server).byId.forEach((coreId, entry) -> found.add(
                new Snapshot(coreId, entry.name(), entry.dimension(), entry.pos(), entry.config())));
        found.sort(Comparator.comparing(Snapshot::name, String.CASE_INSENSITIVE_ORDER));
        return found;
    }

    /** Загружен ли сейчас чанк с этим Ядром. */
    public static boolean isLoaded(MinecraftServer server, UUID coreId) {
        Entry entry = get(server).byId.get(coreId);
        if (entry == null) {
            return false;
        }
        ServerLevel level = server.getLevel(entry.dimension());
        return level != null && level.hasChunkAt(entry.pos());
    }

    /** Где записано Ядро: измерение и координаты, как есть в указателе. */
    public static Optional<String> describe(MinecraftServer server, String name) {
        CoreIndex index = get(server);
        UUID coreId = index.byName.get(name);
        if (coreId == null) {
            return Optional.empty();
        }
        Entry entry = index.byId.get(coreId);
        if (entry == null) {
            return Optional.empty();
        }
        ServerLevel level = server.getLevel(entry.dimension());
        boolean loaded = level != null && level.hasChunkAt(entry.pos());
        return Optional.of(entry.dimension().location() + " " + entry.pos().toShortString()
                + (loaded ? "" : " (выгружено)"));
    }

    // --- Удаление Ядра из мира ------------------------------------------------

    /**
     * Убирает Ядро из мира на расстоянии, вместе с записью в указателе.
     *
     * <p>Чанк с Ядром при нужде подгружается без ожидания (см. {@link #resolveAsync});
     * результат приходит в {@code done}: {@code true}, если блок действительно убран.
     *
     * @param detonate выполнить ли посмертное: взрыв, команды, Печать. Обычное
     *                 удаление тихое — Ядро на предохранителе ведёт себя как
     *                 любой другой блок, и {@code CoreBlock.onRemove} до
     *                 арбитра даже не доходит. Подрыв же оставляет
     *                 предохранитель снятым и потому запускает всё то же, что
     *                 и разрушение снарядом.
     */
    public static void deleteAsync(MinecraftServer server, UUID coreId, boolean detonate,
                                   Consumer<Boolean> done) {
        resolveAsync(server, List.of(coreId), found -> {
            if (found.isEmpty()) {
                // До блока не дотянулись — но если записи о нём уже нет, значит
                // Ядро исчезло помимо нас, и удаление можно считать состоявшимся.
                done.accept(get(server).byId.get(coreId) == null);
                return;
            }
            done.accept(delete(server, found.get(0), detonate));
        });
    }

    private static boolean delete(MinecraftServer server, CoreBlockEntity core, boolean detonate) {
        if (!(core.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        if (detonate) {
            core.setConfig(core.config().withArmed(true));
        } else if (core.config().armed()) {
            core.setConfig(core.config().withArmed(false));
        }
        // Без выпадения: Ядро и так noLootTable, но removeBlock честнее
        // destroyBlock — тот ещё и звук со взвесью частиц устраивает.
        boolean removed = level.removeBlock(core.getBlockPos(), false);
        if (removed && !detonate) {
            // Тихое удаление до арбитра не доходит, запись снимаем сами.
            remove(server, core.coreId());
        }
        return removed;
    }

    /**
     * Подбирает свободное имя вида {@code core1}, где {@code core} —
     * настраиваемая приставка. Учитывает и выгруженные Ядра, поэтому имена не
     * сталкиваются.
     */
    public static String nextFreeName(MinecraftServer server) {
        CoreIndex index = get(server);
        for (int i = 1; ; i++) {
            String candidate = index.defaultNamePrefix + i;
            if (!index.byName.containsKey(candidate)) {
                return candidate;
            }
        }
    }

    public static String defaultNamePrefix(MinecraftServer server) {
        return get(server).defaultNamePrefix;
    }

    public static void setDefaultNamePrefix(MinecraftServer server, String prefix) {
        CoreIndex index = get(server);
        index.defaultNamePrefix = prefix;
        index.setDirty();
    }

    /**
     * Идентификаторы всех Ядер, чьи имена подходят под образец с подстановкой
     * {@link #WILDCARD}, — по указателю, чанки не трогаются.
     *
     * <p>Остальной текст образца экранируется, поэтому точки и дефисы в именах
     * значат сами себя и лишних совпадений не дают.
     */
    public static List<UUID> idsMatching(MinecraftServer server, String selector) {
        Pattern pattern = toPattern(selector);
        CoreIndex index = get(server);
        List<UUID> found = new ArrayList<>();
        index.byName.forEach((name, coreId) -> {
            if (pattern.matcher(name).matches()) {
                found.add(coreId);
            }
        });
        return found;
    }

    /** То же, но только загруженные Ядра — для подсказок в консоли. */
    public static List<CoreBlockEntity> findMatching(MinecraftServer server, String selector) {
        List<CoreBlockEntity> found = new ArrayList<>();
        for (UUID coreId : idsMatching(server, selector)) {
            findById(server, coreId).ifPresent(found::add);
        }
        return found;
    }

    /** Содержит ли строка подстановку, то есть задаёт ли она сразу много Ядер. */
    public static boolean isPattern(String selector) {
        return selector.contains(WILDCARD);
    }

    private static Pattern toPattern(String selector) {
        StringBuilder regex = new StringBuilder();
        int from = 0;
        while (true) {
            int at = selector.indexOf(WILDCARD, from);
            if (at < 0) {
                appendLiteral(regex, selector.substring(from));
                break;
            }
            appendLiteral(regex, selector.substring(from, at));
            regex.append(".*");
            from = at + WILDCARD.length();
        }
        return Pattern.compile(regex.toString());
    }

    private static void appendLiteral(StringBuilder regex, String literal) {
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal));
        }
    }
}
