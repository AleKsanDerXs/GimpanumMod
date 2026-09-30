package com.kirbornu.gimpanum.entity;

import com.kirbornu.gimpanum.entity.goal.BoreChaseGoal;
import com.kirbornu.gimpanum.perf.PerformanceConfig;
import com.kirbornu.gimpanum.entity.goal.DevourBlocksGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.control.FlyingMoveControl;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomFlyingGoal;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Поглотитель космоса — быстрый, лазающий и прогрызающий.
 *
 * <p>Догнать его нельзя: он впятеро быстрее бегущего игрока. Спрятаться за
 * стеной — тоже: породу он проедает с той же скоростью, с какой летит по
 * воздуху, и не по блоку, а полостью в свой рост (см.
 * {@link DevourBlocksGoal}).
 *
 * <p>Защиты от него нет никакой — ни света, ни стен, ни расстояния в пределах
 * чутья. Так решено намеренно: это не противник, которого переигрывают, а
 * событие, которое переживают. Остаётся одно — убить его, пока он не добрался.
 * Прежде свет обращал его в бегство, но защита, которая ставится одним факелом,
 * обесценивала весь замысел.
 */
public class SpaceDevourer extends Monster {

    /**
     * Чужие глотки, которыми подпевает его собственный рёв — по одной на вопль.
     *
     * <p>Один и тот же звук раз в секунду ухо быстро перестаёт слышать. Смесь
     * из четырёх разных чудовищ не привыкает.
     */
    private static final List<SoundEvent> CHORUS = List.of(
            SoundEvents.WARDEN_ROAR,
            SoundEvents.GHAST_SCREAM,
            SoundEvents.ENDER_DRAGON_GROWL,
            SoundEvents.RAVAGER_ROAR);

    /**
     * Громкость его собственных звуков — ранения, смерти, голоса без погони.
     *
     * <p>Громкость в игре одновременно и дальность: выше единицы звук громче
     * не становится, зато слышен на 16 блоков за каждую единицу. Пять — это
     * восемьдесят блоков, вся дальность его чутья.
     */
    private static final float VOICE = 5.0F;

    /**
     * Номер цели, о которой он уже объявил.
     *
     * <p>Именно номер, а не ссылка: ссылка удержала бы в памяти вышедшего из
     * игры игрока до тех пор, пока моб не сменит цель.
     */
    private int lastAnnounced = -1;

    /** Сколько тиков осталось до следующего вопля в погоне. */
    private int chaseCry;

    /**
     * Вышел на охоту по Кошмару Спящего Бога.
     *
     * <p>Пока жертва не потеряна, Поглотитель не исчезает от дальности: иначе
     * охоту обрывало бы то, что жертва просто убежала подальше.
     */
    private boolean hunting;

    /** Сколько тиков длится охота; в сохранение не пишется — после перезапуска отсчёт идёт заново. */
    private int huntTicks;

    public SpaceDevourer(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.xpReward = MobStats.of("space_devourer").integer("experience");
        // hoversInPlace = true: управление полётом само отключает тяготение и
        // больше его не возвращает. Иначе поглотитель падал бы всякий раз,
        // когда цель достигнута и движение остановлено, — то есть посреди
        // прогрызаемого туннеля.
        this.moveControl = new FlyingMoveControl(this, 20, true);
        this.setNoGravity(true);
    }

    /**
     * Летает, а не ходит.
     *
     * <p>Причина не в замысле, а в непроходимости: у моба четыре блока в
     * ширину, и наземный поиск пути почти нигде не находит прохода — отсюда
     * прежнее «стоит рядом и ничего не делает». Летающему проходы не нужны
     * вовсе: он идёт к жертве по прямой, а камень на дороге проедает
     * ({@link DevourBlocksGoal}). Так он и задуман — червь, а не бегун.
     */
    @Override
    protected PathNavigation createNavigation(Level level) {
        FlyingPathNavigation navigation = new FlyingPathNavigation(this, level);
        navigation.setCanOpenDoors(false);
        navigation.setCanFloat(true);
        navigation.setCanPassDoors(true);
        return navigation;
    }

