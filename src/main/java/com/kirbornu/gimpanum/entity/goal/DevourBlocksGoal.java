package com.kirbornu.gimpanum.entity.goal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.kirbornu.gimpanum.config.JsonConfig;
import com.kirbornu.gimpanum.dimension.VolatileGasBlock;
import com.kirbornu.gimpanum.perf.PerformanceConfig;
import com.kirbornu.gimpanum.registry.GimpanumContent;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.neoforged.neoforge.event.EventHooks;

import java.util.EnumSet;

/**
 * Непрерывное бурение к цели.
 *
 * <p>Прежняя редакция работала рывками, и это чувствовалось именно так, как и
 * выглядело: поглотитель упирался в стену, находил <i>один</i> блок,
 * выгрызал вокруг него полость в три блока, пролетал эти три блока и упирался
 * снова. Между рывками он казался растерянным, а не страшным.
 *
 * <p>Хуже того, преграду искал луч из глаз в глаза жертвы. У моба четыре
 * блока в ширину, и луч то и дело проходил в щель, тогда как туша стояла в
 * стену: искать было «нечего», и он честно висел на месте.
 *
 * <p>Теперь полость выедается <b>каждый тик</b> и не вокруг найденного блока,
 * а прямо перед мордой — сфера радиусом {@value #BITE} на {@link #lead}
 * блоков впереди середины тела. Пока впереди есть камень, он исчезает без
 * пауз, и поглотитель идёт сквозь породу с той же скоростью, с какой летел бы
 * в пустоте. Останавливает его только по-настоящему крепкое: время укуса
 * считается по самому твёрдому блоку в сфере, и даже обсидиан — это треть
 * секунды, а не преграда.
 *
 * <p>Блоки с отрицательной прочностью (коренная порода, Ядро, врата) не
 * трогаются вовсе: это не «крепко», это «нельзя», и вокруг них он обгрызает.
 */
public class DevourBlocksGoal extends Goal {

    /** Ближе этого грызть незачем — жертва уже на расстоянии удара. */
    private static final double GIVE_UP = 3.0;

    /** Радиус выедаемой полости. */
    private static final int BITE = 3;

    /**
     * Тиков на единицу прочности.
     *
     * <p>Камень исчезает за тик, обсидиан за семь. Это не преграда, а
     * запинка — ровно настолько, чтобы разница между песком и обсидианом была
     * заметна на слух.
     */
    private static final double TICKS_PER_HARDNESS = 0.13;

    /** Не чаще, чем раз в столько тиков, отбивать звук укуса. */
    private static final int SOUND_INTERVAL = 3;

    /**
     * Громкость хруста — и дальность: четыре — это шестьдесят четыре блока.
     *
     * <p>Хруст — часть его рёва: сквозь толщу слышно, что он идёт, и слышно,
     * что именно он сейчас грызёт.
     */
    private static final float CRUNCH = 4.0F;

    private final Mob mob;

    private int progress;
    private int needed;
    private int soundCooldown;

    /** Сколько тиков осталось до следующего поиска преграды, если впереди было пусто. */
    private int idle;

    private long nextPermissionCheck;

    private boolean allowed = true;

    /** Блоки укуса этого тика; переиспользуется, чтобы не плодить списки. */
    private final LongArrayList mouthful = new LongArrayList();

