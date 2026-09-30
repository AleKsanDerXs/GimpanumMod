package com.kirbornu.gimpanum.structure;

import com.kirbornu.gimpanum.config.JsonConfig;
import com.kirbornu.gimpanum.emission.MemoryEntity;
import com.kirbornu.gimpanum.emission.Visions;
import com.kirbornu.gimpanum.entity.CometWraith;
import com.kirbornu.gimpanum.entity.GimpanumEntities;
import com.kirbornu.gimpanum.registry.GimpanumContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Ловушки структур — что происходит, когда тронут Сгусток фоноса. */
final class Traps {

    /** Тиков между кольцами волны увядания — волна идёт от сгустка наружу. */
    private static final int WAVE_STEP = 3;

    private Traps() {
    }

    /**
     * Застывшее воспоминание рушится.
     *
     * <p>Всё живое вокруг волной от сгустка осыпается пеплом: трава и земля
     * становятся Космическим пеплом, стволы тоже, листва, цветы и вода просто
     * исчезают. Вместе с травой уходит и воздух — дышать здесь можно было только
     * рядом с ней. Игрушка остаётся: она не живая. А к тронувшему приходят
     * исполинские видения из этого воспоминания.
     */
    static void witherMemory(ServerLevel level, BlockPos centre, ServerPlayer player) {
        JsonConfig.Section config = StructureConfig.of(ClotKind.FROZEN_MEMORY.id());
        int radius = config.integer("wither_radius_blocks");
        TreeMap<Integer, List<BlockPos>> rings = new TreeMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-radius, -radius, -radius),
                centre.offset(radius, radius, radius))) {
            if (pos.distSqr(centre) <= radius * radius && level.hasChunkAt(pos) && alive(level.getBlockState(pos))) {
                rings.computeIfAbsent((int) Math.sqrt(pos.distSqr(centre)), ring -> new ArrayList<>()).add(pos.immutable());
            }
        }
        rings.forEach((ring, cells) -> Later.run(level, ring * WAVE_STEP, now -> {
            for (BlockPos pos : cells) {
                wither(now, pos);
            }
        }));
        level.playSound(null, centre, SoundEvents.BEACON_DEACTIVATE, SoundSource.AMBIENT, 3.0F, 0.5F);

        int visions = config.between("visions", level.random);
        int life = config.integer("vision_life_seconds") * 20;
        Later.run(level, radius * WAVE_STEP, now -> {
            for (int i = 0; i < visions; i++) {
                Visions.summon(now, player, 10.0 + now.random.nextDouble() * 10.0, life);
            }
        });
    }

    /** Живое — то, что осыпается: трава, земля, стволы, листва, цветы, вода. */
    private static boolean alive(BlockState state) {
        return state.is(BlockTags.DIRT) || state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.FLOWERS) || state.is(BlockTags.REPLACEABLE_BY_TREES)
                || state.getFluidState().is(FluidTags.WATER);
    }

    private static void wither(ServerLevel level, BlockPos pos) {
        // Волна идёт несколько секунд: чанк за это время мог выгрузиться, а обращение к
        // выгруженному загрузило бы его синхронно.
        if (!level.hasChunkAt(pos)) {
            return;
        }
        BlockState state = level.getBlockState(pos);
        if (!alive(state)) {
            return;
        }
        boolean solid = state.is(BlockTags.DIRT) || state.is(BlockTags.LOGS);
        level.setBlockAndUpdate(pos, solid ? GimpanumContent.COSMIC_ASH.get().defaultBlockState()
                : Blocks.AIR.defaultBlockState());
        level.sendParticles(ParticleTypes.WHITE_ASH, pos.getX() + 0.5, pos.getY() + 0.8, pos.getZ() + 0.5,
                4, 0.3, 0.3, 0.3, 0.01);
        if (level.random.nextInt(8) == 0) {
            level.sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                    2, 0.2, 0.2, 0.2, 0.01);
        }
    }

    /**
     * Грёза оборачивается кошмаром.
     *
     * <p>Часть стекла бьётся, и среди осколков одно за другим вспыхивают
     * воспоминания Хару — те же, что в Плохих Воспоминаниях: искрят и
     * взрываются, задевая только игроков.
     */
    static void shatterDream(ServerLevel level, BlockPos centre, ServerPlayer player) {
        JsonConfig.Section config = StructureConfig.of(ClotKind.GLASS_DREAMS.id());
        RandomSource random = level.random;
        int spread = config.integer("spread_blocks");

        double share = config.number("shatter_share");
        List<BlockPos> glass = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-spread, -spread, -spread),
                centre.offset(spread, spread, spread))) {
            if (!level.hasChunkAt(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if ((state.getBlock() instanceof StainedGlassBlock || state.getBlock() instanceof StainedGlassPaneBlock)
                    && random.nextDouble() < share) {
                glass.add(pos.immutable());
            }
        }
        for (int i = 0; i < glass.size(); i++) {
            BlockPos pos = glass.get(i);
            Later.run(level, i % 40, now -> {
                if (now.hasChunkAt(pos)) {
                    now.destroyBlock(pos, false);
                }
            });
        }

        int memories = config.between("memories", random);
        int gap = config.integer("memory_gap_ticks");
        for (int i = 0; i < memories; i++) {
            Later.run(level, i * gap, now -> {
                MemoryEntity memory = GimpanumEntities.MEMORY.get().create(now);
                if (memory == null) {
                    return;
                }
                memory.moveTo(spot(now, centre, spread));
                memory.arm(config.integer("fuse_ticks"), (float) config.number("radius_blocks"),
                        (float) config.number("damage"));
                now.addFreshEntity(memory);
            });
        }
    }

    /** Точка в воздухе поблизости — воспоминание в стекле или песке взорвалось бы впустую. */
    private static Vec3 spot(ServerLevel level, BlockPos centre, int spread) {
        RandomSource random = level.random;
        for (int attempt = 0; attempt < 12; attempt++) {
            BlockPos pos = centre.offset(random.nextInt(spread * 2 + 1) - spread, random.nextInt(5) - 2,
                    random.nextInt(spread * 2 + 1) - spread);
            if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return Vec3.atCenterOf(pos);
            }
        }
        return Vec3.atCenterOf(centre);
    }

    /**
     * Из статуи вырывается художник — Призрак кометы, бывший Радитаж, —
     * и первым делом набрасывается на того, кто разбудил его.
     */
    static void releaseArtist(ServerLevel level, BlockPos pos, ServerPlayer player) {
        CometWraith wraith = GimpanumEntities.COMET_WRAITH.get().create(level);
        if (wraith == null) {
            return;
        }
        wraith.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
        EventHooks.finalizeMobSpawn(wraith, level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
        wraith.setTarget(player);
        level.addFreshEntity(wraith);
        level.playSound(null, pos, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.5F, 0.6F);
    }
}
