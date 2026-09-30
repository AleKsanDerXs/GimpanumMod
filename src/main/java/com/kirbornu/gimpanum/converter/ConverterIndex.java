package com.kirbornu.gimpanum.converter;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Где стоят Фонос-конвертеры.
 *
 * <p>Указатель нужен потому, что конвертеры разбросаны по всей карте и
 * большинство из них в любой момент выгружено. Без записи на диске список
 * обменников оператору взять неоткуда, а игрокам — тем более.
 *
 * <p>Хранит только координаты. Условия обмена живут в самом блоке: они меняются
 * командой на месте, и держать их вторую копию значило бы заводить два
 * источника правды.
 *
 * <p>Имена конвертерам, в отличие от Ядер, не нужны: Ядро уезжает на физическую
 * конструкцию, где координаты становятся восьмизначными и непригодными для
 * ввода, а конвертер прибит к миру намертво — по координатам его и адресуют.
 */
public final class ConverterIndex extends SavedData {

    private static final String FILE_NAME = "gimpanum_converters";
    private static final String KEY_LIST = "Converters";
    private static final String KEY_DIMENSION = "Dimension";
    private static final String KEY_POS = "Pos";

    /** Измерение и позиция — этого хватает, чтобы найти блок и поставить метку. */
    public record Entry(ResourceKey<Level> dimension, BlockPos pos) {
    }

    private final Set<Entry> entries = new LinkedHashSet<>();

    private ConverterIndex() {
    }

    public static ConverterIndex get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ConverterIndex::new, ConverterIndex::load, null), FILE_NAME);
    }

    private static ConverterIndex load(CompoundTag tag, HolderLookup.Provider registries) {
        ConverterIndex index = new ConverterIndex();
        ListTag list = tag.getList(KEY_LIST, Tag.TAG_COMPOUND);

        for (int i = 0; i < list.size(); i++) {
            CompoundTag stored = list.getCompound(i);
            ResourceLocation dimensionId = ResourceLocation.tryParse(stored.getString(KEY_DIMENSION));
            int[] pos = stored.getIntArray(KEY_POS);
            if (dimensionId == null || pos.length != 3) {
                continue;
            }
            index.entries.add(new Entry(ResourceKey.create(Registries.DIMENSION, dimensionId),
                    new BlockPos(pos[0], pos[1], pos[2])));
        }
        return index;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry entry : entries) {
            CompoundTag stored = new CompoundTag();
            stored.putString(KEY_DIMENSION, entry.dimension().location().toString());
            stored.putIntArray(KEY_POS,
                    new int[]{entry.pos().getX(), entry.pos().getY(), entry.pos().getZ()});
            list.add(stored);
        }
        tag.put(KEY_LIST, list);
        return tag;
    }

    public static void put(MinecraftServer server, ResourceKey<Level> dimension, BlockPos pos) {
        ConverterIndex index = get(server);
        if (index.entries.add(new Entry(dimension, pos.immutable()))) {
            index.setDirty();
        }
    }

    public static void remove(MinecraftServer server, ResourceKey<Level> dimension, BlockPos pos) {
        ConverterIndex index = get(server);
        if (index.entries.remove(new Entry(dimension, pos.immutable()))) {
            index.setDirty();
        }
    }

    /**
     * Все известные конвертеры, попутно вычёркивая пропавшие.
     *
     * <p>Запись может разойтись с миром: блок сносят в креативе, затирают
     * схематикой, теряют вместе с повреждённым чанком. Проверяются только
     * загруженные позиции — подгружать ради списка чанки по всей карте нельзя.
     */
    public static int count(MinecraftServer server) {
        return get(server).entries.size();
    }

    public static List<Entry> all(MinecraftServer server) {
        ConverterIndex index = get(server);
        List<Entry> found = new ArrayList<>();
        List<Entry> stale = new ArrayList<>();

        for (Entry entry : index.entries) {
            ServerLevel level = server.getLevel(entry.dimension());
            if (level != null && level.hasChunkAt(entry.pos())
                    && !(level.getBlockEntity(entry.pos()) instanceof ConverterBlockEntity)) {
                stale.add(entry);
                continue;
            }
            found.add(entry);
        }

        if (!stale.isEmpty()) {
            index.entries.removeAll(stale);
            index.setDirty();
        }
        return found;
    }
}