    public static AttributeSupplier.Builder createAttributes() {
        // В воздухе управление полётом читает не MOVEMENT_SPEED, а
        // FLYING_SPEED, и без него моб завис бы на месте. Чутьё — сквозь
        // стены: прятаться от Поглотителя бессмысленно по замыслу, он всё
        // равно прогрызётся.
        return MobStats.attributes(Monster.createMonsterAttributes(), "space_devourer");
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(3, new BoreChaseGoal(this, MobStats.of("space_devourer").integer("attack_interval_ticks")));
        this.goalSelector.addGoal(4, new DevourBlocksGoal(this));
        this.goalSelector.addGoal(6, new WaterAvoidingRandomFlyingGoal(this, 0.6));
        this.goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));

        this.targetSelector.addGoal(1, (HurtByTargetGoal) new HurtByTargetGoal(this)
                .setUnseenMemoryTicks(MobStats.of("space_devourer").integer("memory_ticks")));
        // Предпоследний {@code false} — «видеть цель необязательно». Поглотитель
        // чует жертву сквозь любую толщу, и это не поблажка, а весь его смысл:
        // стена от него не спасает, она лишь откладывает встречу.
        this.targetSelector.addGoal(2,
                new NearestAttackableTargetGoal<>(this, Player.class, 0, false, false, null)
                        .setUnseenMemoryTicks(MobStats.of("space_devourer").integer("memory_ticks")));
    }

    /**
     * Тяготение на поглотителя не действует никогда.
     *
     * <p>Не флагом, а вычислением — и это не придирка. {@code Entity.load}
     * присваивает признак невесомости из тега {@code NoGravity}, которого у
     * призванного и у только что загруженного из чанка моба попросту нет, а
     * отсутствующий тег читается как «нет». Выставленный в конструкторе флаг
     * поэтому доживал ровно до первой загрузки, и поглотитель падал с неба
     * камнем, пока управление полётом не спохватится.
     */
    @Override
    public boolean isNoGravity() {
        return true;
    }

    /** Падать неоткуда, но толчком вниз его всё же можно приложить о землю. */
    @Override
    protected void checkFallDamage(double distance, boolean onGround, BlockState state, BlockPos pos) {
    }

    /**
     * Рёв — при выборе жертвы и потом всю погоню, каждую секунду.
     *
     * <p>Один раз при захвате мало: Поглотитель идёт за жертвой минутами и
     * сквозь стены, и всё это время он должен быть слышен. Иначе выходит
     * тишина, из которой внезапно выламывается стена, — а нужно, чтобы
     * приближение было слышно заранее и с каждым разом ближе.
     *
     * <p>И не просто слышен, а невыносим — так задумано. Громче игрового
     * предела один звук не станет, поэтому давим числом: свой рёв и чужой
     * поверх него, оба на всю дальность чутья и с разной высотой, чтобы не
     * сливались. А жертве вдобавок — ещё раз прямо в ухо, на полной громкости
     * и где бы она ни была: издали позиционный звук затухает, а этот нет.
     */
    @Override
    public void aiStep() {
        super.aiStep();
        if (this.level().isClientSide) {
            return;
        }
        LivingEntity target = this.getTarget();
        if (hunting && target == null) {
            hunting = false;
        }
        if (hunting) {
            // Охота Кошмара не вечна: по истечении devourer.hunt_timeout_seconds
            // Поглотитель снова подчиняется обычному исчезновению вдали от игроков.
            int limit = PerformanceConfig.of("devourer").integer("hunt_timeout_seconds") * 20;
            if (limit > 0 && ++huntTicks > limit) {
                hunting = false;
            }
        }
        int id = target == null ? -1 : target.getId();
        if (target != null && (id != lastAnnounced || --chaseCry <= 0)) {
            roar(target);
            // Лёгкий разброс, чтобы два Поглотителя не ревели в унисон.
            int interval = Math.max(1, MobStats.of("space_devourer").integer("roar_interval_ticks"));
            chaseCry = interval + this.random.nextInt(interval / 4 + 1);
        }
        lastAnnounced = id;
    }

    private void roar(LivingEntity target) {
        if (this.isSilent()) {
            return;
        }
        float volume = (float) (MobStats.of("space_devourer").number("roar_range_blocks") / 16.0);
        float pitch = 0.7F + this.random.nextFloat() * 0.6F;
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                GimpanumSounds.DEVOURER_ROAR.get(), this.getSoundSource(), volume, pitch);
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                CHORUS.get(this.random.nextInt(CHORUS.size())), this.getSoundSource(), volume,
                0.6F + this.random.nextFloat() * 0.6F);
        if (target instanceof ServerPlayer player) {
            player.connection.send(new ClientboundSoundPacket(
                    BuiltInRegistries.SOUND_EVENT.wrapAsHolder(GimpanumSounds.DEVOURER_ROAR.get()),
                    this.getSoundSource(), player.getX(), player.getEyeY(), player.getZ(),
                    1.0F, pitch, this.random.nextLong()));
        }
    }

    @Override
    protected float getSoundVolume() {
        return VOICE;
    }

    /** Назначить жертву и не отставать от неё. */
    public void hunt(Player prey) {
        this.setTarget(prey);
        hunting = true;
        huntTicks = 0;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return !hunting && super.removeWhenFarAway(distance);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (hunting) {
            tag.putBoolean("Hunting", true);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        hunting = tag.getBoolean("Hunting");
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return GimpanumSounds.DEVOURER_AMBIENT.get();
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return GimpanumSounds.DEVOURER_HURT.get();
    }

    @Override
    protected SoundEvent getDeathSound() {
        return GimpanumSounds.DEVOURER_DEATH.get();
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        this.playSound(GimpanumSounds.DEVOURER_STEP.get(), 2.0F, 1.0F);
    }

    /**
     * Убрать трещины, если поглотитель погиб посреди укуса.
     *
     * <p>Цели не останавливаются, когда сущность убирают из мира, поэтому
     * узор разрушения остался бы на блоке до следующего обновления.
     */
    @Override
    public void remove(RemovalReason reason) {
        if (!this.level().isClientSide) {
            this.level().destroyBlockProgress(this.getId(), this.blockPosition(), -1);
        }
        super.remove(reason);
    }

    @Override
    public SoundSource getSoundSource() {
        return SoundSource.HOSTILE;
    }

    /**
     * Свет ничего не решает.
     *
     * <p>{@link net.minecraft.world.entity.monster.Monster} оценивает точку
     * появления по освещённости, и чем светлее — тем хуже. В Гимпануме вечный
     * полдень и {@code ambient_light: 1.0}, то есть предельно светло везде:
     * по этой мерке всё измерение непригодно, и ни один моб из ветки Монстра
     * не появился бы нигде и никогда. Мерку убираем — по той же причине, по
     * какой свет не участвует и в условиях появления.
     */
    @Override
    public float getWalkTargetValue(BlockPos pos, LevelReader level) {
        return 0.0F;
    }

}
