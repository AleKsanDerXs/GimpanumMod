package com.kirbornu.gimpanum.dimension;

import com.kirbornu.gimpanum.perf.PerformanceConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Где стоят порталы в каждом измерении.
 *
 * <p>Порталы не связаны попарно: выход берётся случайным среди известных на
 * той стороне. Значит нужен список, а не привязка «этот к тому».
 *
 * <p>Портал попадает сюда, когда его чанк впервые загрузился. Поэтому портал,
 * до которого ещё никто не дошёл, целью не станет — на этот случай
 * {@link NebulaPortal} умеет искать структуру мирогеном.
 */
public final class PortalIndex extends SavedData {

    private static final String FILE_NAME = "gimpanum_portals";
    private static final String KEY_LIST = "Portals";
    private static final String KEY_DIMENSION = "Dimension";
    private static final String KEY_POS = "Pos";

    private final Set<Entry> entries = new LinkedHashSet<>();

    public record Entry(ResourceKey<Level> dimension, BlockPos pos) {
    }

    private PortalIndex() {
    }

    public static PortalIndex get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PortalIndex::new, PortalIndex::load, null), FILE_NAME);
    }

    private static PortalIndex load(CompoundTag tag, HolderLookup.Provider registries) {
        PortalIndex index = new PortalIndex();
        ListTag list = tag.getList(KEY_LIST, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag stored = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(stored.getString(KEY_DIMENSION));
            int[] pos = stored.getIntArray(KEY_POS);
            if (id == null || pos.length != 3) {
                continue;
            }
            index.entries.add(new Entry(ResourceKey.create(Registries.DIMENSION, id),
                    new BlockPos(pos[0], pos[1], pos[2])));
        }
        return index;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry e : entries) {
            CompoundTag stored = new CompoundTag();
            stored.putString(KEY_DIMENSION, e.dimension().location().toString());
            stored.putIntArray(KEY_POS, new int[]{e.pos().getX(), e.pos().getY(), e.pos().getZ()});
            list.add(stored);
        }
        tag.put(KEY_LIST, list);
        return tag;
    }

    public static void put(MinecraftServer server, ResourceKey<Level> dimension, BlockPos pos) {
        PortalIndex index = get(server);
        if (index.entries.add(new Entry(dimension, pos.immutable()))) {
            // Указатель не растёт без предела: лишнее вытесняется с давних записей.
            int cap = PerformanceConfig.of("portal").integer("max_known");
            if (cap > 0) {
                Iterator<Entry> it = index.entries.iterator();
                while (index.entries.size() > cap && it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
            index.setDirty();
        }
    }

    /** Вычёркивает портал: арку сломали или проверка нашла на её месте пустоту. */
    public static void remove(MinecraftServer server, ResourceKey<Level> dimension, BlockPos pos) {
        PortalIndex index = get(server);
        if (index.entries.remove(new Entry(dimension, pos.immutable()))) {
            index.setDirty();
        }
    }

    public static int size(MinecraftServer server) {
        return get(server).entries.size();
    }

    public static List<BlockPos> in(MinecraftServer server, ResourceKey<Level> dimension) {
        List<BlockPos> found = new ArrayList<>();
        for (Entry e : get(server).entries) {
            if (e.dimension().equals(dimension)) {
                found.add(e.pos());
            }
        }
        return found;
    }
}