    public DevourBlocksGoal(Mob mob) {
        this.mob = mob;
        this.setFlags(EnumSet.of(Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = mob.getTarget();
        return target != null && target.isAlive() && mob.distanceToSqr(target) >= GIVE_UP * GIVE_UP;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        progress = 0;
        needed = 0;
        idle = 0;
    }

    /**
     * Можно ли сейчас грызть: настройка, измерение, правило mobGriefing.
     *
     * <p>Проверяется раз в секунду, а не каждый тик: событие о вредительстве
     * мобов не бесплатно, а ответ меняется редко.
     */
    private boolean digAllowed(Level level) {
        long now = level.getGameTime();
        if (now < nextPermissionCheck) {
            return allowed;
        }
        nextPermissionCheck = now + 20;
        JsonConfig.Section config = PerformanceConfig.of("devourer");
        boolean ok = config.bool("dig_enabled");
        JsonArray dimensions = config.list("dimensions");
        if (ok && !dimensions.isEmpty()) {
            ok = false;
            String here = level.dimension().location().toString();
            for (JsonElement element : dimensions) {
                if (element.getAsString().equals(here)) {
                    ok = true;
                    break;
                }
            }
        }
        if (ok && config.bool("respect_mob_griefing")) {
            ok = EventHooks.canEntityGrief(level, mob);
        }
        allowed = ok;
        return ok;
    }

    @Override
    public void tick() {
        LivingEntity target = mob.getTarget();
        if (target == null) {
            return;
        }
        Level level = mob.level();
        if (!digAllowed(level)) {
            progress = 0;
            needed = 0;
            return;
        }
        if (idle > 0) {
            idle--;
            return;
        }
        JsonConfig.Section config = PerformanceConfig.of("devourer");
        int bite = Math.max(1, config.integer("bite_radius"));
        BlockPos centre = BlockPos.containing(mouth(target));

        // Один обход сферы на тик: заодно и что грызть, и насколько крепкое.
        // Позиции копятся числами и без промежуточных объектов: обход идёт каждый тик.
        mouthful.clear();
        float hardest = 0.0F;
        double reach = (double) bite * bite;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -bite; dx <= bite; dx++) {
            for (int dy = -bite; dy <= bite; dy++) {
                for (int dz = -bite; dz <= bite; dz++) {
                    if (dx * dx + dy * dy + dz * dz > reach) {
                        continue;
                    }
                    cursor.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    float hardness = state.getDestroySpeed(level, cursor);
                    if (hardness < 0.0F) {
                        // Неразрушимое не считается и во время укуса: иначе врата
                        // рядом со стеной делали бы стену вечной.
                        continue;
                    }
                    mouthful.add(cursor.asLong());
                    if (hardness > hardest) {
                        hardest = hardness;
                    }
                }
            }
        }

        if (soundCooldown > 0) {
            soundCooldown--;
        }
        if (mouthful.isEmpty()) {
            // Впереди пусто — летим дальше, отсчёт укуса начинается заново.
            progress = 0;
            needed = 0;
            // Пока впереди пусто, преграду можно искать реже.
            idle = Math.max(1, config.integer("scan_interval_ticks")) - 1;
            return;
        }

        if (needed <= 0) {
            needed = Math.max(1, (int) Math.ceil(hardest * TICKS_PER_HARDNESS));
        }
        if (++progress < needed) {
            return;
        }
        progress = 0;
        needed = 0;

        BlockPos first = BlockPos.of(mouthful.getLong(0));
        BlockState sample = level.getBlockState(first);
        if (soundCooldown <= 0) {
            // Звук разрушения, а не удара: удар — это кирка по камню, а тут
            // камень исчезает целиком.
            level.playSound(null, centre, sample.getSoundType(level, first, mob).getBreakSound(),
                    SoundSource.HOSTILE, CRUNCH, 0.5F + mob.getRandom().nextFloat() * 0.3F);
            soundCooldown = SOUND_INTERVAL;
        }

        // Один всплеск частиц на весь укус — и это не мелочь.
        //
        // Раньше блоки убирались через level.destroyBlock, а он на КАЖДЫЙ блок
        // шлёт событие 2001 всем игрокам в 64 блоках. Полость в укусе — до
        // полутора сотен блоков, и грызёт он каждый тик: три тысячи пакетов и
        // три тысячи всплесков частиц в секунду на одного поглотителя. Сервер
        // этого почти не замечает — вымерено, шесть поглотителей стоили 1.2 мс
        // при бюджете 50, — а клиент захлёбывается. В одиночном мире, где
        // клиент и сервер в одном процессе, это и выглядит как падение TPS.
        level.levelEvent(2001, centre, Block.getId(sample));

        // Потолок блоков за тик (0 — без потолка): остаток догрызётся следующим укусом.
        int limit = config.integer("max_blocks_per_tick");
        int count = limit > 0 ? Math.min(mouthful.size(), limit) : mouthful.size();
        for (int i = 0; i < count; i++) {
            BlockPos pos = BlockPos.of(mouthful.getLong(i));
            // Летучий газ не глотается, а вспыхивает у самой пасти.
            if (level.getBlockState(pos).is(GimpanumContent.VOLATILE_NEBULA_GAS.get())) {
                VolatileGasBlock.disturb(level, pos);
                continue;
            }
            // Флаг UPDATE_CLIENTS и ничего сверх: без выпадения (поглотитель не
            // добывает, он поглощает) и без обновления соседей. Обновления
            // соседей здесь не нужны и стоят дорого — за укус их было бы
            // столько же, сколько блоков, помноженное на шесть сторон.
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Куда приходится пасть: перед мордой, а не в центре тела.
     *
     * <p>Отступ считается от габарита, а не числом: сфера обязана захватывать
     * и то место, куда моб вот-вот войдёт, иначе он упирался бы в край
     * собственной полости. Направление берётся к глазам жертвы — червю
     * одинаково всё равно, рыть вверх или вниз.
     */
    private Vec3 mouth(LivingEntity target) {
        Vec3 from = mob.getEyePosition();
        Vec3 towards = target.getEyePosition().subtract(from);
        double length = towards.length();
        if (length < 1.0E-4) {
            return from;
        }
        return from.add(towards.scale(lead() / length));
    }

    private double lead() {
        return mob.getBbWidth() / 2.0 + 1.5;
    }
}
