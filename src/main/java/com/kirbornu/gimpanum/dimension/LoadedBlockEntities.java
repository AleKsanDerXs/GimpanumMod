package com.kirbornu.gimpanum.dimension;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.function.Consumer;

/**
 * Обход блок-сущностей там, где они вообще могут тикать.
 *
 * <p>Ни один клеймовый или создающий мод не даёт события «блок-сущность
 * протикала», поэтому за чужими блоками приходится присматривать обходом.
 * Обход общий на всех, кто в нём нуждается: пройти чанки один раз и раздать
 * находки — дешевле, чем ходить по тем же чанкам дважды.
 *
 * <p>Границы обхода — не «все загруженные чанки», а радиус тика вокруг
 * игроков плюс принудительно загруженные. Вне них блок-сущность всё равно не
 * работает, и присматривать за ней незачем.
 */
public final class LoadedBlockEntities {

    private LoadedBlockEntities() {
    }

    /** Чанки, где блок-сущность может тикать. */
    public static LongSet candidates(ServerLevel level) {
        return candidates(level, Integer.MAX_VALUE);
    }

    /** То же, но не дальше {@code maxRadius} чанков от игрока: осмотру не нужна вся дальность симуляции. */
    public static LongSet candidates(ServerLevel level, int maxRadius) {
        LongSet chunks = new LongOpenHashSet();
        int radius = Math.min(maxRadius, level.getServer().getPlayerList().getSimulationDistance());
        for (ServerPlayer player : level.players()) {
            ChunkPos centre = player.chunkPosition();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    chunks.add(ChunkPos.asLong(centre.x + dx, centre.z + dz));
                }
            }
        }
        chunks.addAll(level.getForcedChunks());
        return chunks;
    }

    /** Отдаёт каждую блок-сущность из этих чанков. Чанк, которого нет в памяти, пропускается. */
    public static void forEach(ServerLevel level, LongSet chunks, Consumer<BlockEntity> visitor) {
        LongIterator it = chunks.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) {
                continue;
            }
            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                visitor.accept(blockEntity);
            }
        }
    }
}
