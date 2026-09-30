package com.kirbornu.gimpanum.emission;

import com.kirbornu.gimpanum.config.JsonConfig;
import com.kirbornu.gimpanum.registry.GimpanumContent;
import com.kirbornu.gimpanum.worldgen.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Аккреция: диск силится родить солнце, которым Хару так и не стал, и на
 * барханы сыплются метеориты.
 *
 * <p>Каждый метеорит виден заранее: огненный след с неба и тлеющее кольцо там,
 * куда он придёт. Удар — взрыв и свежий обломок: сырое железо с магмой, изредка
 * древние обломки или хрустальная корка. В лабиринте безопасно, но добыча
 * наверху — выброс, за которым выходят сами.
 *
 * <p>Падают только на поверхность, рядом с каждым игроком свой поток.
 */
final class Accretion extends LastingEmission {

    /** С какой высоты над землёй начинается огненный след. */
    private static final int STREAK = 60;

    private record Impact(BlockPos ground, int at) {
    }

    private final List<Impact> falling = new ArrayList<>();

    /** Тик, на котором у игрока падает следующий метеорит. */
    private final Map<UUID, Integer> next = new HashMap<>();

    @Override
    protected int duration(ServerLevel level) {
        return EmissionConfig.of("accretion").integer("duration_seconds") * 20;
    }

    @Override
    protected void pulse(ServerLevel level, List<ServerPlayer> targets) {
        JsonConfig.Section config = EmissionConfig.of("accretion");
        RandomSource random = level.random;
        int fuse = config.integer("fuse_ticks");
        for (ServerPlayer player : targets) {
            int due = next.computeIfAbsent(player.getUUID(), id -> elapsed);
            if (elapsed < due) {
                continue;
            }
            next.put(player.getUUID(), elapsed + config.between("interval_ticks", random));
            double angle = random.nextDouble() * Math.PI * 2.0;
            int distance = config.between("distance_blocks", random);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
            if (!level.hasChunkAt(new BlockPos(x, 0, z))) {
                continue;
            }
            BlockPos ground = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            falling.add(new Impact(ground, elapsed + fuse));
            level.playSound(null, ground, SoundEvents.GHAST_SHOOT, SoundSource.HOSTILE, 4.0F, 0.5F);
        }

        Iterator<Impact> it = falling.iterator();
        while (it.hasNext()) {
            Impact impact = it.next();
            int left = impact.at() - elapsed;
            if (left <= 0) {
                strike(level, impact.ground(), config, random);
                it.remove();
                continue;
            }
            BlockPos ground = impact.ground();
            double height = ground.getY() + STREAK * (double) left / fuse;
            level.sendParticles(ParticleTypes.FLAME, ground.getX() + 0.5, height, ground.getZ() + 0.5,
                    6, 0.3, 0.6, 0.3, 0.01);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, ground.getX() + 0.5, height + 1.0, ground.getZ() + 0.5,
                    2, 0.2, 0.4, 0.2, 0.0);
            if (left % 5 == 0) {
                ring(level, ground);
            }
        }
    }

    @Override
    public void stop(ServerLevel level) {
        falling.clear();
    }

    /** Тлеющее кольцо на земле — туда и придёт. */
    private static void ring(ServerLevel level, BlockPos ground) {
        for (int i = 0; i < 16; i++) {
            double angle = Math.PI * 2.0 * i / 16;
            level.sendParticles(ParticleTypes.SMOKE, ground.getX() + 0.5 + Math.cos(angle) * 2.5, ground.getY() + 0.1,
                    ground.getZ() + 0.5 + Math.sin(angle) * 2.5, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private static void strike(ServerLevel level, BlockPos ground, JsonConfig.Section config, RandomSource random) {
        level.explode(null, ground.getX() + 0.5, ground.getY() + 0.5, ground.getZ() + 0.5,
                (float) config.number("power"), false,
                config.bool("break_blocks") ? Level.ExplosionInteraction.BLOCK : Level.ExplosionInteraction.NONE);
        if (!config.bool("place_debris")) {
            return;
        }
        // Сам обломок — в дне свежей ямы, наполовину в земле.
        double size = 1.0 + random.nextDouble() * 0.8;
        int reach = (int) Math.ceil(size);
        BlockPos centre = ground.below();
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-reach, -reach, -reach), centre.offset(reach, reach, reach))) {
            if (pos.distSqr(centre) > size * size) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || Terrain.rock(state)) {
                level.setBlockAndUpdate(pos, piece(random));
            }
        }
    }

    private static BlockState piece(RandomSource random) {
        float roll = random.nextFloat();
        if (roll < 0.50F) {
            return Blocks.RAW_IRON_BLOCK.defaultBlockState();
        }
        if (roll < 0.70F) {
            return Blocks.MAGMA_BLOCK.defaultBlockState();
        }
        if (roll < 0.85F) {
            return GimpanumContent.COSMIC_ASH.get().defaultBlockState();
        }
        if (roll < 0.95F) {
            return Blocks.BLACKSTONE.defaultBlockState();
        }
        if (roll < 0.98F) {
            return Blocks.ANCIENT_DEBRIS.defaultBlockState();
        }
        return GimpanumContent.CRYSTAL_CRUST.get().defaultBlockState();
    }
}
