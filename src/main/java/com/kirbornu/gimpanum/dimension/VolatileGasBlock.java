package com.kirbornu.gimpanum.dimension;

import com.kirbornu.gimpanum.config.JsonConfig;
import com.kirbornu.gimpanum.perf.PerformanceConfig;
import com.kirbornu.gimpanum.Gimpanum;
import com.kirbornu.gimpanum.registry.GimpanumContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Летучий небула-газ — карманы в породе, которые взрываются от сотрясения.
 *
 * <p>Сотрясение — это сломанный рядом блок, сам сломанный газ, чужой взрыв
 * или укус Поглотителя. Добыть газ нельзя: он всегда уходит взрывом. Зато
 * копать в Гимпануме становится делом осторожным, а Поглотитель, прогрызая
 * толщу, сам устраивает обвалы.
 *
 * <p>Взрыв не мгновенный: газ вспыхивает через пару тиков, и соседние карманы
 * подхватывают один за другим. Выходит не один хлопок, а раскатистая цепь —
 * и секунда на то, чтобы понять, что пора бежать.
 *
 * <p>Огня взрыв не даёт: в Гимпануме нечему гореть.
 */
@EventBusSubscriber(modid = Gimpanum.MOD_ID)
public class VolatileGasBlock extends Block {

    /** Сила взрыва — чуть слабее крипера: карман в пять блоков и так даёт пять взрывов. */
    /** Сколько газовых взрывов уже прошло в этот тик; см. {@code volatile_gas.max_per_tick}. */
    private static long budgetTick = -1;
    private static int budgetUsed;

    /** Задержка вспышки, тиков: от сломанного блока — почти сразу, по цепи — с разбросом. */
    private static final int FUSE_MIN = 2;
    private static final int FUSE_SPREAD = 5;

    public VolatileGasBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    /** Поджечь газ в этой точке, если он там есть. */
    public static void disturb(Level level, BlockPos pos) {
        if (level.getBlockState(pos).is(GimpanumContent.VOLATILE_NEBULA_GAS.get())) {
            level.scheduleTick(pos, GimpanumContent.VOLATILE_NEBULA_GAS.get(),
                    FUSE_MIN + level.getRandom().nextInt(FUSE_SPREAD));
        }
    }

    /** Поджечь газ вокруг точки — со всех шести сторон. */
    public static void disturbAround(Level level, BlockPos pos) {
        for (Direction side : Direction.values()) {
            disturb(level, pos.relative(side));
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        JsonConfig.Section config = PerformanceConfig.of("volatile_gas");
        // Потолок взрывов за тик: облако газа рвётся цепочкой, и без потолка
        // сотня взрывов может прийтись на один тик. Лишние переносятся на следующий.
        int cap = config.integer("max_per_tick");
        if (cap > 0) {
            long now = level.getGameTime();
            if (now != budgetTick) {
                budgetTick = now;
                budgetUsed = 0;
            }
            if (budgetUsed >= cap) {
                level.scheduleTick(pos, this, 1 + random.nextInt(FUSE_SPREAD));
                return;
            }
            budgetUsed++;
        }
        level.removeBlock(pos, false);
        level.explode(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                (float) config.number("power"), false,
                config.bool("break_blocks") ? Level.ExplosionInteraction.BLOCK : Level.ExplosionInteraction.NONE);
    }

    /**
     * Чужой взрыв газ не сносит, а поджигает.
     *
     * <p>Блок остаётся на месте до своей вспышки — иначе цепь оборвалась бы
     * на первом же кармане: взрыв убрал бы соседей раньше, чем они успеют
     * вспыхнуть сами.
     */
    @Override
    public void onBlockExploded(BlockState state, Level level, BlockPos pos, Explosion explosion) {
        disturb(level, pos);
    }

    @Override
    public boolean dropFromExplosion(Explosion explosion) {
        return false;
    }

    /**
     * Игрок сломал блок: сам газ или соседа газа.
     *
     * <p>Сам газ не ломается — вместо этого он вспыхивает. Сосед ломается как
     * обычно, но газ рядом с ним поджигается.
     */
    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof Level level) || level.isClientSide) {
            return;
        }
        BlockPos pos = event.getPos();
        if (event.getState().is(GimpanumContent.VOLATILE_NEBULA_GAS.get())) {
            event.setCanceled(true);
            level.scheduleTick(pos, GimpanumContent.VOLATILE_NEBULA_GAS.get(), 1);
            return;
        }
        disturbAround(level, pos);
    }
}
